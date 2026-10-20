# KMP / Compose Multiplatform 架构与开发规范

官方文档核对日期：2026-10-20。此文是 Matter 的工程约定；“项目约定”代表结合本项目作出的选择，不声称是所有 KMP 工程唯一的官方范式。升级工具链或实施迁移前重新核对文末官方来源，并记录验证版本与结果。

## 0. 执行基线：当前统一，优先最新稳定方案

- 项目尚处于可集中整理的体量，本规范是当前架构统一的验收基线，不是仅供未来参考的方向。架构任务按同类能力统一整理，避免一个功能一套模式。
- 新增和重构采用官方当前推荐、目标平台支持的 API。已废弃 API 和仅为旧实现保留的兼容开关应在对应统一任务中替换、删除，避免继续扩散旧入口。
- 依赖选择为“满足全部目标与工具链约束的最新稳定组合”。实施时查官方 release notes 和兼容矩阵，记录实际版本与核对日期；不把本文日期、示例版本或现有 version catalog 当作永久版本上限。
- 若所需能力仅有 alpha / beta / RC，或当前已有预发布依赖，按能力记录采用原因、平台验证和回退路径。既不自动追预发布，也不因“稳定优先”强制降级已经验证的必需能力。
- 架构统一允许跨页面和跨层改动，完成后保留一个正式入口。短期适配器必须有删除条件，迁移完成删除旧实现，不长期维护新旧双轨。
- “必须”是项目验收要求；框架允许的其它写法仍可有效，但不作为本项目并行的新标准。仅增加文档不代表这些要求已在代码中完成。

## 1. 范围与模块结构

当前 `settings.gradle.kts` 仅包含 `:app`，使用 AGP 9；以下为迁移目标，不是已存在目录。

```text
:app          Android 应用入口，保留现有模块名即可
:desktopApp   JVM Desktop 应用入口、窗口和打包
:shared       KMP 共享业务、ViewModel 与可共享 CMP UI
  commonMain / commonTest
  androidMain / androidUnitTest（具体名称按插件配置）
  jvmMain / jvmTest（若目标命名 desktop，则相应为 desktopMain / desktopTest）
```

- 按官方当前建议将应用入口与共享模块分开。AGP 9 下共享 Android target 使用 `com.android.kotlin.multiplatform.library`，不在同一个 KMP 共享模块里使用 Android application 插件。迁移 DSL 按兼容文档核对。
- 初期一个共享模块足够。仅在原生 UI、服务端共享或依赖隔离需求明确时拆为 sharedLogic / sharedUI，避免按每个类建模块。
- `commonMain` 仅依赖所有已声明目标均支持的 API；Android 与 Desktop 同为 JVM 不能使 `java.*` 自动成为 common API。仅用于平台实现的 Java API 放对应 JVM 源码集。
- 每个平台保留原生入口。Desktop 是 JVM 应用；共享 UI 不意味着原生播放器、权限和窗口 API 也能原样共享。

依据：[模块结构][structure]、[AGP 9 迁移][agp9]、[平台入口][entry]。

## 2. 分层与职责（项目约定）

```text
UI → ViewModel → Repository → 网络 / 本地数据源
                           → 平台能力接口及其实现
```

- 页面 Composable 不直接调用 HTTP、数据库或 DataStore。路由层获取 ViewModel，内容组件接收只读状态和事件回调；可复用组件不依赖页面 ViewModel。
- ViewModel 负责页面状态、用户事件、分页与请求协调；Repository 是业务数据入口，处理缓存、数据映射、会话隔离及跨页面共享事实。
- 关注、点赞、收藏等按业务 ID 维护统一可观察状态；不得靠各页面复制布尔值并手工互相补写。架构统一覆盖所有使用方，完成后删除旧 Store / 覆盖 Map 的独立权威入口。
- 网络协议与 DTO 解析可继续由 WeiboApiService 承担。Gateway / RemoteDataSource 用于独立协议、多来源或平台 SDK 边界，不要求每个 Repository 都套一层空转发接口。
- Domain / UseCase 仅在跨 ViewModel 复用或业务复杂度需要时引入。
- 对外业务模型和 UI State 不暴露带接口解析细节的 DTO；Repository 负责 DTO → 业务数据映射，ViewModel 按展示需求构建 UI State。可直接展示的精简业务模型不再复制同构 UiModel；不为贴稳定性注解增加模型层。

依据：[分层建议][architecture]。

## 3. 平台能力与依赖装配

- 使用小而明确的接口，例如 MediaSaver、ExternalLinkOpener、LocationProvider、CredentialStore、AppDirectories；通过构造函数注入，便于平台替换和测试。
- 通常先选接口和工厂；需要编译期平台声明或库要求时使用 `expect/actual`。不把所有业务类都改成 expect class，也不创建承载所有系统操作的巨型 PlatformManager。
- Context、Activity、MediaStore、权限申请、AndroidView、ExoPlayer、PiP、RuntimeShader 等限制在 Android 入口或实现。桌面提供文件选择、播放器、通知、窗口及图形效果的可用实现或明确降级。
- 不向公共业务状态暴露 Context、Uri、Bitmap、File、PlayerView 等平台对象；按场景使用业务 ID、URL 或专门的平台句柄边界。
- Koin 公共模块注册 Repository / ViewModel；Android 模块提供 Context 和系统能力，Desktop 模块提供路径、引擎和窗口能力。业务类不访问全局 Koin；不得为跨平台迁移另起重复容器。
- 应用资源、窗口资源、会话资源分别定义所有者和关闭时机；不要将页面 ViewModel 注册成应用单例，也不要在跨账号单例中捕获旧会话。

依据：[expect / actual][expect]、[Koin CMP][koin]。

## 4. 状态、导航与生命周期

- 使用多平台 ViewModel / Lifecycle 的兼容制品，共享包名不代表当前 Android Maven 依赖可直接放 commonMain；按官方依赖说明选型。
- 导航统一采用多平台 Navigation 3，选择最新兼容稳定制品，不再新增另一套路由库。为每个导航条目提供 saveable-state 与 ViewModelStore decorators，并在弹栈时释放对应状态。
- 路由传可序列化的轻量 ID / 参数，不传 API 服务、大 DTO 或平台对象。序列化配置必须在目标平台测试，不依赖 JVM 反射隐式发现路由子类。
- SavedStateHandle 从真实导航条目 CreationExtras / DI 工厂获取；生产恢复路径不使用手动 `SavedStateHandle()` 冒充框架状态。保存空字符串等合法值时区分缺失与空值。
- saveable / SavedStateHandle 保存输入、分栏 ID、滚动位置等轻量状态；完整回复树、图片及大列表由数据层恢复。桌面重启恢复如有需求，应使用明确的持久化方案，不能把 Android 进程恢复保证套给桌面。
- 页面业务状态统一通过只读 `StateFlow<FeatureUiState>` 暴露，以不可变状态快照更新，按独立功能分区组织；公共数据层使用 Flow / suspend，不暴露 Compose Snapshot 集合。写入经 ViewModel / Repository 方法，UI 不可直接修改。
- Snapshot State 用于 UI 状态持有器、滚动、动画及布局；不要把所有状态合成一个巨型 UiState。ViewModel 使用 Snapshot State 本身合法，本项目为统一公共业务边界选择 StateFlow，此项不是官方对其它方案的否定。
- UI 收集状态使用适用的生命周期感知 API。业务结果优先建模为可确认消费的状态；提示类事件若使用 Channel / Flow，明确无订阅者、取消和重建时的交付语义，不用瞬时事件承载必须保留的业务事实。
- `remember` 不替代 ViewModel；`rememberSaveable` 不替代数据库。纯 UI 状态保持在合适的 UI 所有者中。
- Desktop 为 ViewModel / Lifecycle 提供主线程调度器，按官方说明配置 kotlinx-coroutines-swing；多窗口按窗口和导航条目管理 ViewModelStore，窗口关闭清理资源。
- Effect key 包含实际依赖；`DisposableEffect(resource)` 释放同一资源实例。不要在可能切换的播放器上使用 `DisposableEffect(Unit)` 捕获首个实例。

依据：[ViewModel][viewmodel]、[Lifecycle][lifecycle]、[Navigation 3][navigation]。

## 5. 请求、分页与会话一致性（项目约定）

- 每个业务分区定义请求 owner、Job、互斥规则及 token / generation；在可能出现旧响应时，取消之外还需校验结果身份。
- 刷新取代旧分页；同页不并发。列表、页码、游标、hasMore 成对提交，刷新失败保留原数据及其游标。失败与分页结束必须分别建模，并提供同页重试。
- 相册、关键词、榜单、账号切换使旧请求失效；成功、错误和 finally 清理均不得覆盖新请求状态。
- 切账号先推进会话身份并清理状态；旧请求的完成、失败、取消回滚均校验身份。避免只清 Map 却让在途任务重新写回旧值。
- 不吞 CancellationException；需要清理时使用 finally 并校验 owner。注入 dispatcher / clock 等确有测试需求的依赖；阻塞文件工作不在 UI 线程执行。

## 6. 网络、持久化与凭据

- Ktor 的请求、插件、序列化配置尽量共享，引擎与平台配置通过工厂 / DI 提供。OkHttp 可以服务 Android 和 JVM Desktop；只有目标支持要求变化时才换引擎。
- Room DAO / Entity / 数据库契约可按所选版本能力共享；平台提供路径和 builder，按目标配置 KSP。保留现有 Room 3 schema 与迁移，不能照抄 Room 2 或不同版本示例。
- DataStore 的偏好逻辑可共享，实例创建及路径按平台提供；同一文件避免重复实例。桌面持久数据库、偏好和凭据放应用数据目录，不把文档示例临时目录当正式存储位置。
- Cookie / token 的存储策略按平台安全能力评估；普通 DataStore 不等于加密凭据库。网络日志隐藏凭据。

依据：[Ktor engines][ktor]、[Room KMP][room]、[DataStore KMP][datastore]。

## 7. Compose 模型稳定性与桌面体验

- Strong Skipping 在 Kotlin 2.0.20+ 默认启用。稳定参数按 equals、不稳定参数按引用比较；缺少注解不代表不能跳过重组。
- 优先依赖编译器推断；`@Stable` / `@Immutable` 是契约，不会令普通 var 可观察或令 List / Map 深度不可变。不要批量添加，也不要为了稳定性自动把所有集合转为 ImmutableList。
- 需要注解时验证可观察性 / 不可变约束与 equals 成本。包含大集合或递归 DTO 的模型尤其先测量；使用编译器报告和实际性能证据决定优化。
- Desktop 验证可调整窗口、键盘导航、焦点、快捷键、鼠标滚轮、右键和文件保存。媒体查看不能只有双指缩放入口；不将手机布局简单拉宽作为桌面适配完成。

依据：[Compose stability][stability]、[平台行为差异][behavior]。

## 8. 依赖与迁移验证

- Kotlin、Compose compiler、CMP、AGP、Gradle、KSP、Lifecycle、Navigation、Room 分别核对兼容关系。Compose compiler 插件与 Kotlin 版本对齐；CMP 与 Android Compose 版本独立发布。
- 保留 version catalog；不要根据文档示例升级到 alpha / beta，也不要把 Android Compose BOM 当成 CMP 全依赖版本管理。
- AGP 9 采用内建 Kotlin 支持及 Android KMP Library 插件的正式配置，不以 legacy variant API 开关作为长期方案。替换已废弃的 Clipboard、组件重载等 API 时按所选平台版本的正式替代项实施；预览阶段 API 不冒充稳定 API。
- 引入多平台依赖前验证实际 target、源码集可用 API 和第三方 UI / 图形 / 媒体库支持。Android-only 库留在平台侧并提供边界；不能仅凭包名或产品描述认定兼容。
- 迁移按纵向可运行功能推进：建立 Desktop 入口 → 登录 / 只读时间线 → 详情及交互 → 持久化与媒体。每步保持 Android 可构建。
- commonTest 覆盖业务状态和竞态，平台测试覆盖路径、存储、真实 DI、导航恢复和窗口生命周期。构建通过不等于桌面运行或打包通过。
- Desktop 发布按目标 OS / 架构验证运行时与打包；原生安装包需要在对应 OS 构建，不把单机 JVM 编译通过当成 Windows / macOS / Linux 安装包均可用。

依据：[兼容性][compatibility]、[桌面分发][distribution]。

## 9. 统一工作清单与完成标准（项目约定）

以下是当前待执行基线，不代表工程已完成迁移。架构任务按这组标准验收，并为确实受依赖阻塞的条目登记原因和解除条件。

- [ ] Android 入口、Desktop 入口、共享 KMP 模块分离，按实际 target 配置源码集及依赖。
- [ ] 页面业务统一为 ViewModel + 只读 StateFlow；所有网络、数据库、偏好访问经 Repository / 平台能力边界。
- [ ] 点赞、关注、收藏仅保留各自单一事实来源，数据层不依赖 Compose Snapshot 集合；账号切换与旧请求回滚具备身份隔离。
- [ ] 网络 DTO 不跨越 Repository 对外边界；UI 不承担接口字段兼容和网络解析。
- [ ] Android 系统 API、凭据实现、媒体、路径和图形能力隔离，Android / Desktop DI 均能解析真实生产图。
- [ ] 同类分页和刷新采用一致的错误、游标、取消和请求身份语义，覆盖已有全部列表页面。
- [ ] Navigation 3 条目作用域、恢复及窗口关闭清理经过测试；废弃 API、旧入口和已完成迁移的适配器已删除。
- [ ] 维护依赖基线表，记录制品、版本、稳定性渠道、Android / Desktop 支持、兼容依据和验证结果；不能只写“最新版”。
- [ ] 公共业务测试、Android 测试、Desktop 编译与启动检查通过；平台打包按对应主机验证，未验证项明确列出。

## 官方来源

[structure]: https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html
[agp9]: https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html
[entry]: https://kotlinlang.org/docs/multiplatform/compose-multiplatform-entry-points.html
[expect]: https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html
[architecture]: https://developer.android.com/topic/architecture/recommendations
[viewmodel]: https://kotlinlang.org/docs/multiplatform/compose-viewmodel.html
[lifecycle]: https://kotlinlang.org/docs/multiplatform/compose-lifecycle.html
[navigation]: https://kotlinlang.org/docs/multiplatform/compose-navigation-3.html
[koin]: https://insert-koin.io/docs/reference/koin-compose/compose/
[ktor]: https://ktor.io/docs/client-engines.html
[room]: https://developer.android.google.cn/kotlin/multiplatform/room?hl=en
[datastore]: https://developer.android.com/kotlin/multiplatform/datastore
[stability]: https://developer.android.com/develop/ui/compose/performance/stability
[behavior]: https://kotlinlang.org/docs/multiplatform/compose-platform-specifics.html
[compatibility]: https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html
[distribution]: https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html

- [推荐模块结构][structure]、[AGP 9 迁移][agp9]、[平台入口][entry]、[平台声明][expect]
- [分层架构][architecture]、[ViewModel][viewmodel]、[生命周期][lifecycle]、[Navigation 3][navigation]
- [Koin][koin]、[Ktor][ktor]、[Room][room]、[DataStore][datastore]
- [稳定性][stability]、[平台差异][behavior]、[版本兼容][compatibility]、[桌面分发][distribution]
