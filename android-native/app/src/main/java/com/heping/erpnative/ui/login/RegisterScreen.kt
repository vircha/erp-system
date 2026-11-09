package com.heping.erpnative.ui.login

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heping.erpnative.data.SessionStore
import com.heping.erpnative.ui.components.ErrorText
import com.heping.erpnative.ui.components.toast
import com.heping.erpnative.vm.AuthViewModel
import kotlinx.coroutines.launch

/**
 * 注册账户（2026-11-07 新增）
 *
 * 🔴🔴 与网页版保持同一套规则，核心是**隐私**：
 *   · 绝不下发/展示任何「套账列表」。老版本注册页会拉 /api/books/public，
 *     把**所有客户的套账名称**明晃晃列出来，任何人打开注册页就能看到客户名录。
 *   · 加入已有套账只能靠**邀请码**（老板在设置里生成后私下发），
 *     服务端用它反查 book_id，客户端全程不知道有哪些套账。
 *
 * 🔴 两种注册方式：
 *   · 新建套账 —— 自己起名，注册后就是该套账的老板
 *   · 用邀请码加入 —— 记为该套账的员工，角色后续由老板调整
 */
/** 中国大陆手机号：1 开头、第二位 3-9、共 11 位 */
private val PHONE_RX = Regex("^1[3-9][0-9]{9}$")

/** 邮箱格式（宽松校验，真正的验证靠验证码） */
private val EMAIL_RX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")

@Composable
fun RegisterScreen(
    vm: AuthViewModel,
    onBack: () -> Unit,
    onEntered: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var joinMode by remember { mutableStateOf(false) }   // false=新建套账 true=邀请码加入
    var bookName by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var captchaText by remember { mutableStateOf("") }
    // 🔴 2026-11-09 账号类型：邮箱（默认）/ 手机号
    var emailMode by remember { mutableStateOf(true) }
    var emailCode by remember { mutableStateOf("") }
    var emailLeft by remember { mutableStateOf(0) }
    var emailSending by remember { mutableStateOf(false) }

    // 🔴 短信验证码（服务端开了短信通道才显示）
    var smsCode by remember { mutableStateOf("") }
    var smsLeft by remember { mutableStateOf(0) }
    var smsSending by remember { mutableStateOf(false) }
    var smsTip by remember { mutableStateOf<String?>(null) }
    var trust by remember { mutableStateOf(false) }

    // 注册必填验证码，进页面就拉一张
    LaunchedEffect(Unit) {
        vm.loadEmailStatus()
        vm.loadSmsStatus()
        if (vm.captcha == null) vm.loadCaptcha()
    }
    LaunchedEffect(emailLeft) {
        if (emailLeft > 0) {
            kotlinx.coroutines.delay(1000)
            emailLeft -= 1
        }
    }
    // 60 秒倒计时
    LaunchedEffect(smsLeft) {
        if (smsLeft > 0) {
            kotlinx.coroutines.delay(1000)
            smsLeft -= 1
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "返回登录") }
            Text("注册账户", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(12.dp))

        // 账号类型切换
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (emailMode) {
                Button(onClick = { emailMode = true }, modifier = Modifier.weight(1f)) { Text("邮箱注册", fontSize = 13.sp) }
                OutlinedButton(onClick = { emailMode = false }, modifier = Modifier.weight(1f)) { Text("手机号注册", fontSize = 13.sp) }
            } else {
                OutlinedButton(onClick = { emailMode = true }, modifier = Modifier.weight(1f)) { Text("邮箱注册", fontSize = 13.sp) }
                Button(onClick = { emailMode = false }, modifier = Modifier.weight(1f)) { Text("手机号注册", fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { v ->
                username = if (emailMode) v.trim().take(64)
                           else v.filter { c -> c.isDigit() }.take(11)
            },
            label = { Text(if (emailMode) "邮箱地址" else "手机号（11 位）") },
            placeholder = { Text(if (emailMode) "例如 name@163.com" else "例如 13800138000") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (emailMode) KeyboardType.Email else KeyboardType.Phone
            ),
            modifier = Modifier.fillMaxWidth()
        )

        // ---------- 邮箱验证码（邮箱模式）----------
        if (emailMode) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = emailCode,
                    onValueChange = { emailCode = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("邮箱验证码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        if (!EMAIL_RX.matches(username)) { vm.error = "请先填写正确的邮箱地址"; return@OutlinedButton }
                        if (captchaText.isBlank()) { vm.error = "请先填写图形验证码"; return@OutlinedButton }
                        vm.error = null
                        emailSending = true
                        scope.launch {
                            val err = vm.sendEmailCode(username, captchaText)
                            emailSending = false
                            captchaText = ""
                            if (err == null) emailLeft = 60 else vm.error = err
                        }
                    },
                    enabled = !emailSending && emailLeft == 0,
                    modifier = Modifier.height(56.dp)
                ) {
                    Text(
                        when {
                            emailSending -> "发送中…"
                            emailLeft > 0 -> "${emailLeft}s"
                            else -> "获取验证码"
                        },
                        fontSize = 12.sp
                    )
                }
            }
            Text(
                "验证码会发到邮箱，可能进垃圾箱。邮箱验证通过即可直接登录。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "手机号注册提交后需管理员审核通过才能登录。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // ---------- 短信验证码（仅服务端开启时显示）----------
        if (vm.smsRequired) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = smsCode,
                    onValueChange = { smsCode = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("短信验证码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        if (!PHONE_RX.matches(username)) { vm.error = "请先填写 11 位手机号"; return@OutlinedButton }
                        if (captchaText.isBlank()) { vm.error = "请先填写图形验证码"; return@OutlinedButton }
                        vm.error = null
                        smsSending = true
                        scope.launch {
                            val err = vm.sendSms(username, captchaText)
                            smsSending = false
                            captchaText = ""          // 图形码已用掉，界面清空
                            if (err == null) {
                                smsLeft = 60
                                smsTip = if (vm.smsMock) "已生成验证码（测试模式，见服务器日志）" else "验证码已发送"
                            } else {
                                vm.error = err
                            }
                        }
                    },
                    enabled = !smsSending && smsLeft == 0,
                    modifier = Modifier.height(56.dp)
                ) {
                    Text(
                        when {
                            smsSending -> "发送中…"
                            smsLeft > 0 -> "${smsLeft}s"
                            else -> "获取验证码"
                        },
                        fontSize = 12.sp
                    )
                }
            }
            smsTip?.let {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("密码（至少 4 位）") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = displayName, onValueChange = { displayName = it },
            label = { Text("姓名（可选）") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))
        Text(
            "如何开始？",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!joinMode) {
                Button(onClick = { joinMode = false }, modifier = Modifier.weight(1f)) { Text("新建套账", fontSize = 13.sp) }
                OutlinedButton(onClick = { joinMode = true }, modifier = Modifier.weight(1f)) { Text("用邀请码加入", fontSize = 13.sp) }
            } else {
                OutlinedButton(onClick = { joinMode = false }, modifier = Modifier.weight(1f)) { Text("新建套账", fontSize = 13.sp) }
                Button(onClick = { joinMode = true }, modifier = Modifier.weight(1f)) { Text("用邀请码加入", fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (joinMode) {
            OutlinedTextField(
                value = inviteCode,
                onValueChange = { inviteCode = it.uppercase() },
                label = { Text("邀请码") },
                placeholder = { Text("向该套账老板索取") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "输入老板给的邀请码，注册后自动加入对应套账（角色为员工，可由老板调整）。",
                fontSize = 11.sp, lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            OutlinedTextField(
                value = bookName, onValueChange = { bookName = it },
                label = { Text("套账名称") },
                placeholder = { Text("自己起个名，如：XX贸易") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "新建后你就是该套账的老板，可自行添加商品和员工。",
                fontSize = 11.sp, lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(14.dp))

        // ---------- 图形验证码（注册必填）----------
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = captchaText, onValueChange = { captchaText = it },
                label = { Text("验证码") }, singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            val svg = vm.captcha?.svg
            if (svg != null) {
                SvgImage(
                    svg = svg,
                    modifier = Modifier.size(width = 110.dp, height = 44.dp)
                        .clickable { vm.loadCaptcha() }
                )
            } else {
                TextButton(onClick = { vm.loadCaptcha() }) { Text("获取验证码", fontSize = 12.sp) }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = trust, onCheckedChange = { trust = it })
            Text("信任此设备（30 天免密登录）", fontSize = 13.sp)
        }

        ErrorText(vm.error)

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                // 前端先挡一道，减少无谓请求
                if (emailMode) {
                    if (!EMAIL_RX.matches(username)) { vm.error = "邮箱格式不正确"; return@Button }
                    if (emailCode.length != 6) { vm.error = "请填写 6 位邮箱验证码"; return@Button }
                } else {
                    if (!PHONE_RX.matches(username)) { vm.error = "账号必须是 11 位手机号（1 开头）"; return@Button }
                }
                if (password.length < 4) { vm.error = "密码至少 4 位"; return@Button }
                if (!joinMode && bookName.isBlank()) { vm.error = "请填写套账名称"; return@Button }
                if (joinMode && inviteCode.isBlank()) { vm.error = "请填写邀请码"; return@Button }
                if (captchaText.isBlank()) { vm.error = "请填写图形验证码"; return@Button }
                if (vm.smsRequired && smsCode.length != 6) { vm.error = "请填写 6 位短信验证码"; return@Button }
                scope.launch {
                    val ok = vm.register(
                        username = username,
                        password = password,
                        displayName = displayName,
                        bookName = if (joinMode) "" else bookName,
                        inviteCode = if (joinMode) inviteCode else "",
                        captchaText = captchaText,
                        trust = trust,
                        smsCode = if (emailMode) "" else smsCode,
                        emailCode = if (emailMode) emailCode else ""
                    )
                    if (ok) {
                        if (vm.trustFull) {
                            toast(ctx, "已达 4 台信任设备上限，本机未被记住", long = true)
                        }
                        // 与登录一致：先尝试进入信任面板，失败则直接进系统
                        if (vm.loadMe()) onEntered() else onEntered()
                    } else if (vm.pendingMsg != null) {
                        // 🔴 注册成功但待审核：不是失败，要明确告知，并回到登录页
                        toast(ctx, vm.pendingMsg ?: "注册成功，等待管理员审核", long = true)
                        vm.pendingMsg = null
                        onBack()
                    } else {
                        captchaText = ""
                    }
                }
            },
            enabled = !vm.loading,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            if (vm.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("注册并进入")
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack) { Text("已有账号？返回登录", fontSize = 13.sp) }
        Spacer(Modifier.height(24.dp))
    }
}
