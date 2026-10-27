package org.kazusa.matter.ui.components

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * 信息流卡片里的正文是否挂 [SelectionContainer]（长按选词复制）。
 *
 * 每挂一层都要为这个列表项创建一份独立的选区注册器、`DisposableEffect` 订阅和手势处理节点，
 * 一屏十几张卡片全挂上是一笔纯固定开销，而「逐条复制信息流正文」并不是常用操作。
 * 主框架在信息流分页里下发 `false`，正文页仍保留（正文页是真正会去复制文本的地方）。
 *
 * 默认 `true`：其他页面（博主主页、搜索结果、收藏等）行为完全不变。
 */
val LocalCardTextSelectionEnabled = compositionLocalOf { true }

/**
 * 按 [LocalCardTextSelectionEnabled] 决定是否包一层 [SelectionContainer]。
 *
 * 注意不要写成 `content = { ... }` 的 lambda 参数形式再套 `if`：这里直接以插槽方式接收内容，
 * 关闭时不会额外产生一层组合节点。
 */
@Composable
fun CardTextSelection(content: @Composable () -> Unit) {
    if (LocalCardTextSelectionEnabled.current) {
        SelectionContainer(content = content)
    } else {
        content()
    }
}
