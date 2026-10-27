package org.kazusa.matter.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.StatusPicture
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.model.ReplyImageHandle
import org.kazusa.matter.data.model.ReplyImageReader

/** 发微博时固定下来的账号凭据；排队提交期间不重新读取成另一个账号。 */
internal data class PublishSession(val uid: String, internal val cookie: String, val loggedIn: Boolean)

/**
 * 发微博的数据入口：固定账号、读取图片、上传取 pic id、提交正文。
 *
 * 与 [ReplyRepository] 分开，是因为「发微博」和「评论」是两条独立的写入语义：
 * 前者带多图与可见性，后者绑定目标微博与楼中楼。两者共用同一个 [WeiboApiService]
 * 写闸门与图片上传端点。
 *
 * 成功发布后通过 [published] 通知关心「刚发了一条」的页面（时间线据此刷新），
 * 避免 ViewModel 之间互相持有。
 */
internal class PublishRepository(
    val sessions: Flow<PublishSession>,
    private val imageReader: ReplyImageReader? = null,
    private val uploadImage: suspend (PublishSession, ByteArray) -> ApiResult<String> = { _, _ ->
        ApiResult.Error(message = "图片上传不可用")
    },
    private val publishStatus: suspend (PublishSession, String, List<StatusPicture>) -> ApiResult<Unit> = { _, _, _ ->
        ApiResult.Error(message = "发布不可用")
    }
) {
    constructor(api: WeiboApiService, auth: AuthPreferences, reader: ReplyImageReader) : this(
        sessions = auth.sessionFlow.map { PublishSession(it.uid, it.cookieHeader, it.isLoggedIn) },
        imageReader = reader,
        uploadImage = { session, bytes -> api.uploadReplyImage(session.cookie, bytes) },
        publishStatus = { session, text, pictures -> api.publishStatus(text, pictures, session.cookie) }
    )

    private val _publishedCount = MutableStateFlow(0L)

    /**
     * 发布成功计数（只增）。
     *
     * 用计数而不是一次性事件：编辑器盖在时间线之上时，时间线那一层已经被移除组合，
     * SharedFlow 的即时事件会丢；订阅方按「比上次处理过的更大」决定刷新即可，
     * 返回时补一次也不会重复（见 MainTabsScreen）。
     */
    val publishedCount: StateFlow<Long> = _publishedCount.asStateFlow()

    suspend fun currentSession(): PublishSession = sessions.first()

    /** 读取并上传一张待发图片，返回服务端 pid（连同真实 MIME，见 [StatusPicture]）。 */
    suspend fun upload(session: PublishSession, handle: ReplyImageHandle): ApiResult<StatusPicture> {
        if (!session.loggedIn || session.cookie.isBlank()) {
            return ApiResult.Error(code = 401, message = "请先登录后上传图片")
        }
        if (currentSession() != session) return ApiResult.Error(message = "账号已切换，请重新选择图片")
        val image = requireNotNull(imageReader) { "图片读取不可用" }.read(handle)
        if (currentSession() != session) return ApiResult.Error(message = "账号已切换，请重新选择图片")
        return when (val result = uploadImage(session, image.bytes)) {
            is ApiResult.Success -> ApiResult.Success(StatusPicture(result.data, image.mimeType))
            is ApiResult.Error -> result
            ApiResult.Loading -> ApiResult.Error(message = "图片上传未完成，请重试")
        }
    }

    /** 提交一条微博；[pictures] 是已经上传成功的图片，顺序即展示顺序。 */
    suspend fun publish(
        session: PublishSession,
        text: String,
        pictures: List<StatusPicture>
    ): ApiResult<Unit> {
        if (!session.loggedIn || session.cookie.isBlank()) {
            return ApiResult.Error(code = 401, message = "请先登录后发布")
        }
        if (currentSession() != session) return ApiResult.Error(message = "账号已切换，请重新发布")
        val result = publishStatus(session, text, pictures)
        if (result is ApiResult.Success) _publishedCount.update { it + 1 }
        return result
    }
}
