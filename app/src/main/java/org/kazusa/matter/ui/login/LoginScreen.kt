package org.kazusa.matter.ui.login

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.MatterCard
import org.kazusa.matter.ui.components.TopBarTitleText
import org.kazusa.matter.ui.theme.MatterShapeTokens
import org.kazusa.matter.ui.theme.TextSecondary

import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val countdown by viewModel.countdown.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var phoneInput by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }
    var usernameInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var cookieInput by remember { mutableStateOf("") }

    LaunchedEffect(uiState) {
        if (uiState is LoginUiState.Success) {
            onLoginSuccess()
        }
    }

    Scaffold(
        topBar = {
            // 静态表单页无滚动联动，走实心顶栏形态
            ImmersiveTopBar(
                title = { scroll ->
                    TopBarTitleText(
                        text = "微博账号登录",
                        scroll = scroll
                    )
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                contentColor = MaterialTheme.colorScheme.primary,
                edgePadding = 16.dp
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("网页授权(推荐)", style = if (selectedTab == 0) MaterialTheme.typography.titleSmallEmphasized else MaterialTheme.typography.titleSmall) },
                    icon = { Icon(Icons.Default.Web, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("短信登录", style = if (selectedTab == 1) MaterialTheme.typography.titleSmallEmphasized else MaterialTheme.typography.titleSmall) },
                    icon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("密码登录", style = if (selectedTab == 2) MaterialTheme.typography.titleSmallEmphasized else MaterialTheme.typography.titleSmall) },
                    icon = { Icon(Icons.Default.AccountCircle, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("Cookie导入", style = if (selectedTab == 3) MaterialTheme.typography.titleSmallEmphasized else MaterialTheme.typography.titleSmall) },
                    icon = { Icon(Icons.Default.VpnKey, contentDescription = null) }
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> {
                        // 网页授权登录 (支持极验/易盾等各类人机验证并自动捕获 SUB Cookie)
                        var webViewRef by remember { mutableStateOf<WebView?>(null) }

                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                AndroidView(
                                    factory = { context ->
                                        WebView(context).apply {
                                            webViewRef = this
                                            settings.javaScriptEnabled = true
                                            settings.domStorageEnabled = true
                                            settings.databaseEnabled = true
                                            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

                                            val cookieManager = CookieManager.getInstance()
                                            cookieManager.setAcceptCookie(true)
                                            cookieManager.setAcceptThirdPartyCookies(this, true)

                                            fun checkAndExtractLogin(currentUrl: String?) {
                                                android.util.Log.d("WeiboLogin", "Current url: $currentUrl")
                                                // 如果还在登录页面输入账号密码或人机验证，不要过早触发自动验证
                                                val isAtSigninPage = currentUrl != null && (
                                                    currentUrl.contains("passport.weibo.com/sso/signin") ||
                                                    currentUrl.contains("login.sina.com.cn")
                                                )
                                                if (isAtSigninPage) return

                                                val cookiePool = buildString {
                                                    append(cookieManager.getCookie("https://m.weibo.cn").orEmpty()).append("; ")
                                                    append(cookieManager.getCookie("https://weibo.cn").orEmpty()).append("; ")
                                                    append(cookieManager.getCookie("https://weibo.com").orEmpty()).append("; ")
                                                    append(cookieManager.getCookie("https://passport.weibo.com").orEmpty()).append("; ")
                                                    append(cookieManager.getCookie("https://sina.cn").orEmpty()).append("; ")
                                                    currentUrl?.let { append(cookieManager.getCookie(it).orEmpty()) }
                                                }

                                                val hasSubCookie = cookiePool.contains("SUB=") && !cookiePool.contains("SUB=\"\"")
                                                val isRedirectedToTarget = currentUrl != null && (
                                                    currentUrl.contains("m.weibo.cn") ||
                                                    currentUrl.contains("weibo.com") ||
                                                    currentUrl.contains("sina.cn") ||
                                                    currentUrl.contains("crossdomain")
                                                )

                                                if (hasSubCookie && isRedirectedToTarget && uiState !is LoginUiState.Success && uiState !is LoginUiState.Loading) {
                                                    viewModel.loginWithCookie(cookiePool)
                                                }
                                            }

                                            webViewClient = object : WebViewClient() {
                                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                                    super.doUpdateVisitedHistory(view, url, isReload)
                                                    checkAndExtractLogin(url)
                                                }

                                                override fun onPageFinished(view: WebView?, url: String?) {
                                                    super.onPageFinished(view, url)
                                                    checkAndExtractLogin(url)
                                                }
                                            }
                                            loadUrl("https://passport.weibo.com/sso/signin?entry=wapsso&source=wapssowb&url=https%3A%2F%2Fm.weibo.cn%2F")
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )

                                if (uiState is LoginUiState.Loading) {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .align(Alignment.TopCenter),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            // 底部操作栏：支持手动校验登录完成进入、刷新页面
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                tonalElevation = 3.dp,
                                color = MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                ) {
                                    if (uiState is LoginUiState.Error) {
                                        Text(
                                            text = (uiState as LoginUiState.Error).message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(bottom = 6.dp)
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = { webViewRef?.reload() },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("刷新")
                                        }

                                        Button(
                                            onClick = {
                                                val cookieManager = CookieManager.getInstance()
                                                val url = webViewRef?.url
                                                val cookiePool = buildString {
                                                    append(cookieManager.getCookie("https://m.weibo.cn").orEmpty()).append("; ")
                                                    append(cookieManager.getCookie("https://weibo.cn").orEmpty()).append("; ")
                                                    append(cookieManager.getCookie("https://weibo.com").orEmpty()).append("; ")
                                                    append(cookieManager.getCookie("https://passport.weibo.com").orEmpty()).append("; ")
                                                    append(cookieManager.getCookie("https://sina.cn").orEmpty()).append("; ")
                                                    url?.let { append(cookieManager.getCookie(it).orEmpty()) }
                                                }
                                                viewModel.loginWithCookie(cookiePool)
                                            },
                                            enabled = uiState !is LoginUiState.Loading,
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.weight(2.5f)
                                        ) {
                                            if (uiState is LoginUiState.Loading) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    strokeWidth = 2.dp
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("验证中...")
                                            } else {
                                                Text("已完成登录，点击进入")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        // 手机短信验证码登录
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "手机快捷登录",
                                style = MaterialTheme.typography.titleMediumEmphasized
                            )
                            Text(
                                text = "未注册手机号验证后将自动创建或关联微博账号",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            // 手机号输入框
                            OutlinedTextField(
                                value = phoneInput,
                                onValueChange = { if (it.length <= 11) phoneInput = it },
                                label = { Text("手机号码") },
                                placeholder = { Text("请输入11位中国大陆手机号") },
                                leadingIcon = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(start = 12.dp, end = 4.dp)
                                    ) {
                                        Text("+86", style = MaterialTheme.typography.titleSmallEmphasized)
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            // 验证码输入框与获取按钮
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = codeInput,
                                    onValueChange = { if (it.length <= 6) codeInput = it },
                                    label = { Text("验证码") },
                                    placeholder = { Text("6位短信验证码") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )

                                OutlinedButton(
                                    onClick = { viewModel.sendSmsCode(phoneInput) },
                                    enabled = phoneInput.length == 11 && countdown == 0 && uiState !is LoginUiState.Loading,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.height(54.dp)
                                ) {
                                    Text(
                                        text = if (countdown > 0) "${countdown}s后重发" else "获取验证码",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }

                            // 错误或发送成功提示
                            when (uiState) {
                                is LoginUiState.Error -> {
                                    Text(
                                        text = (uiState as LoginUiState.Error).message,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                is LoginUiState.CodeSent -> {
                                    Text(
                                        text = "✓ ${(uiState as LoginUiState.CodeSent).message}",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodySmallEmphasized
                                    )
                                }
                                else -> {}
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = { viewModel.loginWithSms(phoneInput, codeInput) },
                                enabled = phoneInput.length == 11 && codeInput.isNotBlank() && uiState !is LoginUiState.Loading,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                            ) {
                                if (uiState is LoginUiState.Loading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("登 录", style = MaterialTheme.typography.titleMediumEmphasized)
                                }
                            }
                        }
                    }
                    2 -> {
                        // 账号密码登录
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "账号密码登录",
                                style = MaterialTheme.typography.titleMediumEmphasized
                            )
                            Text(
                                text = "支持使用微博绑定手机号、邮箱或个性化账号名登录。若触发了安全防护验证，请切换至「网页授权(推荐)」。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            OutlinedTextField(
                                value = usernameInput,
                                onValueChange = { usernameInput = it },
                                label = { Text("账号 / 手机号 / 邮箱") },
                                placeholder = { Text("请输入微博账号") },
                                leadingIcon = {
                                    Icon(Icons.Default.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text("密码") },
                                placeholder = { Text("请输入微博密码") },
                                leadingIcon = {
                                    Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (uiState is LoginUiState.Error) {
                                Text(
                                    text = (uiState as LoginUiState.Error).message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = { viewModel.loginWithPassword(usernameInput, passwordInput) },
                                enabled = usernameInput.isNotBlank() && passwordInput.isNotBlank() && uiState !is LoginUiState.Loading,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                            ) {
                                if (uiState is LoginUiState.Loading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("登 录", style = MaterialTheme.typography.titleMediumEmphasized)
                                }
                            }
                        }
                    }
                    3 -> {
                        // 手动输入 Cookie / SUB 凭据
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            MatterCard(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                shape = MatterShapeTokens.listItem
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "💡 凭据获取指南",
                                        style = MaterialTheme.typography.titleMediumEmphasized
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "1. 在电脑或手机浏览器打开 m.weibo.cn 登录账号。\n2. 打开审查元素或抓包工具，复制 Cookie 中的 SUB 或完整 Cookie 粘贴至下方。\n3. 点击立即验证登录完成鉴权与会话绑定。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = cookieInput,
                                onValueChange = { cookieInput = it },
                                label = { Text("Cookie / SUB 字符串") },
                                placeholder = { Text("例如: SUB=_2A25...; SUBP=0033...") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 4,
                                maxLines = 8,
                                leadingIcon = {
                                    Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            )

                            if (uiState is LoginUiState.Error) {
                                Text(
                                    text = (uiState as LoginUiState.Error).message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Button(
                                onClick = { viewModel.loginWithCookie(cookieInput) },
                                enabled = uiState !is LoginUiState.Loading,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                            ) {
                                if (uiState is LoginUiState.Loading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("立即验证并登录", style = MaterialTheme.typography.titleMediumEmphasized)
                                }
                            }
                        }
                    }
                }
            }

            // 底部快捷通道：访客体验模式
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = { viewModel.loginAsGuest() }) {
                    Text("跳过登录 · 访客体验模式", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
