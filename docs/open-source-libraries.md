# 开源组件页面

入口：「我的 → 更多设置 → 开源组件」。使用 AboutLibraries 15.3.0 的 Material 3 UI，展示当前构建使用的组件、版本、项目链接与许可证正文，跟随 Matter 的主题设置。

`com.mikepenz.aboutlibraries.plugin.android` 在 Android 构建时按变体自动生成 `R.raw.aboutlibraries`，不需要手工维护清单或单独执行导出命令。`includePlatform = false` 排除仅用于约束版本的 BOM。清单包括运行时的传递依赖，排除测试与编译器依赖。

生成结果位于 `app/build/generated/aboutLibraries/<variant>/res/raw/aboutlibraries.json`，随 APK 打包；页面通过显式资源 ID 读取，兼容 release 资源裁剪。打开页面及阅读已打包的许可证不需要联网，访问项目网站需要联网。

升级方式：修改 `gradle/libs.versions.toml` 中的 `aboutLibraries` 版本，保持 Gradle 插件与 Compose UI 版本一致。

参考：[AboutLibraries 官方项目与接入文档](https://github.com/mikepenz/AboutLibraries)。
