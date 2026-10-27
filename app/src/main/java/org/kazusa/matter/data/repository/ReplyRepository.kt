package org.kazusa.matter.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.model.*

/** 凭据只在数据层使用；页面用同一个实例判断会话是否仍然有效。 */
internal data class ReplySession(val uid: String, internal val cookie: String, val loggedIn: Boolean)

internal data class PublishedReply(
    val id: Long,
    val text: String,
    val createdAt: String,
    val authorId: Long?,
    val authorName: String,
    val authorAvatar: String?
)

/** 固定本次写入的账号与回复目标，不在排队提交时重新读取成另一个账号。 */
internal class ReplyRepository(
    val sessions: Flow<ReplySession>,
    private val imageReader: ReplyImageReader? = null,
    private val uploadImage: suspend (ReplySession, ByteArray) -> ApiResult<String> = { _, _ -> ApiResult.Error(message = "图片上传不可用") },
    /** [commentId] 为 null 删微博，非 null 删评论。 */
    private val destroy: suspend (ReplySession, String, Long?) -> ApiResult<Unit> = { _, _, _ -> ApiResult.Error(message = "删除不可用") },
    private val publish: suspend (ReplySession, String, Long?, String, String?) -> ApiResult<PublishedReply?>
) {
    constructor(api: WeiboApiService, auth: AuthPreferences, reader: ReplyImageReader) : this(
        sessions = auth.sessionFlow.map { ReplySession(it.uid, it.cookieHeader, it.isLoggedIn) },
        imageReader = reader,
        uploadImage = { session, bytes -> api.uploadReplyImage(session.cookie, bytes) },
        destroy = { session, postId, commentId ->
            if (commentId == null) api.destroyPost(postId, session.cookie)
            else api.destroyComment(postId, commentId, session.cookie)
        },
        publish = { session, postId, commentId, text, pictureId ->
            when (val result = api.createComment(postId, text, session.cookie, commentId, pictureId)) {
                is ApiResult.Success -> ApiResult.Success(result.data?.let {
                    PublishedReply(requireNotNull(it.id), it.text, it.createdAt, it.user?.id,
                        it.user?.screenName.orEmpty(), it.user?.avatarLarge ?: it.user?.profileImageUrl)
                })
                is ApiResult.Error -> result
                ApiResult.Loading -> ApiResult.Loading
            }
        }
    )

    suspend fun currentSession(): ReplySession = sessions.first()

    suspend fun submit(session: ReplySession, postId: String, commentId: Long?, text: String, pictureId: String? = null): ApiResult<PublishedReply?> {
        if (!session.loggedIn || session.cookie.isBlank()) {
            return ApiResult.Error(code = 401, message = "请先登录后回复")
        }
        if (currentSession() != session) return ApiResult.Error(message = "账号已切换，请重新回复")
        return publish(session, postId, commentId, text, pictureId)
    }

    /** 删除自己发表的微博。 */
    suspend fun deletePost(session: ReplySession, postId: String): ApiResult<Unit> {
        if (!session.loggedIn || session.cookie.isBlank()) {
            return ApiResult.Error(code = 401, message = "请先登录后再操作")
        }
        if (currentSession() != session) return ApiResult.Error(message = "账号已切换，请重新操作")
        return destroy(session, postId, null)
    }

    /** 删除自己发表的评论；楼中楼回复同样是评论，按评论 id 删除。 */
    suspend fun deleteComment(session: ReplySession, postId: String, commentId: Long): ApiResult<Unit> {
        if (!session.loggedIn || session.cookie.isBlank()) {
            return ApiResult.Error(code = 401, message = "请先登录后再操作")
        }
        if (currentSession() != session) return ApiResult.Error(message = "账号已切换，请重新操作")
        return destroy(session, postId, commentId)
    }

    suspend fun upload(session: ReplySession, handle: ReplyImageHandle): ApiResult<String> {
        if (!session.loggedIn || session.cookie.isBlank() || currentSession() != session) {
            return ApiResult.Error(code = 401, message = "请先登录后上传图片")
        }
        val image = requireNotNull(imageReader) { "图片读取不可用" }.read(handle)
        if (currentSession() != session) return ApiResult.Error(message = "账号已切换，请重新选择图片")
        return uploadImage(session, image.bytes)
    }
}
