package com.heping.erpnative.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson

/**
 * 🔴🔴 v5.7.4 离线开单队列（参考管家婆「断网也能录单，联网自动上传」）。
 *
 * 为什么用原生 SQLiteOpenHelper 而不是 Room：
 *  ① Room 需要加 kapt/ksp 注解处理器 + 一堆依赖 → 构建变慢、APK 变大；
 *  ② 这里只有一张最简单的队列表，SQLiteOpenHelper 十几行就够；
 *  ③ 引入新依赖的风险（kapt 与 R8 的兼容）不值得。
 *
 * 🔴 幂等凭据 = `client_id`（UUID）。它会随单提交给服务端（client_order_id），
 *   服务端据此去重 → **重试多少次都只会建出一张单**。这是本地队列能安全重试的前提。
 */

/**
 * 一条待同步的离线单。
 * 🔴 放在类**外面**（顶层）：放 companion object 里外面拿不到（要写 LocalOrderDb.Companion.PendingOrder），
 *   顶层后直接 LocalOrderDb.PendingOrder。
 */
data class PendingOrder(
    val clientId: String,
    val payload: String,       // CreateOrderRequest 的 JSON
    val customerName: String,
    val amount: Double,
    val createdAt: Long,
    val status: String,        // pending | failed
    val error: String?,
    val serverOrderNo: String?
)

class LocalOrderDb(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    companion object {
        private const val NAME = "erp_offline.db"
        private const val VERSION = 1
        const val TABLE = "pending_orders"
    }

    private val gson = Gson()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
              client_id TEXT PRIMARY KEY,
              payload TEXT NOT NULL,
              customer_name TEXT DEFAULT '',
              amount REAL DEFAULT 0,
              created_at INTEGER NOT NULL,
              status TEXT DEFAULT 'pending',
              error TEXT,
              server_order_no TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pending_created ON $TABLE(created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 是首个版本；以后加字段走 onUpgrade 迁移，不丢队列
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    /** 入队一条待同步单。client_id 相同则覆盖（幂等：同一单不会重复入队） */
    fun enqueue(clientId: String, payload: String, customerName: String, amount: Double) {
        val cv = ContentValues().apply {
            put("client_id", clientId)
            put("payload", payload)
            put("customer_name", customerName)
            put("amount", amount)
            put("created_at", System.currentTimeMillis())
            put("status", "pending")
            putNull("error")
            putNull("server_order_no")
        }
        writableDatabase.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** 全部待同步（含失败），按创建时间正序 —— 先进先出，保证离线单按录入顺序上账 */
    fun listAll(): List<PendingOrder> {
        val out = mutableListOf<PendingOrder>()
        readableDatabase.query(
            TABLE, null, null, null, null, null, "created_at ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    PendingOrder(
                        clientId = c.getString(c.getColumnIndexOrThrow("client_id")) ?: "",
                        payload = c.getString(c.getColumnIndexOrThrow("payload")) ?: "",
                        customerName = c.getString(c.getColumnIndexOrThrow("customer_name")) ?: "",
                        amount = c.getDouble(c.getColumnIndexOrThrow("amount")),
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                        status = c.getString(c.getColumnIndexOrThrow("status")) ?: "pending",
                        error = c.getString(c.getColumnIndexOrThrow("error")),
                        serverOrderNo = c.getString(c.getColumnIndexOrThrow("server_order_no"))
                    )
                )
            }
        }
        return out
    }

    /** 待同步数量（UI 角标用） */
    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** 同步成功 → 删除 */
    fun remove(clientId: String) {
        writableDatabase.delete(TABLE, "client_id=?", arrayOf(clientId))
    }

    /** 同步失败 → 记原因（保留在队列里，等下次重试；不无限重试同一张错单） */
    fun markFailed(clientId: String, err: String) {
        val cv = ContentValues().apply {
            put("status", "failed")
            put("error", err)
        }
        writableDatabase.update(TABLE, cv, "client_id=?", arrayOf(clientId))
    }

    /** 重置为待同步（用户点「重试」时用） */
    fun markPending(clientId: String) {
        val cv = ContentValues().apply {
            put("status", "pending")
            putNull("error")
        }
        writableDatabase.update(TABLE, cv, "client_id=?", arrayOf(clientId))
    }

    /** 全量重置为待同步（设置页「全部重试」） */
    fun resetAllPending() {
        val cv = ContentValues().apply {
            put("status", "pending")
            putNull("error")
        }
        writableDatabase.update(TABLE, cv, null, null)
    }

    /** 把 payload JSON 还原成下单请求 */
    fun toRequest(payload: String): CreateOrderRequest? =
        try { gson.fromJson(payload, CreateOrderRequest::class.java) } catch (_: Exception) { null }
}
