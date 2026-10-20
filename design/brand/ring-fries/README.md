# Matter 圆环薯条 M

历史 C「松弛」方案（已由清新蓝凝核替代）：金色圆环，炭灰色薯条 M，纯白底。圆环线宽从 16 调整为 26，并在画板内整体内收，保留外侧两根向外舒展的排列。

- `matter-a.svg`：初始 A 方案，透明底。
- `matter-b.svg`：开口圆环，字形倾斜 9°。
- `matter-c.svg`：选用标志，加粗金色圆环，外侧两根向外舒展。
- `matter-mono.svg`：深色单色，透明底。
- `matter-reversed.svg`：浅色圆环与金色字形，适合深色底。
- `matter-app-icon.svg`：选用 C 版的纯白圆角底展示稿。
- `matter-design-board.svg`：三方案与使用场景对比。
- `generate.py`：上述矢量文件的几何源，可重复生成。

主标志使用 512 × 512 画板。圆环半径 186，C 版线宽 26（A、B 保留 16）；外侧条宽 40、内侧条宽 36。配色：炭灰 `#252A27`、暖金 `#E6A52E`、纯白 `#FFFFFF`（应用图标底）/ 暖白 `#F8F5ED`（展示板）。

全部标志由原生 SVG 几何图形组成，无嵌入位图、滤镜或字体依赖；对比板的说明文字使用系统字体。

Android 启动图标使用独立的前景矢量和纯白背景，API 33+ 额外提供单色主题图层；常规与圆形启动入口引用同一 adaptive icon。当前 minSdk 为 31，无需旧版位图回退。前景将主标志以中心为锚点缩放至 0.66，108 dp 图层中的外径约 55.4 dp，位于 66 dp 安全区内。圆角底 SVG 仅用于展示，Android 外形由启动器裁切。

`generate.py` 只在本目录的 `android-reference` 下导出旧方案参考资源。当前应用资源由 `../nucleus-palettes/export-android.py` 生成。`matter-launcher-preview.svg` 展示圆形、圆角方形及主题单色裁切。

尺寸依据：[Android adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)，核对于 2026-10-21。设备启动器实际显示与动态效果尚需真机确认。
