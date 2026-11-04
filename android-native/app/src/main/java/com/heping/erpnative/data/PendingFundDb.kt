package com.heping.erpnative.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import java.util.UUID

/**
 * 资金流水「记一笔」的**离线队列**（v5.7.12）
 *
 * 🔴 为什么独立建库而不扩 LocalOrderDb：
 *    LocalOrderDb 是已验证的**开单**离线核心（PendingOrder 存 CreateOrderRequest）。
 *    资金笔数少、字段不同，塞进同一张表要改表结构 + 改 SyncManager，风险不划算。
 *    独立库 = 改坏了也不影响开单离线。
 *
 * 🔴 幂等：每条自带 clientFundId（UUID），服务端用唯一索引去重
 *    （fund_records.client_fund_id 部分唯一索引，NULL/空串互不影响）。
 */
data class PendingFund(
    val clientFundId: String,
    val payload: String,          // FundBody 的 JSON
    val summary: String,          // 列表展示用：日期 + 类别 + 金额
    val amount: Double,
    val createdAt: Long,
    val status: String = "pending",   // pending | failed
    val error: String? = null
)

class PendingFundDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    companion object {
        private const val NAME = "erp_fund_offline.db"
        private const val VERSION = 1
        const val TABLE = "pending_funds"
        /** 幂等 id：一条离线记账一个 UUID，服务端靠它去重 */
        fun newId(): String = UUID.randomUUID().toString()
    }

    private val gson = Gson()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
              client_fund_id TEXT PRIMARY KEY,
              payload TEXT NOT NULL,
              summary TEXT DEFAULT '',
              amount REAL DEFAULT 0,
              created_at INTEGER NOT NULL,
              status TEXT DEFAULT 'pending',
              error TEXT
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // 首版，无历史数据；保守重建
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun enqueue(body: FundBody, summary: String) {
        writableDatabase.insertWithOnConflict(
            TABLE, null,
            ContentValues().apply {
                put("client_fund_id", body.clientFundId)
                put("payload", gson.toJson(body))
                put("summary", summary)
                put("amount", body.amount)
                put("created_at", System.currentTimeMillis())
                put("status", "pending")
                putNull("error")
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun listAll(): List<PendingFund> {
        val out = mutableListOf<PendingFund>()
        readableDatabase.query(TABLE, null, null, null, null, null, "created_at ASC").use { c ->
            while (c.moveToNext()) {
                out.add(
                    PendingFund(
                        clientFundId = c.getString(c.getColumnIndexOrThrow("client_fund_id")),
                        payload = c.getString(c.getColumnIndexOrThrow("payload")),
                        summary = c.getString(c.getColumnIndexOrThrow("summary")) ?: "",
                        amount = c.getDouble(c.getColumnIndexOrThrow("amount")),
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                        status = c.getString(c.getColumnIndexOrThrow("status")) ?: "pending",
                        error = c.getString(c.getColumnIndexOrThrow("error"))
                    )
                )
            }
        }
        return out
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun remove(clientFundId: String) {
        writableDatabase.delete(TABLE, "client_fund_id=?", arrayOf(clientFundId))
    }

    fun markFailed(clientFundId: String, err: String) {
        writableDatabase.update(
            TABLE,
            ContentValues().apply { put("status", "failed"); put("error", err) },
            "client_fund_id=?", arrayOf(clientFundId)
        )
    }

    fun markPending(clientFundId: String) {
        writableDatabase.update(
            TABLE, ContentValues().apply { put("status", "pending"); putNull("error") },
            "client_fund_id=?", arrayOf(clientFundId)
        )
    }

    fun toBody(payload: String): FundBody? =
        try { gson.fromJson(payload, FundBody::class.java) } catch (_: Exception) { null }

}

/**
 * 资金离线同步器：联网后把队列里的记账逐条上传。
 * 由 NetState.online 触发（与开单同步并列，互不阻塞）。
 */
object FundSync {
    data class Result(val ok: Int = 0, val fail: Int = 0, val errs: List<String> = emptyList())

    suspend fun sync(ctx: Context): Result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val db = PendingFundDb(ctx)
        val rows = db.listAll()
        if (rows.isEmpty()) return@withContext Result()
        var ok = 0
        var fail = 0
        val errs = mutableListOf<String>()
        for (r in rows) {
            val body = db.toBody(r.payload)
            if (body == null) { db.remove(r.clientFundId); continue }
            try {
                ErpClient.api.addFund(body)
                db.remove(r.clientFundId)
                ok++
            } catch (e: Exception) {
                // 🔴 只有「明确的业务/参数错误」才标 failed；网络类错误保持 pending 等下次
                val msg = e.message ?: "未知错误"
                val isPermanent = msg.contains("40") || msg.contains("必填") || msg.contains("不合法")
                if (isPermanent) { db.markFailed(r.clientFundId, msg); fail++ }
                errs.add(msg)
            }
        }
        Result(ok, fail, errs.take(3))
    }
}
