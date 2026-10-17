package org.kazusa.matter.core.network

import org.kazusa.matter.data.model.CommentsDataDto
import org.kazusa.matter.data.model.WeiboCommentDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** hotFlowChild returns a flat page; some responses wrap that page inside data. */
internal fun decodeCommentReplies(rawText: String): ApiResult<CommentsDataDto> {
    val json = KtorClientFactory.jsonConfig
    val envelope = json.parseToJsonElement(rawText) as? JsonObject
        ?: return ApiResult.Error(message = "回复数据格式异常")
    val page = (envelope["data"] as? JsonObject) ?: envelope
    val envelopeOk = (envelope["ok"] as? JsonPrimitive)?.intOrNull
    val pageOk = (page["ok"] as? JsonPrimitive)?.intOrNull
    if (envelopeOk == 0 || pageOk == 0) {
        val message = (envelope["msg"] as? JsonPrimitive)?.content
            ?: (page["msg"] as? JsonPrimitive)?.content
            ?: "获取回复失败，请稍后重试"
        return ApiResult.Error(message = message)
    }
    val replies = page["data"] as? JsonArray
        ?: return ApiResult.Error(message = "未获取到回复数据，请稍后重试")
    return ApiResult.Success(
        CommentsDataDto(
            data = json.decodeFromJsonElement<List<WeiboCommentDto>>(replies),
            totalNumber = (page["total_number"] as? JsonPrimitive)?.intOrNull,
            maxId = (page["max_id"] as? JsonPrimitive)?.longOrNull,
            maxIdType = (page["max_id_type"] as? JsonPrimitive)?.intOrNull
        )
    )
}
