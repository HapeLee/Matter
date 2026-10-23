package org.kazusa.matter.data.model

data class OnlineEmojiPackage(val id: String, val version: String, val downloadUrl: String) {
    val title: String get() = when (id) {
        "com.sina.default" -> "微博热门表情"
        "com.sina.lxh" -> "浪小花"
        else -> Regex("emotions(\\d{4})-(\\d{2})").find(id)?.let { "${it.groupValues[1]} 年 ${it.groupValues[2]} 月表情" }
            ?: id.removePrefix("com.sina.")
    }
}

data class OnlineEmoji(val code: String, val image: String, val aliases: List<String> = emptyList())
data class InstalledEmojiPackage(val id: String, val version: String, val title: String, val emojis: List<OnlineEmoji>)

data class OnlineEmojiState(
    val catalog: List<InstalledEmojiPackage> = emptyList(),
    val packages: List<OnlineEmojiPackage> = emptyList(),
    val installed: List<InstalledEmojiPackage> = emptyList(),
    val refreshing: Boolean = false,
    val downloading: Set<String> = emptySet(),
    val error: String? = null,
    val packageErrors: Map<String, String> = emptyMap()
)

/** ZIP 解包、持久化和图片句柄由平台实现，数据层不依赖 File / Context。 */
interface EmojiPackageStore {
    suspend fun restoreCatalog(): List<InstalledEmojiPackage>
    suspend fun saveCatalog(groups: List<InstalledEmojiPackage>)
    suspend fun restore(): List<InstalledEmojiPackage>
    suspend fun install(pack: OnlineEmojiPackage, zip: ByteArray): InstalledEmojiPackage
}
