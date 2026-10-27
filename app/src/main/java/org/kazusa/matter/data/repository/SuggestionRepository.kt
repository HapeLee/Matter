package org.kazusa.matter.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.model.ReplyPickerKind
import org.kazusa.matter.data.model.ReplySuggestion

/**
 * 输入框里 `@用户` / `#话题` 的建议来源。
 *
 * 评论、发微博与转发三个输入框共用同一份口径：未输入时给关注列表（@）或热搜（#），
 * 输入后走综合搜索的用户 / 话题频道。它不属于任何一条写入语义，因此从
 * [ReplyRepository] 独立出来，避免「发微博的 @ 选择器依赖评论仓库」这种错位。
 */
internal class SuggestionRepository(
    val sessions: Flow<ReplySession>,
    private val search: suspend (ReplySession, ReplyPickerKind, String) -> ApiResult<List<ReplySuggestion>> =
        { _, _, _ -> ApiResult.Success(emptyList()) }
) {
    constructor(api: WeiboApiService, auth: AuthPreferences) : this(
        sessions = auth.sessionFlow.map { ReplySession(it.uid, it.cookieHeader, it.isLoggedIn) },
        search = { session, kind, query -> searchComposerSuggestions(api, session, kind, query) }
    )

    suspend fun currentSession(): ReplySession = sessions.first()

    suspend fun suggestions(kind: ReplyPickerKind, query: String): ApiResult<List<ReplySuggestion>> {
        val session = currentSession()
        if (!session.loggedIn) return ApiResult.Error(message = "请先登录")
        return search(session, kind, query.trim().trim('@', '#'))
    }
}

private suspend fun searchComposerSuggestions(
    api: WeiboApiService,
    session: ReplySession,
    kind: ReplyPickerKind,
    query: String
): ApiResult<List<ReplySuggestion>> {
    if (query.isBlank()) {
        return if (kind == ReplyPickerKind.MENTION) {
            when (val result = api.getFollowingUsers(session.uid, cookie = session.cookie)) {
                is ApiResult.Success -> ApiResult.Success(result.data.items.mapNotNull { it.user }.map {
                    ReplySuggestion(
                        it.id?.toString() ?: it.screenName,
                        it.screenName,
                        it.description.orEmpty(),
                        it.avatarLarge ?: it.profileImageUrl
                    )
                }.distinctBy { it.id })
                is ApiResult.Error -> result
                ApiResult.Loading -> ApiResult.Loading
            }
        } else {
            when (val result = api.getHotSearch(cookie = session.cookie)) {
                is ApiResult.Success -> ApiResult.Success(result.data.sections.flatMap { it.items }
                    .filterNot { it.isMore || it.isPromoted }.map {
                        val name = cleanSuggestionLabel(it.title).trim('#')
                        ReplySuggestion(name, name, it.heat)
                    }.filter { it.name.isNotBlank() }.distinctBy { it.name })
                is ApiResult.Error -> result
                ApiResult.Loading -> ApiResult.Loading
            }
        }
    }
    return when (val result = api.searchWeiboPage(
        query,
        searchType = if (kind == ReplyPickerKind.MENTION) 3 else 38,
        cookie = session.cookie
    )) {
        is ApiResult.Success -> {
            val suggestions = result.data.items.flatMap { item ->
                if (kind == ReplyPickerKind.MENTION) {
                    listOfNotNull(item.card?.user?.let { user ->
                        ReplySuggestion(
                            user.id?.toString() ?: user.screenName,
                            user.screenName,
                            user.description.orEmpty(),
                            user.avatarLarge ?: user.profileImageUrl
                        )
                    })
                } else {
                    val names = item.chipGroup?.chips?.map { it.text }
                        ?: listOfNotNull(item.card?.titleSub ?: item.card?.title)
                    names.map { label ->
                        val name = cleanSuggestionLabel(label).trim('#')
                        ReplySuggestion(name, name, cleanSuggestionLabel(item.card?.desc1.orEmpty()))
                    }
                }
            }.filter { it.name.isNotBlank() }.distinctBy { it.id }
            ApiResult.Success(suggestions)
        }

        is ApiResult.Error -> result
        ApiResult.Loading -> ApiResult.Loading
    }
}

private fun cleanSuggestionLabel(value: String): String = value
    .replace(Regex("<[^>]*>"), "")
    .replace("&amp;", "&")
    .replace("&nbsp;", " ")
    .trim()
