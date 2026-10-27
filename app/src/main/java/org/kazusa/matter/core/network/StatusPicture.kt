package org.kazusa.matter.core.network

/**
 * 已上传到微博图床的一张图片。
 *
 * 发表接口要求 `pic_id` 是 `[{"type":"image/jpeg","pid":"..."}]` 形式的 JSON 数组
 * （2026-10 抓包核实的网页端表单），因此 pid 必须和上传时的真实 MIME 配对，
 * 不能一律当 JPEG：[mimeType] 来自读取图片时的原始类型。
 */
data class StatusPicture(
    val pid: String,
    val mimeType: String = "image/jpeg"
)
