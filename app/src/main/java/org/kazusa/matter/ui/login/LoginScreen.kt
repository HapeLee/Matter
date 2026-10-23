package org.kazusa.matter.ui.login

import androidx.compose.foundation.shape.RoundedCornerShape


import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.kazusa.matter.core.network.WeiboRequestHeaders
import org.kazusa.matter.core.session.WebSessionCookieStore
import org.kazusa.matter.data.repository.AuthCookieSanitizer
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.MatterCard
import org.kazusa.matter.ui.components.TopBarTitleText
import org.kazusa.matter.ui.theme.MatterShapeTokens
import org.kazusa.matter.ui.theme.TextSecondary

import androidx.compose.material3.ScrollableTabRow

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    webCookieStore: WebSessionCookieStore,
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
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
                    text = { Text("Cookie导入", style = if (selectedTab == 1) MaterialTheme.typography.titleSmallEmphasized else MaterialTheme.typography.titleSmall) },
                    icon = { Icon(Icons.Default.VpnKey, contentDescription = null) }
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> {
                        // 网页授权登录 (支持极验/易盾等各类人机验证并自动捕获 SUB Cookie)
                        var webViewRef by remember { mutableStateOf<WebView?>(null) }
                        // 创建 WebView 前先清掉残留 Cookie：否则页面一加载就会
                        // 抓回旧账号的 SUB 自动登录，用户永远换不了号
                        var cookieStoreCleared by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) {
                            webCookieStore.clearAll()
                            cookieStoreCleared = true
                        }
                        // 离开登录页释放 WebView：仅把它从组合树摘掉不会销毁原生实例
                        DisposableEffect(Unit) {
                            onDispose {
                                webViewRef?.apply {
                                    stopLoading()
                                    loadUrl("about:blank")
                                    destroy()
                                }
                                webViewRef = null
                            }
                        }

                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                if (cookieStoreCleared) {
                                    AndroidView(
                                    factory = { context ->
                                        WebView(context).apply {
                                            webViewRef = this
                                            settings.javaScriptEnabled = true
                                            settings.domStorageEnabled = true
                                            settings.databaseEnabled = true
                                            settings.safeBrowsingEnabled = true
                                            // 与后续 API / CDN 请求保持一致：登录时拿到的会话
                                            // 与之后请求的 UA 不同，会被判成换了客户端
                                            settings.userAgentString = WeiboRequestHeaders.USER_AGENT

                                            val cookieManager = CookieManager.getInstance()
                                            cookieManager.setAcceptCookie(true)
                                            cookieManager.setAcceptThirdPartyCookies(this, true)

                                            fun checkAndExtractLogin(currentUrl: String?) {
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

                                                // 多域名拼接后按「首个非空值生效」去重，
                                                // 防止两个账号的 SUB 同头发给服务端
                                                val sanitizedPool = AuthCookieSanitizer.canonicalize(cookiePool)

                                                val hasSubCookie = sanitizedPool.contains("SUB=")
                                                val isRedirectedToTarget = currentUrl != null && (
                                                    currentUrl.contains("m.weibo.cn") ||
                                                        currentUrl.contains("weibo.com") ||
                                                        currentUrl.contains("sina.cn") ||
                                                        currentUrl.contains("crossdomain")
                                                )

                                                if (hasSubCookie && isRedirectedToTarget && uiState !is LoginUiState.Success && uiState !is LoginUiState.Loading) {
                                                    viewModel.loginWithCookie(sanitizedPool)
                                                }
                                            }

                                            webViewClient = object : WebViewClient() {
                                                // 登录流程只会经过微博/新浪域名；白名单外
                                                // 的跳转一律不在内嵌 WebView 中加载
                                                override fun shouldOverrideUrlLoading(
                                                    view: WebView?,
                                                    request: WebResourceRequest?
                                                ): Boolean {
                                                    val host = request?.url?.host?.lowercase()
                                                        ?: return true
                                                    val allowed = listOf(
                                                        "weibo.com", "weibo.cn",
                                                        "sina.com", "sina.com.cn", "sina.cn"
                                                    )
                                                    return allowed.none { host == it || host.endsWith(".$it") }
                                                }

                                                // 证书校验失败直接终止加载，绝不放行
                                                override fun onReceivedSslError(
                                                    view: WebView?,
                                                    handler: SslErrorHandler?,
                                                    error: SslError?
                                                ) {
                                                    handler?.cancel()
                                                }

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
                                } else {
                                    CircularProgressIndicator(
                                        modifier = Modifier.align(Alignment.Center),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

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
                                                viewModel.loginWithCookie(AuthCookieSanitizer.canonicalize(cookiePool))
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
