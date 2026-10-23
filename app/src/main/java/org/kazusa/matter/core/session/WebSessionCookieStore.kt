package org.kazusa.matter.core.session

/**
 * 平台 WebView Cookie 存储的清理边界。
 *
 * 登录 WebView 会把 SUB / SUBP 等全权凭据留在系统 Cookie 库里，与本地会话同源同权；
 * 登出 / 换号时必须一并清除，否则「已退出」只是清了应用自己的 DataStore。
 * 会话数据层是跨平台候选，不直接依赖 android.webkit，实现由平台侧注入。
 */
interface WebSessionCookieStore {
    /** 清空 WebView 持久的全部 Cookie。 */
    suspend fun clearAll()
}
