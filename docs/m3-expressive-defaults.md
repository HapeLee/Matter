# M3 Expressive 默认主题

MatterTheme 直接采用 MaterialExpressiveTheme 的默认排版、全局形状和动效，保留用户选择的 Monet / MaterialKolor 配色、AMOLED 和玻璃设置。

- 删除自定义 Type.kt 和 MatterShapes；不覆盖主题字号、字重、行高或圆角档位。
- EmptyMessage 接收 TextStyle；移除未读角标字重、分组说明行高及个人简介行高的独立覆盖。
- 顶栏随已动画化的玻璃显示进度，在 headlineMediumEmphasized 和 headlineSmallEmphasized 两档主题 TextStyle 之间平滑插值；显示和隐藏双向过渡。搜索框使用相同规则，无手写字号/字重。
- 保留玻璃配方和组件自身的局部形状。TextStyle.copy 仅处理颜色和阴影；动画通过主题样式插值，不独立覆盖文字指标。

验证：`:app:compileDebugKotlin :app:testDebugUnitTest` 成功；168 项单测，无失败、错误或跳过。`git diff --check` 通过；main 源码未发现直接设置 fontSize/fontWeight/lineHeight/letterSpacing 或重建 Typography/Shapes 表。未在设备上运行 Matter；同套顶栏切换在 Sendee 实机检查。

标题动画修改后，两个项目 compileDebugKotlin 均通过；Sendee 设计规则检查通过。本次未重新运行此前的 168 项单测。
