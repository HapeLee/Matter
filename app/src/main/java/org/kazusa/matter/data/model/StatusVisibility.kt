package org.kazusa.matter.data.model

/**
 * 写接口的分享范围，对应表单字段 `visible`。
 *
 * 取值沿用网页端发微博 / 转发的常见口径（`0` 公开、`6` 好友圈、`1` 仅自己可见）；
 * 与 `repost` 的其余字段一样，尚未逐项在真机上抓包核对，失败细节会写进 logcat。
 */
enum class StatusVisibility(val value: String, val label: String) {
    PUBLIC("0", "公开"),
    FRIENDS("6", "好友圈"),
    PRIVATE("1", "仅自己可见")
}
