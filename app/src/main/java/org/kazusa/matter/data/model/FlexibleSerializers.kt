package org.kazusa.matter.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 支持从数字、带单位字符串（如 "16.9万"）或任何基本类型灵活解析为 String
 */
object FlexibleStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String {
        return if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonPrimitive) {
                element.content
            } else {
                element.toString()
            }
        } else {
            decoder.decodeString()
        }
    }
}

/**
 * 支持从布尔值、数字（1/0）或字符串（"true"/"1"）灵活解析为 Boolean?。
 *
 * 微博不同端下发的 `following` / `follow_me` 既有 true/false，也有 1/0，
 * 直接声明成 Boolean 会在后端换格式时整棵树解析失败。
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object FlexibleBooleanSerializer : KSerializer<Boolean?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleBoolean", PrimitiveKind.BOOLEAN)

    override fun serialize(encoder: Encoder, value: Boolean?) {
        if (value != null) encoder.encodeBoolean(value) else encoder.encodeNull()
    }

    override fun deserialize(decoder: Decoder): Boolean? {
        return if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonPrimitive) {
                when {
                    !element.isString -> element.content == "true" || element.content == "1"
                    else -> element.content.equals("true", ignoreCase = true) || element.content == "1"
                }
            } else null
        } else {
            try {
                decoder.decodeBoolean()
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * 支持从长整型或字符串形式的数字灵活解析为 Long?
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object FlexibleLongSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleLong", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value != null) {
            encoder.encodeLong(value)
        } else {
            encoder.encodeNull()
        }
    }

    override fun deserialize(decoder: Decoder): Long? {
        return if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonPrimitive) {
                element.content.toLongOrNull()
            } else null
        } else {
            try {
                decoder.decodeLong()
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * 支持从整型或字符串形式的数字灵活解析为 Int?
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object FlexibleIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleInt", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Int?) {
        if (value != null) {
            encoder.encodeInt(value)
        } else {
            encoder.encodeNull()
        }
    }

    override fun deserialize(decoder: Decoder): Int? {
        return if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonPrimitive) {
                element.content.toIntOrNull()
            } else null
        } else {
            try {
                decoder.decodeInt()
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * 支持解析微博中的 page_pic 字段：
 * 微博后端在此字段上既可能返回直接的图片 URL 字符串："https://wx4.sinaimg..."
 * 也可能返回一个图片元数据对象：{"url": "https://wx4.sinaimg...", "width": 1080, ...}
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object WeiboPagePicSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("WeiboPagePic", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String?) {
        if (value != null) {
            encoder.encodeString(value)
        } else {
            encoder.encodeNull()
        }
    }

    override fun deserialize(decoder: Decoder): String? {
        return if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            when (element) {
                is JsonPrimitive -> element.content
                is JsonObject -> {
                    // 如果是对象，优先提取 "url"，其次提取可能存在的 "pid" 等
                    val urlPrimitive = element["url"] as? JsonPrimitive
                    urlPrimitive?.content ?: element.values.firstOrNull { it is JsonPrimitive }?.let { (it as JsonPrimitive).content }
                }
                else -> null
            }
        } else {
            try {
                decoder.decodeString()
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * 支持解析微博中的 title 字段：
 * 既可能返回字符串形式的标题，也可能返回 {"text": "...", "base_color": 1} 等对象
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object WeiboTitleTextSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("WeiboTitleText", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String?) {
        if (value != null) {
            encoder.encodeString(value)
        } else {
            encoder.encodeNull()
        }
    }

    override fun deserialize(decoder: Decoder): String? {
        return if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            when (element) {
                is JsonPrimitive -> element.content
                is JsonObject -> {
                    val textPrimitive = element["text"] as? JsonPrimitive
                    textPrimitive?.content ?: element.values.firstOrNull { it is JsonPrimitive }?.let { (it as JsonPrimitive).content }
                }
                else -> null
            }
        } else {
            try {
                decoder.decodeString()
            } catch (e: Exception) {
                null
            }
        }
    }
}
