package org.kazusa.matter.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
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
