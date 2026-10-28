package com.heping.erpnative.data

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 🔴🔴 v5.7.4 离线队列同步器（参考管家婆「联网自动上传」）。
 *
 * 触发时机：
 *  ① App 启动（进系统后）
 *  ② 网络恢复（NetState.online 由 false → true）
 *  ③ 设置页手动点「立即同步」
 *  ④ 离线提交完单立刻试一次（可能用户刚恢复网络）
 *
 * 🔴🔴 幂等是全部安全性的地基：
 *   每条队列记录带 `client_id`（UUID）→ 提交时作为 `client_order_id` 传给服务端。
 *   服务端若已收过这张单，直接返回原单（duplicate=true），**不会重复建单、重复扣库存**。
 *   → 所以这里「超时后重试」「用户手动再点一次同步」都是安全的。
 *
 * 🔴 失败分类（决定要不要继续重试）：
 *   - IOException（网络层）→ 保留队列，等下次网络恢复
 *   - 4xx（业务错误：商品不存在、客户不存在、越权）→ 标 failed，不再自动重试
 *     （重试一万次也一样错，只会一直卡在队列里）
 *   - 5xx（服务端错）→ 保留队列，下次再试
 */
object SyncManager {

    private val gson = Gson()
    /** 🔴 同步必须串行：两个同步任务同时跑会互相把对方刚同步掉的记录重复提交 */
    private val mutex = Mutex()

    /** 同步结果摘要（供 UI 显示） */
    data class Result(
        val ok: Int = 0,
        val failed: Int = 0,
        val remain: Int = 0,
        val message: String? = null
    )

    @Volatile
    var lastResult: Result? = null
        private set

    /** 同步进行中（UI 转圈用） */
    @Volatile
    var syncing: Boolean = false
        private set

    /**
     * 把本地待同步队列逐条提交到服务端。
     * @param ctx 用于拿 LocalOrderDb（applicationContext 足够）
     * @param force true = 连「failed」状态的也再试一次（用户手动点「全部重试」）
     */
    suspend fun sync(ctx: Context, force: Boolean = false): Result = mutex.withLock {
        syncing = true
        try {
            val db = LocalOrderDb(ctx)
            val list = db.listAll()
            if (list.isEmpty()) {
                val r = Result(remain = 0, message = "没有待同步的单据")
                lastResult = r
                return@withLock r
            }
            var ok = 0
            var failed = 0
            var lastErr: String? = null

            for (po in list) {
                // 已失败的（非 force）→ 跳过，等用户手动重试
                if (po.status == "failed" && !force) { failed++; continue }
                val req = db.toRequest(po.payload)
                if (req == null) {
                    // payload 解析不出来 = 脏数据，重试也没用
                    db.markFailed(po.clientId, "本地数据损坏，无法解析")
                    failed++
                    lastErr = "本地数据损坏"
                    continue
                }
                try {
                    // 🔴 把 client_id 塞回去，保证每次重试用的是**同一个**幂等键
                    val r = ErpClient.api.createOrder(req.copy(clientOrderId = po.clientId))
                    db.remove(po.clientId)
                    ok++
                } catch (e: Exception) {
                    val msg = e.userMessage()
                    lastErr = msg
                    if (isNetworkError(e)) {
                        // 网络层失败 → 保留在队列，等下次
                        db.markFailed(po.clientId, msg)
                    } else if (isBizError(e)) {
                        // 业务错误 → 标 failed，不再自动重试
                        db.markFailed(po.clientId, msg)
                    } else {
                        db.markFailed(po.clientId, msg)
                    }
                    failed++
                    // 网络不通 → 后面的单也发不出去，直接停（省电、也避免一堆无谓请求）
                    if (isNetworkError(e)) break
                }
            }

            val remain = db.count()
            val r = Result(
                ok = ok,
                failed = failed,
                remain = remain,
                message = when {
                    ok == 0 && failed == 0 -> "没有待同步的单据"
                    ok > 0 && failed == 0 -> "已同步 $ok 单"
                    ok > 0 -> "已同步 $ok 单，$failed 单失败（${lastErr ?: "未知原因"}）"
                    else -> "同步失败：${lastErr ?: "未知原因"}"
                }
            )
            lastResult = r
            r
        } finally {
            syncing = false
        }
    }

    /** 待同步数量（UI 角标） */
    suspend fun pendingCount(ctx: Context): Int = withContext(Dispatchers.IO) {
        try { LocalOrderDb(ctx).count() } catch (_: Exception) { 0 }
    }

    /** 待同步明细（设置页列表） */
    suspend fun pendingList(ctx: Context): List<PendingOrder> = withContext(Dispatchers.IO) {
        try { LocalOrderDb(ctx).listAll() } catch (_: Exception) { emptyList() }
    }

    /** 单条重试（用户点某一条的「重试」） */
    suspend fun retryOne(ctx: Context, clientId: String): Result {
        try { LocalOrderDb(ctx).markPending(clientId) } catch (_: Exception) { }
        return sync(ctx, force = true)
    }

    /** 全部重试 */
    suspend fun retryAll(ctx: Context): Result {
        try { LocalOrderDb(ctx).resetAllPending() } catch (_: Exception) { }
        return sync(ctx, force = true)
    }

    /** 删除一条待同步（用户不想传了） */
    suspend fun deleteOne(ctx: Context, clientId: String) {
        withContext(Dispatchers.IO) {
            try { LocalOrderDb(ctx).remove(clientId) } catch (_: Exception) { }
        }
    }

    /**
     * 4xx = 业务错误（参数错/权限不足/商品不存在）→ 重试无用，标记后不再自动重试。
     * 5xx = 服务端错 → 仍可重试。
     */
    private fun isBizError(e: Throwable): Boolean {
        val he = e as? retrofit2.HttpException ?: return false
        return he.code() in 400..499
    }
}
