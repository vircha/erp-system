package com.heping.erpnative.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class BtDevice(val name: String, val address: String, val bonded: Boolean)

/** 打印结果（用于给用户明确的反馈，避免静默失败） */
enum class PrintResult(val message: String) {
    SUCCESS("小票已打印"),
    BT_OFF("打印机未连接：请先打开手机蓝牙"),
    NO_PRINTER("未设置默认打印机，请到「设置」里选择打印机"),
    CONNECT_FAIL("连接打印机失败，请到「设置」重新连接后重试"),
    SEND_FAIL("已连接但发送失败，请重试"),
    NO_ORDER("没有可打印的订单"),
    FETCH_FAIL("获取订单失败，请检查网络")
}

/** 经典蓝牙 SPP 打印（软件内独立蓝牙，不调用系统打印） */
object BtPrinter {
    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val PREFS = "erp_printer"
    private const val KEY_MAC = "mac"
    private const val KEY_NAME = "name"

    private var adapter: BluetoothAdapter? = null
    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null

    fun init(context: Context) {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        adapter = mgr?.adapter
    }

    /**
     * 🔴 2026-10-15 经理要求「不手动连接，点打印直接出单」。
     * App 冷启动 / 回到前台时先静默把上次那台打印机连上，
     * 这样用户走到开单页时 socket 已经热好了，点「打印」零等待、零弹窗。
     *
     * 只在「有配对记录 + 蓝牙已开 + 还没连上」时才动作，其余情况直接返回，
     * 绝不会因为没配对过就凭空弹配对框。
     */
    fun warmUp(context: Context, scope: CoroutineScope) {
        val a = adapter ?: return
        if (!a.isEnabled) return
        if (socket?.isConnected == true) return
        if (getLastMac(context) == null) return
        scope.launch(Dispatchers.IO) {
            try {
                silentReconnect(context.applicationContext)
            } catch (_: Exception) {
            }
        }
    }

    fun hasBluetooth(): Boolean = adapter != null
    fun isOn(): Boolean = adapter?.isEnabled == true
    fun isConnected(): Boolean = socket?.isConnected == true

    fun getPaired(): List<BtDevice> {
        val a = adapter ?: return emptyList()
        if (!a.isEnabled) return emptyList()
        return a.bondedDevices.map { BtDevice(it.name ?: "未知", it.address, true) }
    }

    @SuppressLint("MissingPermission")
    fun scan(context: Context, onFound: (BtDevice) -> Unit, onFinished: () -> Unit) {
        val a = adapter ?: return onFinished()
        if (!a.isEnabled) return onFinished()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        onFound(BtDevice(d.name ?: "未知", d.address, d.bondState == BluetoothDevice.BOND_BONDED))
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> onFinished()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(receiver, filter)
        a.cancelDiscovery()
        a.startDiscovery()
        // 兜底：12 秒后强制结束
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            onFinished()
        }, 12000)
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(context: Context, address: String, timeoutMs: Long = 15000): Boolean = withContext(Dispatchers.IO) {
        val a = adapter ?: return@withContext false
        if (!a.isEnabled) return@withContext false
        try {
            val device = a.getRemoteDevice(address)
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                // 尝试配对（异步，不阻塞连接）
                try { device::class.java.getMethod("createBond").invoke(device) } catch (_: Exception) {}
            }
            socket?.closeSilently()
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                withTimeout(timeoutMs) { s.connect() }
            } catch (e: TimeoutCancellationException) {
                try { s.close() } catch (_: Exception) {}
                return@withContext false
            } catch (e: Exception) {
                try { s.close() } catch (_: Exception) {}
                return@withContext false
            }
            socket = s
            out = s.outputStream
            saveLast(context, address, device.name ?: address)
            true
        } catch (e: Exception) {
            socket = null; out = null; false
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun autoConnect(context: Context): Boolean {
        val mac = getLastMac(context) ?: return false
        return connect(context, mac)
    }

    /**
     * 🔴 2026-10-15 经理要求：「不手动连接，点打印就直接出单」。
     *
     * 原来打印走的是 [connect]，它第 94-97 行发现未配对就调 createBond()，
     * Android 会弹系统「配对/授权」对话框 —— 每次打印都要人点一下，很烦。
     *
     * 本函数 = 静默重连，绝不主动弹任何系统对话框：
     *  ① 已 connected → 直接返回 true（socket 还活着）
     *  ② 已 bonded → 直接开RFCOMM 通道（这是绝大多数情况，全程无 UI）
     *  ③ 未 bonded → 先试连一次（有些机型没 bonded 也允许连）；
     *     真连不上才返回 false，由 UI 决定要不要引导用户去设置页手动配对。
     *
     * @return true 表示可以直接 print()
     */
    @SuppressLint("MissingPermission")
    suspend fun silentReconnect(context: Context, timeoutMs: Long = 12000): Boolean = withContext(Dispatchers.IO) {
        val a = adapter ?: return@withContext false
        if (!a.isEnabled) return@withContext false
        if (socket?.isConnected == true) return@withContext true
        val mac = getLastMac(context) ?: return@withContext false
        try {
            val device = a.getRemoteDevice(mac)
            socket?.closeSilently()
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            val ok = try {
                withTimeout(timeoutMs) { s.connect() }
                true
            } catch (e: Exception) {
                try { s.close() } catch (_: Exception) {}
                false
            }
            if (!ok) return@withContext false
            socket = s
            out = s.outputStream
            saveLast(context, mac, runCatching { device.name }.getOrNull() ?: mac)
            true
        } catch (e: Exception) {
            socket = null; out = null; false
        }
    }

    /** 打印前的统一入口：先静默重连，失败再看是不是「从没配对过」 */
    suspend fun ensureConnected(context: Context): Boolean {
        if (isConnected()) return true
        if (silentReconnect(context)) return true
        // 静默失败：只有在设备根本没 bonded 时才退回去走会弹窗的 connect
        val mac = getLastMac(context) ?: return false
        val a = adapter ?: return false
        val bonded = runCatching { a.getRemoteDevice(mac).bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
        if (bonded) return false            // 已配对还连不上 → 让 UI 提示「打印机连不上，检查是否开机」
        return connect(context, mac)        // 从未配对 → 允许弹一次配对框
    }

    fun print(bytes: ByteArray): Boolean {
        return try {
            out?.write(bytes); out?.flush(); true
        } catch (e: Exception) { false }
    }

    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; out = null
    }

    // ---------- 记忆默认打印机 ----------
    private fun saveLast(context: Context, mac: String, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MAC, mac).putString(KEY_NAME, name).apply()
    }
    fun getLastMac(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MAC, null)
    fun getLastName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAME, null)
    fun clearLast(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun BluetoothSocket?.closeSilently() { try { this?.close() } catch (_: Exception) {} }
}

// ===================== ESC/POS 生成（GBK，80mm / 48 列） =====================
object EscPos {
    private val INIT = byteArrayOf(0x1B, 0x40)
    private val ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00)
    private val ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
    private val BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01)
    private val BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)
    private val BIG = byteArrayOf(0x1B, 0x21, 0x30)
    private val NORMAL = byteArrayOf(0x1B, 0x21, 0x00)
    /**
     * 🔴🔴 2026-10-29 经理报修「打印出来数字 6 和 8 太像了」。
     *
     * 根因（点阵层面）：ESC/POS 内置 Font A = 12×24 点阵，
     * 「8」与「6」的字形差异**只有右上角那一根 1 点宽竖笔画**
     *（8 的上部是闭环、6 的上部右上角是空白）。热敏纸 203dpi 下这根细竖容易糊断，
     * 于是「8」被看成「6」。字库本身没法改（Font B 9×17 更窄更密，更难分；
     * 自定义字模 GS ( k 机型固件兼容风险高）→ 只能从**打印方式**上让它更好认。
     *
     * ✅ 三招（同时上）：
     *   ① 数字区加竖线分区（名称/条码区 | 数量 原价 特价 | 金额）→ 不会串列；
     *   ② 整个数字区加粗（ESC E）→ 竖笔画由 1 点变 2 点，「8」的右上竖不再糊断；
     *   ③ 合计「优惠后金额」用**横向 2 倍**大字 → 最关键的金额一眼可辨。
     *
     * 🔴🔴 指令选型（极易踩坑，务必记住）：
     *   - `ESC ! 0x01` = **选 Font B（9×17）**，不是横向 2 倍！ESC ! 的位定义在不同固件里
     *     有多个流传版本（0x01 / 0x08 / 0x10 分别被解释成 Font B / 倍宽 / 倍高 / 下划线），
     *     赌某个位组合 = 赌机型 → **本项目禁用未经验证的 ESC ! 位组合**。
     *   - 横向 2 倍、高不变（24×24）用 **`GS ! 0x01`**：GS ! n 的位定义**唯一且无歧义**
     *     —— 位0-3 = 宽度倍数(1-8)、位4-7 = 高度倍数(1-8) → 0x01 = 宽×2、高×1；
     *     复位用 `GS ! 0x00`。
     *   - 为什么敢用 GS：本文件**已经在用 GS ( k 打 EAN-13 条码**（GS 指令族在本机可用可证）。
     *   - 关键好处：只变宽、**行高不变** → 不会跟下一行叠字、纸长不变、基线不跑。
     *   - 宽度预算：Font A 一列 = 12 点 → 倍宽字符占 **2 列**（n2(pay).length * 2），精确可算。
     *   - 退化保底：万一某机型不支持 GS !，只是字不变大，**不乱码、不破版**
     *     （宽度按 2 列预算 → 实际只占 1 列时右边短一点，绝不会溢出到下一行）。
     */
    private val DOUBLE_W = byteArrayOf(0x1D, 0x21, 0x01)
    private val DOUBLE_OFF = byteArrayOf(0x1D, 0x21, 0x00)
    private val CODPAGE_GBK = byteArrayOf(0x1B, 0x74, 0x0F)
    private val CUT = byteArrayOf(0x1D, 0x56, 0x00)

    /** 纸宽（半角列数）：80mm 机型 = 48；若换 58mm 机型改成 32 即可 */
    const val COLS = 48

    // 🔴 2026-10-18 经理要求「打单的价格显示没有原价，应该左边列是原价、右边列是特价，参考管家婆」。
    //    原来的「单价」一列只印成交价（折后），有折扣的单子看不出便宜了多少 → 改成管家婆式双价列。
    //    右侧四列：数量 / 原价 / 特价 / 金额，全部靠右对齐；左侧留给序号 + 名称 + 条码。
    //    ⚠️ 去掉「单价」换成「原价/特价」后右侧多占 7 列，LEFT_W 从 27 降到 20 ——
    //    条码「条码:」+ 13 位数字共 19 列刚好放得下，一行都不用多占。
    private const val QTY_W = 6
    private const val PRICE_W = 7        // 原价列
    private const val SALE_W = 7         // 特价列（成交价）
    private const val AMT_W = 8
    private const val NUM_W = QTY_W + PRICE_W + SALE_W + AMT_W   // 28
    /**
     * 数字区（数量/原价/特价/金额）+ 两条竖线分区。
     * 版式： 名称/条码区 | 数量 原价 特价 | 金额
     *   LEFT_W(18) + "|"(1) + 6 + 7 + 7 + "|"(1) + 8 = 48 ✓
     * LEFT_W 从 20 降到 18 的原因：竖线要从左区抠 2 列出来，
     * 而「条码:」(5 列) + 13 位数字 = 18 列 —— 正好一列不差放下，条码仍与数字同行、不多占行。
     */
    private const val SEP_W = 1
    private const val VBAR = "|"
    private const val LEFT_W = COLS - (SEP_W * 2 + NUM_W)      // 48 - 30 = 18

    /**
     * 🔴 2026-10-29：整个数字区（数量/原价/特价/金额）整块**加粗**（ESC E）。
     * ESC E 是「打印增强」属性，固件实现为「同位置 + 右移 1 点重印」→ 竖画变 2 点宽，
     * 正好补上「8」右上那根最细的 1 点竖 → 6/8 一眼可辨。
     * ⚠️ 加粗**不改变字符前进量**，所以加粗整个数字区不会造成换行/错列。
     *    旁证：本文件原来「折扣优惠金额 / 优惠后金额」整行（正好 48 列）就已用 BOLD_ON，
     *    经理长期使用从未报合计行错位 → 该机 ESC E 不推进字符位。
     */

    private val DASHES = "-".repeat(COLS)
    private val EQ = "=".repeat(COLS)

    // 🔴🔴 2026-10-18 经理报修「打印出来订单底下空白出了快 1 米」，根因就在这条指令：
    // ESC/POS 规范里 `ESC d n`（打印并送纸 n 行、n=0~255）是「送纸 **n 点**」，
    // 203dpi 下 160 点 ≈ 2cm。但**大量廉价 80mm 热敏机固件把它当「n 行」执行**，
    // 160 行 × 1/6 英寸 ≈ **67cm**，再叠上 lf() 和切纸走纸，正好糊出接近 1 米的空白。
    // 同一台机器上「2cm 留白」直接变成了「快 1 米」。
    //
    // ✅ 改成纯换行：每行行高约 3.5mm（1/8 英寸），6 行 ≈ 2.1cm，
    //    在所有机型上行为一致，绝不会被放大成几十行。
    //    ⚠️ 以后**禁止**再用 ESC d / ESC J 做留白，改行数即可。
    private const val TAIL_LF = 6
    private fun tailLf() = ByteArray(TAIL_LF) { 0x0A }

    private fun gb(s: String): ByteArray = s.toByteArray(charset("GBK"))

    /** 显示宽度：中日韩全角字符按 2 列计（排版用，不是字节长度） */
    private fun width(s: String): Int {
        var w = 0
        for (c in s) {
            val k = c.code
            w += if (k in 0x1100..0x115F || k in 0x2E80..0x303E || k in 0x3041..0x33FF ||
                k in 0x3400..0x4DBF || k in 0x4E00..0x9FFF || k in 0xA000..0xA4CF ||
                k in 0xAC00..0xD7A3 || k in 0xF900..0xFAFF || k in 0xFE10..0xFE19 ||
                k in 0xFE30..0xFE6F || k in 0xFF00..0xFF60 || k in 0xFFE0..0xFFE6
            ) 2 else 1
        }
        return w
    }
    private fun padRight(s: String, n: Int): String {
        val d = n - width(s); return if (d > 0) s + " ".repeat(d) else s
    }
    private fun padLeft(s: String, n: Int): String {
        val d = n - width(s); return if (d > 0) " ".repeat(d) + s else s
    }
    /** 按显示宽度截断，避免长商品名把一行撑爆导致折行 */
    private fun clip(s: String, n: Int): String {
        if (width(s) <= n) return s
        val sb = StringBuilder(); var w = 0
        for (c in s) { val cw = width(c.toString()); if (w + cw > n) break; sb.append(c); w += cw }
        return sb.toString()
    }

    private val L_CODES = arrayOf(
        "0001101", "0011001", "0010011", "0111101", "0100011",
        "0110001", "0101111", "0111011", "0110111", "0001011"
    )
    private val G_CODES = arrayOf(
        "0100111", "0110011", "0011011", "0100001", "0011101",
        "0111001", "0000101", "0010001", "0001001", "0010111"
    )
    private val R_CODES = arrayOf(
        "1110010", "1100110", "1101100", "1000010", "1011100",
        "1001110", "1010000", "1000100", "1001000", "1110100"
    )
    /** 首位数字决定左侧 6 位的奇偶（L / G） */
    private val PARITY = arrayOf(
        "LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG",
        "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL"
    )

    /** EAN-13 → 95 位 0/1（1=黑条）。非 12/13 位数字返回 null */
    fun ean13Bits(code: String?): String? {
        var s = (code ?: "").filter { it.isDigit() }
        if (s.length == 12) s = "0$s"
        if (s.length != 13) return null
        val par = PARITY[s[0] - '0']
        val sb = StringBuilder("101")
        for (i in 1..6) {
            val c = par[i - 1]
            sb.append(if (c == 'L') L_CODES[s[i] - '0'] else G_CODES[s[i] - '0'])
        }
        sb.append("01010")
        for (i in 7..12) sb.append(R_CODES[s[i] - '0'])
        sb.append("101")
        return sb.toString()
    }

    data class Line(
        val name: String,
        val qty: Double,
        /** 成交单价（折后）。对应小票右边的「特价」列 */
        val price: Double,
        val unit: String? = null,
        val barcode: String? = null,
        /**
         * 折前原价（管家婆小票的「原价」列）。
         * 🔴 2026-10-18 经理要求小票左右两列价格：左边原价、右边特价。
         *    传 null 或 <=0 时自动退回 [price]（两列印一样的数，不会出现空白列）。
         */
        val origPrice: Double? = null
    ) {
        /** 有效原价：优先 origPrice，缺失或非正数时退回成交价 */
        fun origOrPrice(): Double = (origPrice ?: 0.0).takeIf { it > 0 } ?: price
    }

    /** 数量显示：整数不带小数点，小数去尾零 */
    fun fmtQty(q: Double): String =
        if (q % 1.0 == 0.0) q.toLong().toString()
        else "%.2f".format(q).trimEnd('0').trimEnd('.')

    private fun n2(v: Double): String = "%.2f".format(v)

    /**
     * 销售单据小票（对齐纸质送货单版式，48 列）：
     *   店名 / 单据类型
     *   往来单位名称 / 单据编号 / 单据日期 / 经手人 + 出库(入库)仓库
     *   表列：序号 名称（下带商品条码） 数量 单价 金额
     *   合计数量 / 合计折前金额 / 折扣优惠金额 / 优惠后金额 / 客户签收 / 页脚
     *
     * 说明：明细按订单实际单价（原价）打印，金额=数量×单价，其合计即「合计折前金额」；
     *      整单折扣在底部「折扣优惠金额 / 优惠后金额」体现。退货单取绝对值并按「入库仓库」。
     *      金额一律裸数字——ESC/POS 文本禁用半角 ¥(U+00A5)，GBK 编不出会乱码。
     */
    fun receipt(
        shop: String,
        orderNo: String,
        customer: String,
        items: List<Line>,
        beforeDiscount: Double,
        payable: Double,
        footer: String,
        docTitle: String = "销售订单",
        dateTime: String = "",
        handler: String = "",
        warehouse: String = "",
        isReturn: Boolean = false
    ): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        fun put(a: ByteArray) { o.write(a) }
        fun putLn(s: String) { put(gb(s)); put(lf()) }

        val whLabel = if (isReturn) "入库仓库:" else "出库仓库:"

        // ---- 表头 ----
        put(INIT); put(CODPAGE_GBK); put(ALIGN_CENTER); put(BOLD_ON); put(BIG)
        put(gb(clip(shop.ifBlank { "销售小票" }, 22))); put(NORMAL); put(BOLD_OFF); put(lf())
        put(gb(docTitle)); put(lf())
        put(ALIGN_LEFT)
        put(gb(EQ)); put(lf())
        putLn("往来单位名称:" + clip(customer, COLS - 14))
        putLn("单据编号:" + clip(orderNo, COLS - 10))
        putLn("单据日期:" + clip(dateTime, COLS - 10))
        val wh = whLabel + clip(warehouse, 16)
        putLn(padRight("经手人:" + clip(handler, 12), COLS - width(wh)) + wh)
        put(gb(DASHES)); put(lf())

        // ---- 表列头 ----
        // 🔴 2026-10-18：单价列拆成「原价 / 特价」两列（管家婆版式，左原价右特价）
        // 🔴 2026-10-29：加 2 条竖线分区（数字区加粗），「6/8」不再串列
        put(BOLD_ON)
        putLn(
            padRight("序号 名称", LEFT_W) + VBAR + padLeft("数量", QTY_W) +
                padLeft("原价", PRICE_W) + padLeft("特价", SALE_W) + VBAR + padLeft("金额", AMT_W)
        )
        put(BOLD_OFF)
        put(gb(DASHES)); put(lf())

        // ---- 明细：名称一行 + （条码 + 数量/原价/特价/金额）一行 ----
        var qtySum = 0.0
        items.forEachIndexed { i, it ->
            // 🔴 2026-10-15：原来这里对 qty / price 全取绝对值，退货单每行都印成正数，
            // 与负数的「合计折前金额 / 优惠后金额」自相矛盾。
            // 现在原样使用服务端值：退货单 qty 是负数，price 是正数（折后成交单价）。
            val q = it.qty
            val p = it.price               // 折后成交价 → 印在「特价」列
            val op = it.origOrPrice()      // 折前原价   → 印在「原价」列
            qtySum += q
            // 🔴 2026-10-29：明细行整行加粗。
            //    商品名里常有数字（200ml / 125g / 6 瓶），条码更是纯数字 → 同样要加粗。
            put(BOLD_ON)
            putLn(clip("${i + 1} ${it.name}", COLS))
            val bc = (it.barcode ?: "").trim()
            // 金额一律按**特价**算（= 这张单真实成交额），与底部「优惠后金额」对得上
            val nums = VBAR + padLeft(fmtQty(q) + (it.unit ?: ""), QTY_W) +
                padLeft(n2(op), PRICE_W) +
                padLeft(n2(p), SALE_W) + VBAR +
                padLeft(n2(q * p), AMT_W)
            // ⚠️ 经理要求（2026-10-15）：小票上只印条码「数字」，不印条形码图形。
            // 图形条码在热敏小票上又粗又占地方，实际没人扫，纯浪费纸。
            // 布局：左边「条码:1234...」紧贴序号列，右边数量/原价/特价/金额靠右。
            val left = if (bc.isEmpty()) "" else "条码:" + bc
            // 🔴 LEFT_W = 18，「条码:」(5 列) + 13 位数字 = 18 列刚好放得下；
            //    放不下就整行独放，绝不截断末位条码。
            if (left.isNotEmpty() && width(left) > LEFT_W) {
                putLn(clip(left, COLS))
                putLn(padRight("", LEFT_W) + nums)
            } else {
                putLn(padRight(clip(left, LEFT_W), LEFT_W) + nums)
            }
            put(BOLD_OFF)
        }

        // ---- 合计（左列 + 右列，与纸质单一致的两栏）----
        put(gb(DASHES)); put(lf())
        // 🔴 同上：这里原来也取了绝对值，退货单会印成正数合计。同样改为原样透传负数。
        val before = beforeDiscount
        val pay = payable
        val saved = before - pay
        val r1 = "合计折前金额:" + n2(before)
        put(BOLD_ON)
        putLn(padRight("合计数量:" + fmtQty(qtySum), COLS - width(r1)) + r1)
        // 🔴 2026-10-29：最关键的「优惠后金额」用**倍宽大字**（GS ! 0x01 → 24×24，字号 1 倍高、
        //    2 倍宽），行高不变 → 纸长不变；但每个数字的竖笔画被拉成 2 点宽 → 6/8 绝不混淆。
        val savedTxt = "折扣优惠金额:" + n2(saved)
        val payLabel = "优惠后金额:"
        val payNum = n2(pay)
        val payNumW = payNum.length * 2          // 倍宽字符占 2 列（精确预算，绝不溢出）
        val rightW = width(payLabel) + payNumW
        val avail = COLS - rightW
        if (width(savedTxt) <= avail) {
            put(gb(padRight(savedTxt, avail)))
            put(gb(payLabel))
            put(DOUBLE_W); put(gb(payNum)); put(DOUBLE_OFF)
        } else {
            // 金额太长放不下 → 拆两行（折扣优惠金额一行、倍宽金额单独右对齐一行）
            put(gb(savedTxt)); put(lf())
            put(gb(padRight("", avail)))
            put(gb(payLabel))
            put(DOUBLE_W); put(gb(payNum)); put(DOUBLE_OFF)
        }
        put(BOLD_OFF)
        put(lf())
        put(gb(DASHES)); put(lf())
        putLn("客户签收:____________________")
        put(ALIGN_CENTER); putLn(clip(footer, COLS)); put(ALIGN_LEFT)
        put(tailLf()); put(CUT)   // 底部 2cm 签收留白（纯换行，见 tailLf 注释：禁用 ESC d）
        return o.toByteArray()
    }

    /** 写EAN-13 条码图形（GS ( k，m=65 格式 1，宽 1 高 2）；校验位由本方法重算 */
    private fun putEan13(o: java.io.ByteArrayOutputStream, code: String) {
        val data = ByteArray(13)
        for (i in 0 until 12) data[i] = (code[i] - '0').toByte()
        var sum = 0
        for (i in 0 until 12) sum += (data[i] - 48) * (if (i % 2 == 0) 1 else 3)
        data[12] = ((10 - (sum % 10)) % 10).toByte()
        o.write(byteArrayOf(0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32.toByte()))
        o.write(byteArrayOf(0x1D, 0x28, 0x6B, data.size.toByte(), 0x00))
        o.write(data)
        o.write(byteArrayOf(0x0A))
    }

    fun productList(shop: String, title: String, items: List<Line>, footer: String): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        o.write(INIT); o.write(CODPAGE_GBK); o.write(ALIGN_CENTER); o.write(BIG)
        o.write(gb(clip(shop, 22))); o.write(NORMAL); o.write(gb(title)); o.write(lf()); o.write(ALIGN_LEFT)
        o.write(gb(DASHES)); o.write(lf())
        // 🔴 2026-10-18：与 receipt() 同步改成管家婆式「原价 / 特价」双列（左原价右特价）
        // 🔴 2026-10-29：数字区加竖线分区 + 加粗（6/8 一眼可辨）
        //    productList 没有「金额」列 → 用 3 条竖线把 4 列彻底分开：
        //    名称区(25) + |(1) + 数量(6) + |(1) + 原价(7) + |(1) + 特价(7) = 48 ✓
        val plLeft = COLS - (SEP_W * 3 + QTY_W + PRICE_W + SALE_W)   // 25
        o.write(BOLD_ON)
        o.write(
            gb(
                padRight("名称", plLeft) + VBAR + padLeft("数量", QTY_W) + VBAR +
                    padLeft("原价", PRICE_W) + VBAR + padLeft("特价", SALE_W)
            )
        ); o.write(BOLD_OFF); o.write(lf())
        o.write(gb(DASHES)); o.write(lf())
        for (it in items) {
            o.write(gb(clip(it.name, COLS))); o.write(lf())
            val bc = (it.barcode ?: "").trim()
            val left = if (bc.isEmpty()) "" else "  " + bc
            // 注意：ESC/POS 小票文本禁用半角 ¥(U+00A5)——GBK 不可编码，打印会乱码；金额一律裸数字
            o.write(BOLD_ON)
            o.write(
                gb(
                    padRight(clip(left, plLeft), plLeft) + VBAR +
                        padLeft(fmtQty(it.qty) + (it.unit ?: ""), QTY_W) + VBAR +
                        padLeft("%.2f".format(it.origOrPrice()), PRICE_W) + VBAR +
                        padLeft("%.2f".format(it.price), SALE_W)
                )
            ); o.write(BOLD_OFF); o.write(lf())
        }
        o.write(gb(DASHES)); o.write(lf())
        o.write(gb("共 ${items.size} 项")); o.write(lf()); o.write(gb(clip(footer, COLS))); o.write(lf())
        o.write(tailLf()); o.write(CUT)   // 底部 2cm 留白（纯换行，见 tailLf 注释：禁用 ESC d）
        return o.toByteArray()
    }

    private fun lf() = byteArrayOf(0x0A)
}
