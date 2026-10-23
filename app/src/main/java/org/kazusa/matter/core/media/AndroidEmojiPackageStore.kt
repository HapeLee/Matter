package org.kazusa.matter.core.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.kazusa.matter.core.network.KtorClientFactory
import org.kazusa.matter.core.network.safeEmojiPackageId
import org.kazusa.matter.core.network.safeEmojiVersion
import org.kazusa.matter.data.model.*

/** 仅写入注入的应用私有目录；ZIP 路径不直接用于落盘。 */
class AndroidEmojiPackageStore(private val root: File) : EmojiPackageStore {
    override suspend fun restoreCatalog(): List<InstalledEmojiPackage> = withContext(Dispatchers.IO) {
        runCatching {
            KtorClientFactory.jsonConfig.parseToJsonElement(safeFile(root, "catalog.json").readText()).jsonArray.map { element ->
                val group = element.jsonObject
                InstalledEmojiPackage(group.getValue("id").jsonPrimitive.content, "1", group.getValue("title").jsonPrimitive.content,
                    group.getValue("emojis").jsonArray.map {
                        val emoji = it.jsonObject
                        val image = emoji.getValue("image").jsonPrimitive.content
                        require(image.startsWith("https://face.t.sinajs.cn/"))
                        OnlineEmoji(emoji.getValue("code").jsonPrimitive.content, image)
                    })
            }
        }.getOrElse { emptyList() }
    }

    override suspend fun saveCatalog(groups: List<InstalledEmojiPackage>) = withContext(Dispatchers.IO) {
        root.mkdirs()
        val pending = safeFile(root, ".catalog-${UUID.randomUUID()}.json")
        try {
            pending.writeText(buildJsonArray {
                groups.forEach { group -> add(buildJsonObject {
                    put("id", group.id); put("title", group.title)
                    put("emojis", buildJsonArray { group.emojis.forEach { emoji -> add(buildJsonObject {
                        put("code", emoji.code); put("image", emoji.image)
                    }) } })
                }) }
            }.toString())
            Files.move(pending.toPath(), safeFile(root, "catalog.json").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            Unit
        } finally { pending.delete() }
    }

    override suspend fun restore(): List<InstalledEmojiPackage> = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty().mapNotNull { dir ->
            if (!dir.isDirectory || !safeEmojiPackageId(dir.name)) return@mapNotNull null
            runCatching { readInstalled(safeFile(dir, "current.json")) }.getOrNull()
        }
    }

    override suspend fun install(pack: OnlineEmojiPackage, zip: ByteArray): InstalledEmojiPackage = withContext(Dispatchers.IO) {
        require(safeEmojiPackageId(pack.id) && safeEmojiVersion(pack.version)) { "表情包标识无效" }
        require(zip.isNotEmpty() && zip.size <= 8 * 1024 * 1024) { "表情包文件大小无效" }
        val entries = linkedMapOf<String, ByteArray>()
        var expanded = 0
        ZipInputStream(ByteArrayInputStream(zip)).use { stream ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = stream.nextEntry ?: break
                if (entry.isDirectory) continue
                require(entries.size < 2048) { "表情包文件数量过多" }
                val name = entry.name
                require(!name.startsWith('/') && '\\' !in name && name.split('/').none { it == ".." || it == "." }) { "表情包路径无效" }
                require(name !in entries) { "表情包包含重复文件" }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    expanded += count
                    require(expanded <= 32 * 1024 * 1024 && output.size() + count <= 4 * 1024 * 1024) { "表情包解压内容过大" }
                    output.write(buffer, 0, count)
                }
                entries[name] = output.toByteArray()
            }
        }
        val infoName = entries.keys.firstOrNull { it == "info.json" || it.endsWith("/info.json") }
            ?: error("表情包缺少目录信息")
        val info = KtorClientFactory.jsonConfig.parseToJsonElement(entries.getValue(infoName).decodeToString()).jsonObject
        require(info["id"]?.jsonPrimitive?.content == pack.id && info["version"]?.jsonPrimitive?.content == pack.version) { "表情包版本不一致，请刷新目录" }
        val parent = infoName.substringBeforeLast('/', "")
        val title = listOf("group_name_cn", "group_name").firstNotNullOfOrNull {
            (info[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        } ?: pack.title
        val packDir = safeFile(root, pack.id).apply { mkdirs() }
        val versionDir = safeFile(packDir, pack.version)
        val temporary = safeFile(packDir, ".tmp-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val files = linkedMapOf<String, String>()
            val codes = mutableSetOf<String>()
            val metadata = buildJsonArray {
                (info["emoticons"] as? JsonArray).orEmpty().forEach { element ->
                    val item = element as? JsonObject ?: return@forEach
                    val code = item["chs"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val image = item["png"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    if (!code.matches(Regex("\\[[^\\[\\]\\s]{1,64}\\]")) || code.endsWith("_old]") || code.endsWith("_旧]") || !codes.add(code)) return@forEach
                    val member = if (parent.isBlank()) image else "$parent/$image"
                    val bytes = entries[member] ?: return@forEach
                    require(isImage(bytes)) { "表情包包含无效图片" }
                    val extension = image.substringAfterLast('.', "").lowercase()
                    if (extension !in setOf("png", "webp", "gif", "jpg", "jpeg")) return@forEach
                    val relative = files.getOrPut(member) {
                        "images/${files.size}.$extension".also { file ->
                            safeFile(temporary, file).apply { parentFile?.mkdirs(); writeBytes(bytes) }
                        }
                    }
                    add(buildJsonObject {
                        put("code", code)
                        put("file", relative)
                        put("aliases", buildJsonArray {
                            listOf("cht", "en").mapNotNull { (item[it] as? JsonPrimitive)?.contentOrNull }
                                .filter { it != code && it.matches(Regex("\\[[^\\[\\]\\s]{1,64}\\]")) }.distinct().forEach { add(it) }
                        })
                    })
                }
            }
            require(metadata.isNotEmpty()) { "表情包没有可用表情" }
            val manifest = buildJsonObject {
                put("id", pack.id); put("version", pack.version); put("title", title); put("emojis", metadata)
            }
            currentCoroutineContext().ensureActive()
            if (versionDir.exists()) {
                // 只替换本应用表情缓存中已经损坏或尚未被引用的同版本目录。
                val current = safeFile(packDir, "current.json")
                val cached = runCatching { readInstalled(current) }.getOrNull()
                if (cached?.version == pack.version) return@withContext cached
                check(versionDir.deleteRecursively()) { "无法更新表情缓存" }
            }
            check(temporary.renameTo(versionDir)) { "无法保存表情包" }
            val pending = safeFile(packDir, ".current-${UUID.randomUUID()}.json")
            pending.writeText(manifest.toString())
            try {
                Files.move(pending.toPath(), safeFile(packDir, "current.json").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally { pending.delete() }
            readInstalled(safeFile(packDir, "current.json"))
        } finally { temporary.deleteRecursively() }
    }

    private fun readInstalled(manifest: File): InstalledEmojiPackage {
        val json = KtorClientFactory.jsonConfig.parseToJsonElement(manifest.readText()).jsonObject
        val id = json.getValue("id").jsonPrimitive.content
        val version = json.getValue("version").jsonPrimitive.content
        require(safeEmojiPackageId(id) && safeEmojiVersion(version) && manifest.parentFile?.name == id)
        val base = safeFile(manifest.parentFile!!, version)
        val emojis = json.getValue("emojis").jsonArray.map { element ->
            val item = element.jsonObject
            val file = safeFile(base, item.getValue("file").jsonPrimitive.content)
            check(file.isFile && file.length() > 0)
            OnlineEmoji(item.getValue("code").jsonPrimitive.content, file.toURI().toString(),
                (item["aliases"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content })
        }
        require(emojis.isNotEmpty())
        return InstalledEmojiPackage(id, version, json.getValue("title").jsonPrimitive.content, emojis)
    }

    private fun isImage(bytes: ByteArray): Boolean =
        (bytes.size >= 8 && bytes.take(8).toByteArray().contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10))) ||
        (bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" && bytes.copyOfRange(8, 12).decodeToString() == "WEBP") ||
        (bytes.size >= 6 && bytes.copyOfRange(0, 3).decodeToString() == "GIF") ||
        (bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte())

    private fun safeFile(parent: File, relative: String): File {
        val result = File(parent, relative).canonicalFile
        require(result.toPath().startsWith(root.canonicalFile.toPath()) && result.toPath().startsWith(parent.canonicalFile.toPath()) && result != parent.canonicalFile) { "表情缓存路径无效" }
        return result
    }
}
