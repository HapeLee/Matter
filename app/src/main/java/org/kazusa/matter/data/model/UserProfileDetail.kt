package org.kazusa.matter.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 博主详细资料（PC 端个人主页「资料」模块）。
 *
 * 数据来源：
 * - `https://weibo.com/ajax/profile/detail?uid={uid}`（字段最全）
 * - `https://weibo.com/ajax/profile/info?uid={uid}` 的 `data.user`（兜底）
 *
 * 移动端 `m.weibo.cn` 的 `100505{uid}` 容器只下发 userInfo（昵称/头像/粉丝数等），
 * 生日、星座、IP 属地、注册时间、教育/职业、阳光信用、动态标签都拿不到。
 */
@Immutable
data class UserProfileDetail(
    val location: String? = null,
    val gender: String? = null,
    val birthday: String? = null,
    val constellation: String? = null,
    val ipLocation: String? = null,
    val registeredAt: String? = null,
    val description: String? = null,
    val school: String? = null,
    val company: String? = null,
    val sunshineCreditLevel: String? = null,
    val labels: List<String> = emptyList(),
    val domain: String? = null
) {
    val genderLabel: String?
        get() = when (gender?.trim()?.lowercase()) {
            "m", "male", "男" -> "男"
            "f", "female", "女" -> "女"
            else -> null
        }

    /** 注册时间只展示日期部分，避免一行里出现秒级时间戳 */
    val registeredDate: String?
        get() = registeredAt?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.substringBefore(' ')
            ?.takeIf { it.isNotBlank() }

    val hasProfileInfo: Boolean
        get() = !location.isNullOrBlank() ||
            genderLabel != null ||
            !birthday.isNullOrBlank() ||
            !constellation.isNullOrBlank() ||
            !ipLocation.isNullOrBlank() ||
            registeredDate != null ||
            !school.isNullOrBlank() ||
            !company.isNullOrBlank() ||
            !sunshineCreditLevel.isNullOrBlank() ||
            labels.isNotEmpty()
}

/**
 * 解析 `profile/detail` 的 `data` 节点。
 *
 * 微博在不同端/不同版本对个别字段的形态并不稳定：
 * - `education` 可能是对象，也可能是对象数组；
 * - `label_desc` 可能是 `[{"name": "..."}]`，也可能是纯字符串数组；
 * - `birthday` 有时把星座拼在同一个字符串里（如 `1948-06-15 双子座`）。
 * 这里统一收敛成 UI 可直接消费的字符串，避免一个字段变形导致整页解析失败。
 */
fun parseUserProfileDetail(data: JsonObject): UserProfileDetail {
    val rawBirthday = data.stringOrNull("birthday")
    val birthdayParts = rawBirthday?.split(Regex("\\s+"), limit = 2).orEmpty()
    val birthdayTail = birthdayParts.getOrNull(1)?.trim()
    val constellationFromBirthday = birthdayTail?.takeIf { it in CONSTELLATIONS }
    val birthday = if (constellationFromBirthday != null) birthdayParts.first() else rawBirthday

    return UserProfileDetail(
        location = data.stringOrNull("location"),
        gender = data.stringOrNull("gender"),
        birthday = birthday?.trim()?.ifBlank { null },
        constellation = data.stringOrNull("constellation") ?: constellationFromBirthday,
        // 接口可能直接下发「IP属地：北京」，前缀属于展示文案，模型只保留纯属地
        ipLocation = data.stringOrNull("ip_location")?.removeIpLocationPrefix()?.ifBlank { null },
        registeredAt = data.stringOrNull("created_at"),
        description = data.stringOrNull("description"),
        school = data["education"].educationSchool(),
        company = data.stringOrNull("company") ?: data["career"].careerCompany(),
        sunshineCreditLevel = data["sunshine_credit"].sunshineCreditLevel(),
        labels = data["label_desc"].profileLabelNames(),
        domain = data.stringOrNull("domain")
    )
}

private val CONSTELLATIONS = setOf(
    "白羊座", "金牛座", "双子座", "巨蟹座", "狮子座", "处女座",
    "天秤座", "天蝎座", "射手座", "摩羯座", "水瓶座", "双鱼座"
)

private val IP_LOCATION_PREFIX = Regex("^\\s*IP\\s*属地\\s*[:：]\\s*", RegexOption.IGNORE_CASE)

/** 剥掉接口自带的「IP属地：」前缀，中英文冒号、空格都兼容。 */
private fun String.removeIpLocationPrefix(): String = replace(IP_LOCATION_PREFIX, "").trim()

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }

private fun JsonElement?.educationSchool(): String? = when (this) {
    is JsonObject -> stringOrNull("school") ?: stringOrNull("name")
    is JsonArray -> firstOrNull().educationSchool()
    else -> null
}

private fun JsonElement?.careerCompany(): String? = when (this) {
    is JsonObject -> stringOrNull("company") ?: stringOrNull("name")
    is JsonArray -> firstOrNull().careerCompany()
    else -> null
}

private fun JsonElement?.sunshineCreditLevel(): String? = when (this) {
    is JsonObject -> stringOrNull("level") ?: stringOrNull("name")
    is JsonPrimitive -> contentOrNull?.trim()?.ifBlank { null }
    else -> null
}

private fun JsonElement?.profileLabelNames(): List<String> = when (this) {
    is JsonArray -> mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> item.contentOrNull?.trim()?.ifBlank { null }
            is JsonObject -> (item["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }
            else -> null
        }
    }

    is JsonObject -> listOfNotNull(stringOrNull("name"))
    is JsonPrimitive -> listOfNotNull(contentOrNull?.trim()?.ifBlank { null })
    else -> emptyList()
}
