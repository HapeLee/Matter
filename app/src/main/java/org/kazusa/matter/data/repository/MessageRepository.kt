package org.kazusa.matter.data.repository

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.datastore.UserSession
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.MessageApi
import org.kazusa.matter.core.session.SessionExpiredBus
import org.kazusa.matter.data.model.ChatMessagePage
import org.kazusa.matter.data.model.MessageConversationPage
import org.kazusa.matter.data.model.MessageNoticePage
import org.kazusa.matter.data.model.MessageTab

/**
 * 消息域（私信 + 互动提醒）的数据入口。
 *
 * 微博消息页的接口与时间线同域（m.weibo.cn），因此沿用同一份登录 Cookie；
 * 每次请求都重新读一次会话，换账号后旧账号的 Cookie 不会继续用于新会话。
 */
interface MessageRepository {
    /** 私信会话列表；[page] 从 1 开始。 */
    suspend fun conversations(page: Int): ApiResult<MessageConversationPage>

    /** 互动提醒（[MessageTab.MENTION] / [MessageTab.COMMENT] / [MessageTab.LIKE]）。 */
    suspend fun notices(tab: MessageTab, page: Int): ApiResult<MessageNoticePage>

    /** 与 [peerUid] 的聊天记录；[maxId] 传上一页返回的 `nextMaxId` 往前翻。 */
    suspend fun history(peerUid: String, maxId: Long? = null): ApiResult<ChatMessagePage>
}

class WeiboMessageRepository(
    private val authPreferences: AuthPreferences,
    client: HttpClient,
    sessionExpiredBus: SessionExpiredBus? = null
) : MessageRepository {

    private val api = MessageApi(client, sessionExpiredBus)

    override suspend fun conversations(page: Int): ApiResult<MessageConversationPage> {
        val session = currentSession() ?: return notLoggedIn()
        return api.conversations(session.cookieHeader, page)
    }

    override suspend fun notices(tab: MessageTab, page: Int): ApiResult<MessageNoticePage> {
        val session = currentSession() ?: return notLoggedIn()
        return api.notices(session.cookieHeader, tab, page)
    }

    override suspend fun history(peerUid: String, maxId: Long?): ApiResult<ChatMessagePage> {
        val session = currentSession() ?: return notLoggedIn()
        return api.chatHistory(
            cookie = session.cookieHeader,
            currentUid = session.uid,
            peerUid = peerUid,
            maxId = maxId
        )
    }

    private suspend fun currentSession(): UserSession? {
        val session = try {
            authPreferences.sessionFlow.firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return session?.takeIf { it.isLoggedIn && it.uid.isNotBlank() && it.uid != GUEST_UID }
    }

    private fun notLoggedIn(): ApiResult.Error =
        ApiResult.Error(code = 401, message = "登录后才能查看消息")

    private companion object {
        const val GUEST_UID = "guest"
    }
}
