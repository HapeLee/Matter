package org.kazusa.matter.ui.components.composer

import org.kazusa.matter.core.network.StatusPicture
import org.kazusa.matter.data.model.ReplyImageHandle

/**
 * 待发图片：平台句柄 + 上传成功后拿到的 pid / MIME。
 *
 * 发微博与转发共用一个提交语义：图片在**提交时**才上传，失败保留草稿与已上传的 pid，
 * 重试不重复上传，因此状态里同时保留句柄和上传结果。
 */
internal data class ComposerImage(
    val handle: ReplyImageHandle,
    val uploaded: StatusPicture? = null
)
