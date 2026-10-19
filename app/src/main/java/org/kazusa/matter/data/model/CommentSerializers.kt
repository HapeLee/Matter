package org.kazusa.matter.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.decodeFromJsonElement

/** An absent preview can be null/false; a bad optional reply must not hide its parent. */
object CommentListSerializer : KSerializer<List<WeiboCommentDto>> {
    override val descriptor: SerialDescriptor
        get() = ListSerializer(WeiboCommentDto.serializer()).descriptor

    override fun serialize(encoder: Encoder, value: List<WeiboCommentDto>) {
        encoder.encodeSerializableValue(ListSerializer(WeiboCommentDto.serializer()), value)
    }

    override fun deserialize(decoder: Decoder): List<WeiboCommentDto> {
        if (decoder !is JsonDecoder) {
            return decoder.decodeSerializableValue(ListSerializer(WeiboCommentDto.serializer()))
        }
        val element = decoder.decodeJsonElement()
        val items = element as? JsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            if (item !is JsonObject) return@mapNotNull null
            try {
                decoder.json.decodeFromJsonElement<WeiboCommentDto>(item)
            } catch (_: SerializationException) {
                null
            }
        }
    }
}

object ReplyCommentSerializer : KSerializer<WeiboCommentDto?> {
    override val descriptor: SerialDescriptor
        get() = WeiboCommentDto.serializer().nullable.descriptor

    override fun serialize(encoder: Encoder, value: WeiboCommentDto?) {
        encoder.encodeSerializableValue(WeiboCommentDto.serializer().nullable, value)
    }

    override fun deserialize(decoder: Decoder): WeiboCommentDto? {
        if (decoder !is JsonDecoder) {
            return decoder.decodeSerializableValue(WeiboCommentDto.serializer().nullable)
        }
        val element = decoder.decodeJsonElement() as? JsonObject ?: return null
        return try {
            decoder.json.decodeFromJsonElement<WeiboCommentDto>(element)
        } catch (_: SerializationException) {
            null
        }
    }
}

object CommentOptionalTextSerializer : JsonTransformingSerializer<String?>(String.serializer().nullable) {
    override fun transformDeserialize(element: kotlinx.serialization.json.JsonElement) =
        if (element is JsonPrimitive && element.isString) element else JsonNull
}

/**
 * 评论配图字段的容错解析：
 * 微博不同端会把 `pic_ids` / `pic_infos` / `pic_urls` 下发成空对象、空数组或 null，
 * 直接按目标类型解析失败会让整条评论被丢弃，这里统一降级成空集合。
 */
object CommentPicIdsSerializer : JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
    override fun transformDeserialize(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
        val array = element as? JsonArray ?: return JsonArray(emptyList())
        return JsonArray(array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString } })
    }
}

object CommentPicInfosSerializer : JsonTransformingSerializer<Map<String, WeiboPicInfoDto>>(
    MapSerializer(String.serializer(), WeiboPicInfoDto.serializer())
) {
    override fun transformDeserialize(element: kotlinx.serialization.json.JsonElement) =
        if (element is JsonObject) element else JsonObject(emptyMap())
}

object CommentPicUrlsSerializer : JsonTransformingSerializer<List<WeiboPicUrlDto>>(
    ListSerializer(WeiboPicUrlDto.serializer())
) {
    override fun transformDeserialize(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
        val array = element as? JsonArray ?: return JsonArray(emptyList())
        return JsonArray(array.filterIsInstance<JsonObject>())
    }
}

/** `pic` 在部分端是对象、部分端是 false/null，不能让一条坏数据拖垮整条评论。 */
object CommentPicSerializer : KSerializer<WeiboCommentPicDto?> {
    override val descriptor: SerialDescriptor
        get() = WeiboCommentPicDto.serializer().nullable.descriptor

    override fun serialize(encoder: Encoder, value: WeiboCommentPicDto?) {
        encoder.encodeSerializableValue(WeiboCommentPicDto.serializer().nullable, value)
    }

    override fun deserialize(decoder: Decoder): WeiboCommentPicDto? {
        if (decoder !is JsonDecoder) {
            return decoder.decodeSerializableValue(WeiboCommentPicDto.serializer().nullable)
        }
        val element = decoder.decodeJsonElement() as? JsonObject ?: return null
        return try {
            decoder.json.decodeFromJsonElement<WeiboCommentPicDto>(element)
        } catch (_: SerializationException) {
            null
        }
    }
}

/** `pics` 既可能是数组也可能是单个对象，统一收敛成列表后再按 pid 去重。 */
object CommentPicsSerializer : JsonTransformingSerializer<List<WeiboCommentPicDto>>(
    ListSerializer(WeiboCommentPicDto.serializer())
) {
    override fun transformDeserialize(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
        val array = element as? JsonArray ?: (element as? JsonObject)?.let { JsonArray(listOf(it)) }
        return JsonArray(array?.filterIsInstance<JsonObject>() ?: emptyList())
    }
}
