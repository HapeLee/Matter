package org.kazusa.matter.core.util

/** 兼容 "123" / "1.2万" 这类计数文案，无法解析时返回 null。 */
fun parseInteractionCount(raw: String?): Int? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    text.toIntOrNull()?.let { return it }
    if (text.endsWith("万")) {
        return text.dropLast(1).toDoubleOrNull()?.let { (it * 10_000).toInt() }
    }
    return null
}

/** 点赞数展示：小于等于 0 时回退到动作名（赞），上万折算成 "x.x万"。 */
fun formatInteractionCount(count: Int, defaultLabel: String): String = when {
    count <= 0 -> defaultLabel
    count >= 10_000 -> String.format("%.1f万", count / 10_000.0)
    else -> count.toString()
}
