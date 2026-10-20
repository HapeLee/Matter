# Matter (基于 Jetpack Compose + Koin + Ktor + DataStore 的现代客户端)

开发与审查约定见 [AGENTS.md](AGENTS.md) 和 [KMP / Compose Multiplatform 架构规范](docs/kmp-cmp-guidelines.md)。当前工程仍为 Android 单模块，跨平台规范用于后续渐进支持 JVM Desktop。

本项目采用现代 Android 架构规范与最新技术栈（**Jetpack Compose + Material 3 + Koin 依赖注入 + Ktor Client + Jetpack DataStore + Room + Kotlin Coroutines & Flow**）构建，致力于提供高效、优雅且流畅的社交信息流阅读体验。

---

## 一、应用架构与技术选型

项目遵循官方推荐的 **单向数据流 (UDF)** 与分层架构设计，通过 **Koin** 进行统一的依赖注入装配，实现各模块间的彻底解耦与高可测试性：

```
app/src/main/java/org/kazusa/matter/
├── core/
│   ├── datastore/         # Jetpack DataStore (用户会话偏好与媒体/主题配置)
│   ├── di/                # Koin 依赖注入模块 (dataModule, viewModelModule, appModule)
│   └── network/           # Ktor Client 3.x 网络抽象层 + OkHttp 引擎 + ContentNegotiation
├── data/
│   ├── cache/             # 时间线与列表内存/磁盘缓存管理
│   ├── local/             # Room 本地持久化数据库 (浏览历史、搜索记录)
│   ├── model/             # Kotlinx Serialization 数据模型契约
│   └── repository/        # 仓库层 (Auth, Timeline, Group, History, Favorites)
├── ui/
│   ├── components/        # 基础可复用组件 (PostCard, ImageGrid, CommonTopBar 等)
│   ├── group/             # 关注分组列表页与 ViewModel
│   ├── history/           # 浏览/搜索历史页与 ViewModel
│   ├── login/             # 多方式登录页与 ViewModel
│   ├── navigation/        # Type-Safe Navigation 路由定义
│   ├── saved/             # 收藏与离线保存
│   ├── theme/             # Material 3 动态色彩与主题排版系统
│   └── timeline/          # 首页主信息流 (下拉刷新 + 无限分页) 与 ViewModel
├── MainActivity.kt        # 边缘到边缘 (Edge-to-Edge) 单 Activity 导航容器
└── MatterApplication.kt  # 全局 Application 入口 (初始化 Koin 与系统配置)
```

### 技术栈亮点：
1. **Koin 4.x 依赖注入**：
   * 采用纯 Kotlin DSL 进行依赖声明，无注解处理器 (kapt) 开销，启动轻量疾速。
   * 优雅管理网络单例 (`HttpClient`)、数据仓库 (`Repository`)、本地数据库 (`Room DAO`) 以及界面控制器 (`viewModel`)。
   * 支持带参工厂模式 (`factory { parameters -> ... }`)，精细化控制多账号下会话隔离组件的生命周期。
2. **Jetpack Compose + Material 3**：
   * 纯声明式 UI，完整适配 Edge-to-Edge 沉浸式视觉规范。
   * 自定义自适应多媒体布局（单图大图自适应比例、4 图 2x2 矩阵、多图九宫格排布）。
   * 结合 `MaterialKolor` 实现动态色彩提取与丰富的主题定制。
3. **Ktor Client 3.x + OkHttp**：
   * 现代化轻量级异步非阻塞网络客户端，集成 `ContentNegotiation` 与 `Kotlinx Serialization` 极速反序列化。
   * 统一网络拦截器处理请求鉴权、通用 Header 组装与异常重试。
4. **Jetpack DataStore & Room 本地持久化**：
   * **DataStore (Preferences)**：响应式 `Flow<UserSession>` 状态流管理用户凭据与个性化配置，保证多线程安全与数据一致性。
   * **Room 数据库**：结构化存储用户浏览足迹与检索历史，配合 KSP 代码生成兼顾高效与稳定。
5. **Type-Safe Navigation**：
   * 摒弃传统字符串路由，依托类型安全数据类声明页面跳转参数，杜绝传参类型转换崩溃。

---

## 二、核心功能特性

1. **初始新手引导 (Onboarding)**
   * **首启检测**：基于 DataStore 响应式检测引导完成状态，为新用户呈现沉浸式全屏引导画廊。
   * **特性速览**：采用 Compose `HorizontalPager`，流畅演示「纯净流」「分组管理」「便捷接入」等核心亮点。
   * **跳过与体验**：支持灵活跳过或随进度自然过渡至登录体验。

2. **多方式会话接入 (Authentication)**
   * **短信验证登录**：提供标准倒计时防重机制，通过短信验证快速完成登录并安全持久化用户会话。
   * **授权页快速登录**：内嵌专属授权容器，自动解析重定向结果无缝同步登录态。
   * **凭证快速导入**：面向极客与开发者提供会话凭证快速导入能力，经安全校验后即刻绑定。
   * **免密访客模式**：无需绑定账号，一键即可浏览公共精选动态。

3. **动态时间线浏览 (Timeline)**
   * **智能流加载**：下拉刷新 (`PullToRefreshBox`) 与触底智能游标分页。
   * **丰富富文本与多媒体**：支持表情、超链接富文本渲染，自适应九宫格图片预览及内置视频播放支持。
   * **微博互动展示**：折叠嵌套转发引用、转评赞计数智能格式化。
   * **滚动位置与缓存**：退出后保留列表浏览进度，保障二次启动与进出详情页的平滑无感知体验。

4. **分组与动态分类 (Groups)**
   * 动态拉取云端关注分组列表（特别关注、原创、自定义兴趣组等）。
   * 侧边栏/顶部切换标签快速无缝筛选对应分组微博，满足差异化阅读场景。

5. **本地足迹与收藏 (History & Saved)**
   * 自动记录浏览足迹，支持快捷检索历史动态。
   * 本地收藏箱能力，随时回味心仪内容。

---

## 三、编译与运行环境

* **Gradle Wrapper**: 9.8.1
* **Android Gradle Plugin (AGP)**: 9.4.1
* **Kotlin**: 2.4.21
* **Koin**: 4.2.2
* **JDK**: 21+
* **Compile SDK**: 35
* **Min SDK**: 26

---

## 四、免责声明 (Disclaimer)

1. **学习与交流用途**：本项目仅作为 Android 现代技术栈（Jetpack Compose、Koin、Ktor 等）的工程化实践、架构探索与开源学习交流使用，不作任何商业营利用途。
2. **协议与知识产权**：项目代码中不包含、不传播任何第三方专有商业资源或逆向破解成果。第三方平台的相关商标、版权及服务知识产权归其所属公司或权利人所有。
3. **数据与合规**：使用本项目时，请使用者严格遵守相关国家法律法规以及相关网络服务协议与条款，切勿用于任何非法数据抓取、攻击测试或破坏网络安全秩序的行为。
