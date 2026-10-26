package org.kazusa.matter.core.network

import org.kazusa.matter.data.model.RepostsDataDto
import org.kazusa.matter.data.model.UserTimelineResult
import org.kazusa.matter.data.model.WeiboStatusDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

/**
 * 网页域 `weibo.com/ajax/statuses/…` 的响应解析。
 *
 * 与 m 站的差别只有一层信封：m 站把内容包在 `data` 对象里（`{ok, data:{...}}`），
 * 网页端直接把内容放在顶层（`{ok, ...微博字段}` / `{ok, data:[...]}`），
 * 因此这里按扁平结构解析，DTO 本身沿用 m 站那一套。
 */

/** 微博详情：`{ok, id, text, user, ...}`，顶层就是一条微博。 */
internal fun decodeWebStatusDetail(rawText: String): ApiResult<WeiboStatusDto> {
    val json = KtorClientFactory.jsonConfig
    val root = json.parseToJsonElement(rawText) as? JsonObject
        ?: return ApiResult.Error(message = "微博详情数据格式异常")
    readWebError(root)?.let { return it }
    val status = runCatching {
        json.decodeFromJsonElement<WeiboStatusDto>(root)
    }.getOrNull() ?: return ApiResult.Error(message = "未获取到微博详情")
    return ApiResult.Success(status)
}

/**
 * 转发列表：`{ok, data:[微博...], total_number, max_page, next_cursor}`。
 *
 * 未登录时网页端回 `ok=1` + 空 `data` + `empty_tip="前方拥堵，请登录后使用"`，
 * 这里按空列表处理（与 m 站「这里还没有内容」一致，不显示成加载失败）。
 */
internal fun decodeWebRepostPage(rawText: String): ApiResult<RepostsDataDto> {
    val json = KtorClientFactory.jsonConfig
    val root = json.parseToJsonElement(rawText) as? JsonObject
        ?: return ApiResult.Error(message = "转发列表数据格式异常")
    val error = readWebError(root)
    if (error != null) {
        return if (isWeiboNoContentMessage(error.message)) {
            ApiResult.Success(RepostsDataDto())
        } else {
            error
        }
    }
    val data = root["data"] as? JsonArray
        ?: return ApiResult.Error(message = "未获取到转发列表")
    val posts = runCatching {
        json.decodeFromJsonElement<List<WeiboStatusDto>>(data)
    }.getOrElse { return ApiResult.Error(message = "转发列表数据格式异常") }
    return ApiResult.Success(
        RepostsDataDto(
            data = posts,
            totalNumber = (root["total_number"] as? JsonPrimitive)?.intOrNull,
            // 网页端字段是 max_page（总页数），沿用 DTO 的 max 语义
            max = (root["max_page"] as? JsonPrimitive)?.intOrNull
        )
    )
}

/**
 * 用户主页微博列表：`{ok, data:{list:[微博...], since_id, total}}`。
 *
 * 与 m 站 `container/getIndex` 的 cards 结构完全不同，网页端直接给 `data.list`；
 * 分页用 `page`（从 1 开始），`since_id` 是增量游标（首次为 0）。
 */
internal fun decodeWebUserStatusPage(rawText: String): ApiResult<UserTimelineResult> {
    val json = KtorClientFactory.jsonConfig
    val root = json.parseToJsonElement(rawText) as? JsonObject
        ?: return ApiResult.Error(message = "博主动态数据格式异常")
    readWebError(root)?.let { return it }
    val data = root["data"] as? JsonObject
        ?: return ApiResult.Error(message = "未获取到博主动态")
    val posts = (data["list"] as? JsonArray)?.let { array ->
        runCatching { json.decodeFromJsonElement<List<WeiboStatusDto>>(array) }.getOrDefault(emptyList())
    }.orEmpty()
    return ApiResult.Success(
        UserTimelineResult(
            user = posts.firstOrNull()?.user,
            statuses = posts,
            total = (data["total"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            hasMore = posts.isNotEmpty()
        )
    )
}

/** 网页端业务失败信封；`ok` 缺省或等于 1 时返回 null。 */
private fun readWebError(root: JsonObject): ApiResult.Error? {
    val ok = (root["ok"] as? JsonPrimitive)?.intOrNull ?: return null
    if (ok == 1) return null
    val message = (root["msg"] as? JsonPrimitive)?.content
        ?: (root["message"] as? JsonPrimitive)?.content
        ?: (root["error"] as? JsonPrimitive)?.content
    return ApiResult.Error(
        code = ok,
        message = message?.takeIf { it.isNotBlank() } ?: "获取微博数据失败(ok=$ok)"
    )
}
