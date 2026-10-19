package org.kazusa.matter.core.network

/**
 * m.weibo.cn 列表接口在「没有内容」时的固定提示语。
 */
internal fun isWeiboNoContentMessage(msg: String?): Boolean {
    val text = msg?.trim().orEmpty()
    if (text.isEmpty()) return false
    return text.contains("这里还没有内容") ||
        text.contains("还没有内容") ||
        text.contains("暂无数据") ||
        text.contains("暂无内容") ||
        text.contains("没有更多")
}

/**
 * 判断列表接口响应是否应当视为「空列表」而不是请求失败。
 *
 * 转发 / 评论 / 赞数量为 0 时，m.weibo.cn 不会返回 data，而是返回：
 * ```
 * { "ok": 0, "msg": "这里还没有内容" }
 * ```
 * 少数接口则返回 `ok = 0` + 空数组。这两种情况都只是「确实没有内容」，
 * 如果按 ApiResult.Error 处理，正文页就会把 0 转发 / 0 评论显示成「加载失败」。
 *
 * @param ok 响应中的 ok 字段
 * @param msg 响应中的 msg 字段
 * @param itemCount data.data 的条数；data 为 null 时传 null
 */
internal fun isWeiboEmptyListResponse(ok: Int, msg: String?, itemCount: Int?): Boolean {
    if (ok != 0) return false
    if (itemCount == null) return isWeiboNoContentMessage(msg)
    return itemCount == 0
}
