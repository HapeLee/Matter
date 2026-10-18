# Koin 依赖注入

使用 Koin 4.2.2 的 Android 与 Compose 集成，在 `MatterApplication.onCreate` 中通过 `startKoin` 启动。依赖定义集中在 `core/di/AppModule.kt`，原手动 `AppContainer` 和自定义 ViewModel Factory 已移除。

- `dataModule`：偏好设置、JSON、HTTP 客户端、Room 数据库、DAO、缓存管理器和普通 Repository 是应用级 `single`。关闭 Koin 容器时关闭 HTTP 客户端。
- `FavoritesRepository`：带 `UserSession` 参数的 `factory`。导航根按 UID、Cookie、登录状态记忆一次解析结果；切换会话后获取新仓库，不共享旧账号的收藏状态。
- `viewModelModule`：登录、时间线、分组、浏览历史、赞过与收藏五个 ViewModel 都用 `viewModel` 定义。Compose 通过 `koinViewModel()` 获取，继续使用 Navigation 3 每个导航条目的 `ViewModelStoreOwner`，弹出条目时释放。
- `SavedPostsViewModel`：收藏仓库通过 `parametersOf` 传入，`SavedStateHandle` 由 Koin 的 ViewModel Factory 从导航条目的 CreationExtras 创建，保留选中分栏恢复能力。

普通 Compose 依赖使用 `koinInject()`，Activity 使用 `by inject()`。页面已有的显式参数可以继续用于测试；Repository 和 ViewModel 仍用构造函数接收依赖，不在业务类内访问全局 Koin。

新增依赖时，在对应模块中注册定义；新增 ViewModel 时注册 `viewModel`，并在导航条目内调用 `koinViewModel()`。不要把 ViewModel 注册成应用单例，也不要把登录会话对象捕获到应用单例中。

`AppModuleTest` 覆盖单例与资源关闭、账号收藏状态隔离和状态恢复参数。`ViewModelInjectionTest` 在 Robolectric Android 环境中解析生产依赖图，并验证导航条目内复用、条目间隔离及真实 `SavedStateHandle` 注入。

参考：[Koin Compose](https://insert-koin.io/docs/reference/koin-compose/compose/)、[ViewModel 注入](https://insert-koin.io/docs/reference/koin-compose/compose-viewmodel/)。
