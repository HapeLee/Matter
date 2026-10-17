# Matter (基于 Jetpack Compose + Ktor + DataStore 的现代客户端)

本项目采用 Android 现代技术栈（**Jetpack Compose + Material 3 + Type-Safe Navigation + Jetpack DataStore + Ktor Client + Kotlin Coroutines & Flow**）构建，实现高效流畅的第三方微博客户端体验。

---

## 一、微博后端通信协议概览

应用主要与以下微博服务集群进行数据交互：

### 1. 通信架构与核心域名
* **私有移动客户端 API**：`https://api.weibo.cn/2/` 与 `https://api.weibo.com/2/`
* **移动端轻量级 API 与 H5 体系**：`https://m.weibo.cn/`
* **网页/扫码授权认证网关**：`https://passport.weibo.cn/signin/`

### 2. 核心 API 端点映射
| 业务场景 | 内部端点 | 请求方式与关键参数 | 功能说明 |
| :--- | :--- | :--- | :--- |
| **登录与鉴权** | `/2/account/login`<br>`account/login_sendcode`<br>`account/login_smsverify`<br>`/2/qtcode/login` | POST / GET<br>`SUB`, `SUBP`, `gsid`, `token` | 验证码登录、扫码登录及会话校验 |
| **会话/用户信息** | `https://m.weibo.cn/api/config`<br>`users/show` | GET (携带 Cookie) | 验证当前会话有效性，拉取当前登录 UID、昵称、头像 |
| **好友/关注流** | `2/statuses/container_timeline`<br>`statuses/friends/timeline`<br>`https://m.weibo.cn/feed/friends` | GET<br>`page`, `max_id`, `since_id`, `count` | 获取首页关注博主发布的最新的微博信息流（支持基于 max_id 游标分页） |
| **分组微博流** | `groups/timeline`<br>`https://m.weibo.cn/feed/group` | GET<br>`gid`/`list_id`, `page`, `max_id` | 获取指定关注分组下的微博流 |
| **关注分组管理** | `groups/allgroups/v2`<br>`https://api.weibo.com/2/friendships/groups.json` | GET (携带 Cookie/Token) | 获取用户自定义与系统关注分组列表（如特别关注、互相关注等） |
| **详情与互动** | `comments/build_comments`<br>`friendships/create`<br>`remind/unread_count` | GET / POST<br>`id`, `uid` | 评论流拉取、关注/取关、未读红点同步 |

### 3. 会话保持与凭据存储机制
关键凭据即为 `SUB`、`SUBP` 与 `gsid`。本地采用 Jetpack DataStore 响应式持久化管理用户会话。

---

## 二、应用架构与技术栈选型

本项目使用现代 Android 技术栈进行完整实现：

```
app/src/main/java/org/kazusa/matter/
├── core/
│   ├── datastore/         # Jetpack DataStore (UserSession 偏好与凭据存储)
│   ├── di/                # AppContainer 手工依赖注入容器
│   └── network/           # Ktor Client 3.0 + OkHttp 引擎 + ContentNegotiation
├── data/
│   ├── model/             # Kotlinx Serialization 数据契约 (Auth, Timeline, Group)
│   └── repository/        # 仓库层 (AuthRepository, TimelineRepository, GroupRepository)
├── ui/
│   ├── components/        # 基础组件 (WeiboPostCard, ImageGrid, CommonTopBar)
│   ├── group/             # 关注分组列表页与 ViewModel
│   ├── login/             # 登录页 (WebView 自动捕获 Cookie + 手动导入) 与 ViewModel
│   ├── navigation/        # Type-Safe Navigation (Screen.Login, Screen.Timeline, Screen.GroupList)
│   ├── theme/             # Material 3 主题、色板与排版系统
│   └── timeline/          # 首页时间线流 (下拉刷新 + 无限加载) 与 ViewModel
├── MainActivity.kt        # 边缘到边缘 (Edge-to-Edge) 单 Activity 容器
└── MatterApplication.kt  # 全局 Application 初始化
```

### 技术栈亮点：
1. **Jetpack Compose + Material 3**：纯声明式 UI，完整实现了微博九宫格图片布局、带富文本解析的微博卡片、转发微博内嵌卡片及操作栏。
2. **Type-Safe Navigation**：使用 Kotlinx Serialization 驱动的现代路由声明，消除了字符串路径拼接与类型转换隐患。
3. **Jetpack DataStore (Preferences)**：响应式 `Flow<UserSession>` 状态流，提供现代化的凭据持久化管理，支持退出清空、启动自动分发路由。
4. **Ktor Client 3.0**：轻量异步非阻塞网络库，集成 OkHttp 引擎、ContentNegotiation JSON 自动反序列化及拦截器自动补全 Header/Cookie。

---

## 三、核心功能演示与说明

1. **初始新手引导页 (Onboarding)**
   * **首次启动检测**：基于 Jetpack DataStore 监听 `isOnboardingCompletedFlow`，初次启动呈现沉浸式全屏引导画廊。
   * **功能特性速览**：采用 Compose `HorizontalPager`，包含「纯净时间线」「关注分组管理」「多样登录接入」三幕特性介绍与圆点进度条。
   * **跳过与完成**：支持右上角跳过或翻页至末尾点击「进入应用 · 开启体验」，记录完成状态并进入登录页。

2. **多方式账号登录 (Login)**
   * **手机短信验证码登录**：针对国内手机号（`+86`），提供 60 秒倒计时防重发机制，调用 `passport.weibo.cn/sso/sendcode` 与 `passport.weibo.cn/sso/login`，自动从响应头 `Set-Cookie` 提取并持久化 `SUB` 会话凭据。
   * **网页快捷授权登录**：内嵌专属授权 WebView，监听重定向并自动从 `CookieManager` 提取 `SUB`、`SUBP` 会话凭据，免密无缝保存。
   * **Cookie / SUB 导入**：支持开发者直接填入网页端复制的 Cookie 或 `SUB` 凭据，自动请求 `m.weibo.cn/api/config` 校验合法性并绑定账号。
   * **访客免密体验模式**：支持一键进入访客模式直接浏览公共动态。
2. **基础微博浏览 (Timeline)**
   * 支持关注流与主时间线展示。
   * 下拉刷新 (`PullToRefreshBox`) 与触底智能加载更多。
   * 自适应微博图片网格（单图自适应大图、4图2x2矩阵、2/3/5-9图九宫格排布）。
   * 转发微博折叠展示、转评赞计数格式化。
3. **关注分组管理 (Groups)**
   * 拉取用户云端关注分组（特别关注、互相关注、原创、自定义兴趣组等）。
   * 点击分组无缝切换时间线上下文，动态加载分组微博。

---

## 四、编译与构建要求
* **Gradle Wrapper**: 9.8.1（最新主流分支）
* **Android Gradle Plugin (AGP)**: 9.4.1（内置 Kotlin 支持）
* **Kotlin**: 2.4.21
* **Android Studio**: 最新正式版及预览版支持
* **JDK**: 21+
* **Compile SDK**: 35
* **Min SDK**: 26
