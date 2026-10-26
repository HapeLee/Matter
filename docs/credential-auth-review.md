# Matter 用户凭据保存与微博 API 鉴权 —— 安全评估报告

评估对象：`d:/Project/Matter`（`org.kazusa.matter`，Android + Compose，微博第三方客户端）
对照样本：`D:/Project/One` 反编译产物（`app.unique.one` v1.1.6，复刻微博官方私有协议）
评估日期：2026-10-24
文档状态：**调研完成，未修改任何业务代码**（本轮只读）

---

## 交接卡（给下一个会话，先读这一节）

### 用途与边界

本文档是**可直接执行的改造任务书**，不是纯分析报告。下文 §4 的 P0/P1/P2 已按可独立提交的分片拆好，每片都给到「目标文件 + 行号 + 完成定义 + 验证方式」。接手时**不需要重新探索代码**，§8「已确认事实」列出了本轮已核实、不必重复搜索的结论。

### 任务板

> 状态列：`TODO` / `DOING` / `DONE`。每完成一片就更新此表。
> 分片原则：一片一提交、一片一验证；P0 三片互相独立，可并行。

| ID | 优先级 | 任务 | 目标文件:行 | 完成定义 | 状态 |
|---|---|---|---|---|---|
| T1 | P0 | 关闭/收窄备份，防凭据随备份外流 | `app/src/main/AndroidManifest.xml:10-18` | `aapt dump xmltree` 显示 `allowBackup=false`；或 `dataExtractionRules` 排除 `datastore/` 且实测备份不含该文件 | DONE (db9fc6b) |
| T2 | P0 | 清除凭据类日志 | `WeiboApiService.kt:161,184`；`LoginScreen.kt:169`；`KtorClientFactory.kt:50-57` | `adb logcat` 全流程无 `SUB=`/`st=`/`user_token`/`preview=` | DONE (88a1607) |
| T3 | P0 | 登出清 WebView Cookie（含换号） | `AuthRepository.kt:218-220`；`ProfileScreen.kt:111`；`MatterNavGraph.kt:98-103` | 登出后重进登录页不自动登录；`run-as` 查 WebView Cookies 库无 `SUB` | DONE (19edc37) |
| T4 | P0 | 登录 Cookie 池去重 + 优先级取值 | `LoginScreen.kt:164-166,177-184,261-268`；`AuthRepository.kt:85-96` | 新单测：旧账号 SUB 在前、新账号 SUB 在后 → 只保留新账号 | DONE (efde9a6) |
| T5 | P1 | Cookie 注入集中化（Ktor plugin） | `KtorClientFactory.kt:27-65` + `WeiboApiService.kt` 全量 40+ 处 | 业务方法不再出现 `header("Cookie"`；全接口手工回归通过 | DONE (eac10c3) |
| T6 | P1 | 会话失效统一收口（SessionExpiredBus） | 新增 `core/session/`；`WeiboApiService.kt:416,465,921,1010,2469,2598`；`MessageApi.kt:119`；`FavoritesApi.kt:98-102`；`MatterNavGraph.kt` | 手动改坏 Cookie 后，任一页面首次失败即触发统一失效流程（单一文案 + 跳登录） | DONE (7edd020) |
| T7 | P1 | 会话探活（进前台/冷启动一次） | `AuthRepository.kt`（复用 `verifyConfig`）；`MatterNavGraph.kt` | 有效会话不误报；失效会话进入即被识别，不产生重复请求 | DONE (3f4d6a6) |
| T8 | P1 | `getEffectiveCookie` 拆「必登录 / 可游客」 | `WeiboApiService.kt:105-124` + 各调用点 | 失效会话在被降级为游客前先上报失效；公开接口仍可用 | DONE (b3e2edb) |
| T9 | P1 | `clearSession` 不再误清偏好 | `AuthPreferences.kt:192-196` | 登出后 `is_onboarding_completed` / 分组键保留 | DONE (f0562a3) |
| T10 | P2 | 凭据加密（Keystore + AES/GCM） | 新增 `CredentialCipher` 接口 + Android 实现；改 `AuthPreferences.kt` | datastore 文件内 `sub/subp/raw_cookie` 为密文；旧明文自动迁移 | DONE (b761e99) |
| T11 | P2 | 下线密码登录，或补 `prelogin`+RSA | `WeiboApiService.kt:325-384`；`LoginScreen.kt:131-136` | 二选一：Tab 移除；或完整握手可用 | DONE (a28bb34) |
| T12 | P2 | 清理死字段 / WebView 加固 | `UserSession.kt:10-12`；`LoginScreen.kt:157-209` | 无未使用字段；URL 白名单 + SSL 错误取消 | DONE (8e7b682) |

**编号对照**（§4 用 S/A/D 编号，交接卡用 T 编号，二者等价）：

| 交接卡 | §4 | 内容 |
|---|---|---|
| T1 | S1 | 备份 |
| T2 | S2 | 日志 |
| T3 | S3 | 登出清 WebView |
| T4 | S4 + §2.4 | 换号残留 + Cookie 去重（串号） |
| T5 | A1 | 集中注入 |
| T6 | A2 | 失效收口 |
| T7 | A4 | 探活 |
| T8 | A3 | 游客降级拆分 |
| T9 | A6 | clearSession 精化 |
| T10 | D1 | 加密 |
| T11 | A5 | 密码/短信链路 |
| T12 | D3 + D4 | WebView 加固 + 死字段 |
| — | D2 / D5 | 多账号（暂不做） / KMP 平台边界（贯穿所有片） |

### 建议执行顺序

1. **第一批（安全，1 天内可全部完成）**：T1 → T2 → T3 → T4。
   这四片不动网络层结构，风险最低，且能立刻把「高危」清零。
2. **第二批（鉴权健壮性）**：T9 → T6 → T8 → T7。
   先让 `clearSession` 正确，再做失效收口，最后加探活；顺序反了会在调试期反复误登出。
3. **第三批（结构）**：T5。
   必须**先删手写 Cookie 再装 plugin**，不能叠加（见下方「已知坑」）。
4. **第四批（纵深防御）**：T10 → T11 → T12。

### 关键改动指引（避免走弯路）

**T1** —— `res/xml/` 目录当前**不存在**，若走 `dataExtractionRules` 方案需要新建。最省事且足够的是直接 `allowBackup="false"`；如果产品要求保留「换机恢复分组偏好」，则用 rules 只排除凭据：

```xml
<!-- res/xml/data_extraction_rules.xml -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="file" path="datastore/" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="file" path="datastore/" />
    </device-transfer>
</data-extraction-rules>
```

并在 `<application>` 上加 `android:dataExtractionRules="@xml/data_extraction_rules"`（Android 12+）与 `android:fullBackupContent="@xml/backup_rules"`（11-）。注意 `datastore/` 目录同时可能含 `theme_pref`、`media_pref`，若不想连偏好一起排除，改为按文件名 `datastore/matter_auth_pref.preferences_pb` 排除。

**T2** —— `app/build.gradle.kts:115-118` 已开启 `buildConfig = true`，可直接用 `BuildConfig.DEBUG`（import `org.kazusa.matter.BuildConfig`）。`WeiboApiService.kt:184` 的 `preview=` 必须整段删除，不要改成 `take(0)` 留坑。

**T3** —— **不要**在 `AuthRepository` 里直接 `import android.webkit.CookieManager`：`data` 层是跨平台候选，按 `docs/kmp-cmp-guidelines.md` 与 AGENTS.md 必须走接口注入。建议新增：

```kotlin
// core/session/WebSessionCookieStore.kt（纯 Kotlin 接口，KMP 友好）
interface WebSessionCookieStore { suspend fun clearAll() }
```

Android 实现在 `core/platform/` 下用 `CookieManager.getInstance().removeAllCookies { it.flush() }`，Koin 里绑定。同时**统一登出入口**：`ProfileScreen.kt:111` 现在绕过 `AuthRepository` 直接调 `authPreferences.clearSession()`，必须改成 `authRepository.logout()`，否则 T3 在个人中心路径上不生效。

**T4** —— 把 `cookiePool` 的构造从 UI 里抽成纯函数（可单测），规则：

- 先按域名优先级取**第一个非空的 `SUB`**（不要拼接多个域的 SUB）；
- 全池按 key 去重，同名以高优先级域为准；
- `verifyConfig` 与 `saveSession` 使用**同一份**去重结果，避免「验证的是 A、存下的是 B」。

`AuthRepository.kt:90` 现在只要 `cookieString.contains("SUB=")` 就原样使用整串，这正是串号根因。

**T5（已知坑，务必先读）** —— Ktor 的 `defaultRequest` 注入 `Cookie` 后，业务方法里残留的 `header("Cookie", …)` 会产生**两个 Cookie 头**，服务端通常只取第一个，行为随引擎实现漂移。执行顺序必须是：

1. 先加 plugin 但**默认关闭**（`enabled = false`）；
2. 逐文件删除手写 `header("Cookie"`，每删一处跑一次相关接口；
3. 全删完后打开 plugin。

**T6** —— `SessionExpiredBus` 只需 `MutableSharedFlow<Unit>(extraBufferCapacity = 1)`，注意：
- 游客会话（`session.uid == "guest"`）**不要**触发登出；
- 写操作路径已有 `WeiboWriteSession` 的 401 语义，Bus 只负责 UI 与导航，不要重复提示；
- 不要在 `WeiboApiService` 里直接依赖导航，保持 `core/network` 无 UI 依赖。

### 验证命令

```powershell
# 单测（改动后必跑；若 UP-TO-DATE 说明复用结果，不能宣称重新执行）
./gradlew.bat :app:testDebugUnitTest

# 编译
./gradlew.bat :app:assembleDebug

# 相关既有测试（改动命中时重点看）
#   core/network/WeiboWriteSessionTest.kt
#   core/network/FavoritesApiTest.kt
#   core/network/MessageApiTest.kt
#   core/di/AppModuleTest.kt / ViewModelInjectionTest.kt
```

新增测试建议（T4/T9 必须有）：
- `AuthCookieSanitizerTest`（纯函数，覆盖 §2.4 的串号场景）；
- `AuthPreferencesTest`（`clearSession` 保留 onboarding/分组键，用临时目录 DataStore）。

### 硬约束（不要做）

- 不要引入 One 的 `api.weibo.cn/2/` native 签名方案（`s`/`cum`/`X-Shanhai-Pass`），Matter 的 H5 路线是刻意选择。
- 不要为「安全」把 `WeiboApiService` 改造成 OkHttp 拦截器技术栈；现有 Ktor + OkHttp 引擎保留，加 Ktor plugin 即可。
- 不要在 `core/datastore` 直接 import `android.webkit.*` / `android.security.*`，全部走接口（KMP 规范）。
- 不要批量补 `@Stable` / `@Immutable`，不要改 ViewModel 的 Snapshot State 用法。
- 不要顺手做「多账号」——需求未确认，且会牵动 DataStore 分区与全部仓储的生命周期。

---

## 0. 结论速览

**一句话判断：凭据「存」的部分不合格（三处硬伤），凭据「用」的部分设计方向正确但收口不足。**

Matter 不走 OAuth、不持有 appkey，而是**直接持有微博网页端会话 Cookie（`SUB` / `SUBP`）**——这是**长期、全权、可写入**的账号凭据（可发博、私信、改资料），泄露后果等同于账号被完全接管，风险等级高于任何短期 `access_token`。因此它的存储保护标准应当**高于**一般 token 缓存。

| # | 问题 | 位置 | 类型 | 严重度 |
|---|---|---|---|---|
| 1 | 凭据全程明文落盘，无 Keystore 绑定 | `core/datastore/AuthPreferences.kt:17-40` | 安全 | 高 |
| 2 | `allowBackup="true"`，无 `dataExtractionRules` 排除 datastore | `AndroidManifest.xml:12` | 安全 | 高 |
| 3 | 登出/换号不清 WebView Cookie，且登录页会自动抓旧 SUB | `ui/login/LoginScreen.kt:164-197`、`AuthPreferences.kt:192` | 安全 + 功能 | 高 |
| 4 | `/api/config` 响应（含 `st`/`uid`/`user_token`）前 200 字符进 logcat | `WeiboApiService.kt:184` | 安全 | 中 |
| 5 | Ktor `LogLevel.INFO` 无 `BuildConfig.DEBUG` 开关，URL 含 uid/会话 ID 进日志 | `KtorClientFactory.kt:50-57` | 安全 | 中 |
| 6 | 无统一鉴权注入：40+ 处手写 `header("Cookie", …)`，新增接口易漏 | `WeiboApiService.kt` 全文 | 健壮性 | 中 |
| 7 | 会话失效无统一收口：`-100` 只在各页面拼文案，不触发登出/探活 | `WeiboApiService.kt:416,465,921,1010` 等 | 鉴权可靠性 | 中 |
| 8 | 会话失效时**静默降级为游客 Cookie**，掩盖真实登录态 | `WeiboApiService.kt:105-124` | 鉴权可靠性 | 中 |
| 9 | 短信/密码登录缺 `prelogin` + RSA，实际大概率不可用 | `WeiboApiService.kt:211-384` | 功能 | 中 |
| 10 | `clearSession()` 用 `prefs.clear()`，把 onboarding/分组偏好一起清空 | `AuthPreferences.kt:192-196` | 功能 | 低 |
| 11 | 登录时 Cookie 串未去重，旧账号 `SUB` 可能排在前面 | `AuthRepository.kt:85-90`、`LoginScreen.kt:177-184` | 功能（换号串号） | 中 |
| 12 | `UserSession.token` / `gsid` 从未写入，是死字段 | `AuthRepository.kt:117-128` | 代码卫生 | 低 |
| 13 | 无多账号能力（README 声称的隔离只有单会话版） | 全仓 | 功能 | 低 |

**最关键的三件事**（改完即可把「高危」清零）：

1. `android:allowBackup="false"`（或加 `dataExtractionRules` 只排除 `datastore/`）。
2. 登出与换号时 `CookieManager.getInstance().removeAllCookies { flush() }`。
3. 删掉 `verifyConfig` 的响应预览日志、给 Ktor Logging 加 `BuildConfig.DEBUG` 判断。

**不建议做的事**：不要为了「安全」去抄 One 的 `api.weibo.cn/2/` 官方私有协议——它的鉴权依赖 `libSecShell.so` / `libwbutil.so` 里的 native 签名（`s` / `cum` / `X-Shanhai-Pass`），Matter 走 H5 路线是**正确且唯一可行**的选择（详见 §3.5）。

---

## 1. 凭据资产清单

| 资产 | 形态 | 生命周期 | 权限 | Matter 是否存在 |
|---|---|---|---|---|
| `SUB` | Cookie，约 30 字符 | 数周~数月，服务端可续 | **读+写全权** | ✅ 落盘 |
| `SUBP` | Cookie | 同上，与 SUB 配对 | 同上 | ✅ 落盘 |
| `_T_WM` / `XSRF-TOKEN` / `MLOGIN` 等 | Cookie | 会话级/短期 | 辅助 | ✅ 混在 `raw_cookie` |
| `st` | `/api/config` 下发 | 单会话，随 Cookie 变 | 仅写操作 | ⚠️ 仅内存（`WeiboWriteSession`） |
| 游客 `SUB` | `genvisitor` 下发 | 短期 | 只读 | ⚠️ 仅内存缓存 |
| 账号密码 | 明文表单字段 | 瞬时 | — | ⚠️ 只在登录请求中出现，不落盘（好） |
| appkey / secret | — | — | — | ❌ 不存在（不走 OAuth） |
| `uid` / `screen_name` / 头像 | 明文 DataStore | 长期 | 隐私（非凭据） | ✅ 落盘 |

结论：Matter 的凭据集合**收敛得很干净**——没有多余 secret、没有签名密钥、密码不落盘。问题不在「存了什么」，而在「怎么存」和「怎么清」。

---

## 2. Matter 现状解剖

### 2.1 存储层：单点明文 DataStore

```17:27:app/src/main/java/org/kazusa/matter/core/datastore/AuthPreferences.kt
val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "matter_auth_pref")

class AuthPreferences(private val context: Context) {
    private object Keys {
        val UID = stringPreferencesKey("uid")
        val SUB = stringPreferencesKey("sub")
        val SUBP = stringPreferencesKey("subp")
        ...
```

- 落盘路径：`/data/data/org.kazusa.matter/files/datastore/matter_auth_pref.preferences_pb`，**纯 protobuf 明文**。
- `gradle/libs.versions.toml` 只有 `androidx.datastore-preferences 1.2.1`，**没有** `security-crypto` / Tink / SQLCipher。
- 主源码中 `Keystore|Cipher|AES|SecretKey|encrypt` **0 命中**（`Grep` 全仓确认）。
- 唯一写入口 `saveSession()`，唯一清入口 `clearSession()`，层级干净（这一点是加分项，改造只需动一个文件）。

**客观评价**：在 Android 沙箱模型下，「其他应用读不到」这一层是成立的；明文 DataStore 的真实增量风险来自 **备份通道** 与 **root/调试设备**，而不是「同机其他 App」。真正该修的是 #2（备份）而不是「必须上加密」。但考虑到这是**长期全权 Cookie**，加密 + Keystore 绑定仍是值得做的纵深防御。

### 2.2 会话模型与 Cookie 拼装

```30:54:app/src/main/java/org/kazusa/matter/core/datastore/UserSession.kt
    val cookieHeader: String
        get() {
            if (rawCookie.isNotBlank()) {
                ...
                if (sub.isNotBlank()) cookieMap["SUB"] = sub
                if (subp.isNotBlank()) cookieMap["SUBP"] = subp
                return cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
```

- 读取时用 `sub`/`subp` 覆盖 `rawCookie` 里的同名项 —— **这是正确的「权威凭据」设计**，能挡住 `rawCookie` 里的过期/游客 SUB。
- 但 `sub`/`subp` 本身的来源有问题（见 §2.4「串号风险」）。

### 2.3 网络层鉴权注入：已收敛到端点层

- 技术栈是 **Ktor + OkHttp 引擎**。`defaultRequest` 统一设 UA/Accept/`X-Requested-With`，
  Cookie 由 `CookieInjection.kt` 的 `CookieAuth` 插件按域名兜底注入（`WEIBO_CONTENT_HOSTS` 四个域）。
- 端点与凭据语义集中在 `WeiboEndpoint.kt`：
  - `WeiboHost`：`WEB` / `MOBILE` / `OPEN_API` / `PASSPORT`，业务方法只写路径，host 与默认 `Referer` 由这里给出；
  - `WeiboAuth`：`Session(cookie)`（读）/ `Write(credentials)`（网页域写，Cookie + `X-XSRF-TOKEN` + `Origin` 三件套）/ `Guest`（交给插件兜底）；
  - `weiboGet` / `weiboPostForm` / `weiboPostJson`：统一入口，调用点不再手写 host、XSRF 三件套与重复头。
- 一次请求现在长这样：

```436:443:app/src/main/java/org/kazusa/matter/core/network/WeiboApiService.kt
            val response = client.weiboGet(
                endpoint = webEndpoint(
                    path = route.path,
                    auth = WeiboAuth.Session(effectiveCookie),
                    referer = "https://weibo.com/mygroups?gid=$gid"
                )
            ) {
                parameter("list_id", gid)
```

- **收益**：`WeiboApiService` 里 29 个请求点全部走端点层，XSRF 三件套只写一次；
  主页类读接口共用的「432 → 换游客凭据重试」脚手架收敛进 `weiboGetWithVisitorRetry`。
- **仍然要注意**：`WeiboAuth.Session("")` 会被当作游客——新增读接口时若把「登录接口」误声明成
  `Session(空)`，服务端按游客处理返回 `-100`，错误仍会被误判为「接口不可用」。
  这一层已经把风险从「忘塞 Cookie」降到「声明错凭据类型」，但声明仍需人对着语义写。
- **没有**自实现 `sig`/`md5`/`hmac`（正确，H5 端不需要）。

### 2.4 四条登录链路

| 链路 | 实现 | 实际可用性 | 备注 |
|---|---|---|---|
| ① 网页授权（推荐） | WebView 打开 `passport.weibo.com/sso/signin`，从 `CookieManager` 抓 `SUB` | ✅ 可行 | 唯一能过人机验证（极验/易盾）的路径 |
| ② 短信登录 | `login.sina.com.cn/sso/v2/sms/send` + `/sso/v2/login?scode=` | ⚠️ 大概率失效 | 缺 `prelogin`/`servertime`/`nonce`，且实测常见响应是 `need mfa` |
| ③ 密码登录 | 同端点 `password=` 明文 | ⚠️ 大概率失效 | 新浪 SSO 正常要求 `prelogin` 取 `pubkey` 后 RSA 加密密码；明文提交会被风控拦 |
| ④ Cookie 导入 | 用户粘贴整串 | ✅ 可行 | 最「可靠」但也最容易被用户误用（粘错账号） |

**代码证据（密码链路）**：

> 直连的密码 / 短信链路代码已不存在。现在登录只剩 WebView 授权
> （`LoginScreen.kt:239` 打开 `passport.weibo.com/sso/signin`），全仓
> `sso/v2/login` / `prelogin` / `pubkey` 均 0 命中——说明这条链路从未完成过
> 完整的 SSO 握手，最终选择直接下线，而不是补齐它。

**串号风险（真实缺陷）**：`loginWithCookie` 保存的 `rawCookie` 是**未去重**的整串，而 `sub`/`subp` 来自 `parseCookie()` 的 `map`——后写覆盖先写：

```85:96:app/src/main/java/org/kazusa/matter/data/repository/AuthRepository.kt
    suspend fun loginWithCookie(cookieString: String): ApiResult<UserSession> {
        val cookies = parseCookie(cookieString)
        val sub = cookies["SUB"].orEmpty()
        val subp = cookies["SUBP"].orEmpty()
        val formattedCookie = if (cookieString.contains("SUB=")) cookieString else ...
        return when (val result = apiService.verifyConfig(formattedCookie)) {
```

`cookiePool` 的拼接顺序是 `m.weibo.cn → weibo.cn → weibo.com → passport → sina.cn → 当前 URL`。若 WebView 里残留旧账号的 `SUB`，而新账号的 `SUB` 只在靠后的域，`verifyConfig` 携带的 `formattedCookie` **包含两个 SUB**，服务端按第一个取值 → **验证成旧账号，并把旧账号的 SUB 写成权威凭据**。这既是安全问题也是换号失败的直接原因（与 §2.7 的 Cookie 不清理互为因果）。

### 2.5 失效与错误码处理矩阵

微博 H5 端的「鉴权失效」不是一个状态码，而是一组信号。Matter 的处理现状：

| 信号 | 含义 | Matter 处理 | 评价 |
|---|---|---|---|
| `ok: -100` | 未登录/会话失效 | 各页面自行拼中文文案 | ⚠️ 无统一收口，不触发登出/重新探活 |
| HTTP `432` | 无有效 Cookie 被风控拒绝 | `getEffectiveCookie` 兜底游客 | ⚠️ 掩盖失效 |
| HTTP `429` | 频控 | 写路径有 60s 冷却（`WeiboWriteSession`） | ✅ 设计良好 |
| `ok: 0` + 「操作频繁」 | 写频控 | 识别文案并进冷却 | ✅ |
| `need mfa` | 人机验证 | 提示切网页授权 | ✅ 合理 |
| `/api/config` `data.login != true` | 登录态无效 | 返回 401 + 提示 | ✅ |
| `error_code` / `errno` | 业务错误 | 部分接口解析，部分丢弃 | ⚠️ 不一致 |

```413:417:app/src/main/java/org/kazusa/matter/core/network/WeiboApiService.kt
                } else {
                    ApiResult.Error(
                        code = body.ok,
                        message = if (body.ok == -100) "登录凭证已失效(-100)，请点击下方退出并重新登录" else "获取微博动态失败(ok=${body.ok})"
                    )
                }
```

**问题本质**：`-100` 被当成「某页面加载失败」而不是「全局会话失效」。用户在 5 个页面会看到 5 种文案，且**没有任何一处自动登出或触发重新鉴权**。正确做法是发出一次全局 `SessionExpired` 事件，由导航根统一处理（跳登录页 / 清内存态 / 提示重新授权）。

`WeiboWriteSession` 是**全仓鉴权健壮性最好的一处**：`st` 与 Cookie 成对缓存、写操作串行、频控冷却、`invalidate()` 主动失效。这套模式应该被上推成通用的「会话闸门」。

### 2.6 登出 / 换号

```218:220:app/src/main/java/org/kazusa/matter/data/repository/AuthRepository.kt
    suspend fun logout() {
        authPreferences.clearSession()
    }
```

```192:196:app/src/main/java/org/kazusa/matter/core/datastore/AuthPreferences.kt
    suspend fun clearSession() {
        context.authDataStore.edit { prefs ->
            prefs.clear()
```

- ✅ 内存态清理做得不错：`MatterNavGraph` 在 `session?.uid` 变化时清 `likeRepository` / `followRepository` / `PostDetailCache`；`LikeRepository` / `FollowRepository` 用会话代次挡住旧账号晚到响应。
- ❌ **WebView Cookie 完全没清**（全仓 `removeAllCookies` / `clearCookies` **0 命中**）。
- ❌ `prefs.clear()` 把 `is_onboarding_completed`、`last_group_id`、`hidden_group_ids`、`group_custom_order` 一并清掉——登出后用户会被重新引导，分组偏好丢失。
- ❌ 没有服务端登出（`passport.weibo.com/sso/logout`），服务端会话仍存活。对第三方客户端这是可接受的取舍（避免误杀网页端会话），但应明确记录为设计决策。

### 2.7 WebView 与 Cookie（最严重的一处）

```164:196:app/src/main/java/org/kazusa/matter/ui/login/LoginScreen.kt
                                            val cookieManager = CookieManager.getInstance()
                                            cookieManager.setAcceptCookie(true)
                                            cookieManager.setAcceptThirdPartyCookies(this, true)

                                            fun checkAndExtractLogin(currentUrl: String?) {
                                                ...
                                                val cookiePool = buildString {
                                                    append(cookieManager.getCookie("https://m.weibo.cn").orEmpty()).append("; ")
                                                    ...
                                                }

                                                val hasSubCookie = cookiePool.contains("SUB=") && !cookiePool.contains("SUB=\"\"")
```

问题链条：

1. 登录成功后，**完整登录 Cookie 留在系统 WebView 的 Cookies 数据库里**（明文 SQLite，备份可及）。
2. 登出只清 DataStore → WebView 仍是登录态 → **"已退出"是假象**。
3. 下次进入登录页（尤其想换号时），`checkAndExtractLogin` 会在页面加载后立刻抓到旧 `SUB` 并**自动调用 `loginWithCookie`** → 直接登回旧账号，用户无法换号。
4. 叠加 §2.4 的未去重问题，还有概率把两个账号的 SUB 一起送进 `verifyConfig`。

附加问题：登录 WebView 开启 JS/DOM storage，但**没有** `shouldOverrideUrlLoading` 白名单、**没有** `onReceivedSslError` 处理、**没有** `setSafeBrowsingEnabled`。因为**没有** `addJavascriptInterface`，JS 桥注入风险不存在（这点是好的）。

### 2.8 日志 / 备份 / 其他泄密面

| 面 | 现状 | 判断 |
|---|---|---|
| `/api/config` 响应预览 | `Log.d("WeiboAuth", "… preview=${rawText.take(200)}")`，响应含 `st` / `uid` / `user_token` | ❌ 真 token 泄露 |
| 游客 Cookie 前缀 | `Log.d("WeiboAuth", "fetchVisitorCookie success: ${cookie.take(25)}...")` | ⚠️ 低危 |
| Ktor Logging | `LogLevel.INFO` 恒定开启，release 不清 | ⚠️ URL 含 uid/关键词 |
| 登录页 URL | `Log.d("WeiboLogin", "Current url: $currentUrl")` | ⚠️ URL 可能含 ticket |
| 崩溃上报 / 第三方统计 | `Crashlytics/Sentry/Firebase/Analytics` **0 命中** | ✅ 干净 |
| 分享 Intent | 只拼 `weibo.com/$uid/$id` | ✅ 无凭据 |
| 剪贴板 / 通知 | 未用于凭据 | ✅ |
| 明文 HTTP | 未配 `cleartextTrafficPermitted`（Android 9+ 默认禁） | ✅ |
| APK 签名密钥 | CI secret 注入，未入库 | ✅ |
| `allowBackup` | `true`，无 `dataExtractionRules` / `fullBackupContent` | ❌ 见下 |

**关于备份的准确说法**（避免过度报警）：
- Android 11 及以下：`adb backup` 可导出应用数据（需用户确认，且需 `allowBackup=true`）。
- Android 12+：`adb backup` 已从平台移除，但 **Google 云备份 / 厂商换机迁移仍然生效**，且默认包含 `filesDir`（DataStore 就在 `files/datastore`）。
- 因此「凭据随备份离开设备」在**所有版本都成立**，只是通道从 adb 变成了云备份/D2D。

---

## 3. 与微博各端鉴权机制的对照

### 3.1 四条鉴权通道，Matter 只能走第 1 条

| 通道 | 端点 | 鉴权要素 | Matter 能否用 |
|---|---|---|---|
| **① H5 / PWA** | `m.weibo.cn`、`weibo.com/ajax` | Cookie `SUB`/`SUBP` + 写操作 `st` / `X-XSRF-TOKEN` + `Referer` + `MWeibo-Pwa` | ✅ **当前路线** |
| ② 开放平台 | `api.weibo.com/2/*` | `access_token` 参数（OAuth2） | ❌ 需注册 appkey。实测带网页 Cookie 访问 `friendships/groups.json` 返回 403 `auth by Null spi`，分组读写已全部改走 `weibo.com/ajax/feed` |
| ③ 官方私有 | `api.weibo.cn/2/*` | `aid` + `gsid` + `s` + `cum` + `X-Validator` + `X-Shanhai-Pass`（**native 签名**） | ❌ 不可用（需逆向 so） |
| ④ passport 游客 | `passport.weibo.com/visitor/genvisitor` | 无凭据换游客 `SUB` | ✅ 仅作 432 兜底 |

### 3.2 写操作的鉴权链（Matter 做得对的部分）

```200:210:app/src/main/java/org/kazusa/matter/core/network/WeiboApiService.kt
        writeSession.execute(cookie = effectiveCookie, action = "点赞") { credentials ->
            ...
                append("st", credentials.token)
            ...
                header("X-XSRF-TOKEN", credentials.token)
```

`st` 与下发它的 Cookie **必须成对使用**——这一点 Matter 用 `WeiboWriteCredentials(sourceCookie, cookieHeader, token)` 表达得非常明确，且合并了响应里的 `Set-Cookie`。这是整个鉴权实现里质量最高的设计，建议保留并作为其他接口的范本。

### 3.3 `weibo.com` 发布链路的 `XSRF-TOKEN`

发布微博走 `weibo.com/ajax/statuses/update`，需要从 Cookie 里取 `XSRF-TOKEN` 并做 **URL 解码**后放进 `X-XSRF-TOKEN` 头。Matter 已实现（`WeiboApiService.kt:1672-1677`）。这里有个易错点值得记一笔：`XSRF-TOKEN` 的 cookie 值是 percent-encoded，直接回填会被 403。

### 3.4 失效语义的差异

| 端 | 失效信号 | 官方做法 |
|---|---|---|
| H5 | `ok: -100` + `url` 字段指向登录页 | 跳登录页 |
| web ajax | `ok: -100` / `X-Error-Code` 头 | 跳登录页 |
| 官方私有 | `errno ∈ {21317, 21327, 21332, -100, 10006, 5011031072}`、`X-Auth-Gsid` 头 | 提示 + 重新登录 |

One 的实现（`defpackage/r3f.java:96-141`）对上述 errno 全部做了 switch，命中后弹「token 过期」并引导重登；`vbb.java:29-52` 是那个对话框。**没有静默 refresh**——因为微博官方链路根本没有 refresh_token（`s9f` 只有 `sut` + `gsid`）。

这条结论对 Matter 很重要：**"实现自动刷新 token" 是个伪需求**。H5 端能做的只有：
1. 用现有 Cookie 周期性打 `/api/config` 探活；
2. 失效后引导重新走 WebView 授权（拿到新 `SUB`）。

### 3.5 One 的可借鉴 / 不可借鉴清单

**可借鉴（与协议无关的工程模式）**

| One 的做法 | 证据 | 对 Matter 的启示 |
|---|---|---|
| 鉴权集中在一个 OkHttp Interceptor | `defpackage/q6f.java:59-218` | 对应到 Ktor 就是一个 `createClient` 插件，消灭 40+ 处手写 Cookie |
| 准备一个"裸 client"给非鉴权请求 | `bx7.java:162`（`new q6f(false,false,false)`） | 游客/公开接口应走独立 client，避免会话 Cookie 误带 |
| `X-Validator` 取回 → 持久化 → 每请求回带 | `q6f.java:176-215`（MMKV `xvalidator`） | Matter 无此项（H5 端不需要），但「服务端下发的长期票据要留存并回带」这个模式值得记住 |
| 登出清三处：DB 行 + `cache_at_user_<uid>` + `wb_ab_kv_<uid>`，再 `removeAllCookies` | `lj9.java:24-43`、`zge.java:15-50` | 直接对标 Matter 的 #3 缺陷 |
| 登录后**先 `removeAllCookies` 再逐条重灌** | `zge.java:23-47` | 解决「WebView 残留旧账号」的标准做法 |
| 换账号用 `sessionGeneration` 挡旧响应 | Matter 已有（`LikeRepository`） | 保持 |

**不可借鉴**

| One 的做法 | 为什么不能抄 |
|---|---|
| `api.weibo.cn/2/` 私有接口 | 需要 `libSecShell.so`(`s`)、`libwbutil.so`(`cum`)、`lib*.so`(`X-Shanhai-Pass`) 三个 native 签名 + `aid` 设备注册 + 极验 `20062` 重放，工程门槛极高且随版本失效 |
| 用 `mz3`（`c+47` 折叠）"混淆" appkey | 属于伪加密，字符串池里可还原（反编译即证）；Matter 不做这件事反而更干净 |
| 明文存 `pass`（密码）字段 | One 的 `oge.java:49` 把密码写进了账号表，是**反面教材** |
| 账号表明文 SQLite | 与 Matter 的明文 DataStore 同病，不能作为「安全参照」 |

**结论**：One 在**凭据编排流程**（集中注入、换号清理、失效提示）上比 Matter 成熟；但在**凭据静态保护**上和 Matter 一样是明文。所以「抄流程，不抄协议」。

---

## 4. 缺口清单（按修复优先级）

### P0 —— 安全硬伤，改动量 < 1 天

| 编号 | 问题 | 修法 | 影响面 |
|---|---|---|---|
| S1 | 凭据随备份离开设备 | `android:allowBackup="false"`；若要保留备份，加 `android:dataExtractionRules` + `fullBackupContent` 排除 `datastore/matter_auth_pref*` | Manifest 1 处 |
| S2 | `st`/`uid` 进 logcat | 删除 `WeiboApiService.kt:184` 的 `preview=`；`161` 的 Cookie 前缀降级为长度；`KtorClientFactory` 的 `Logging` 用 `if (BuildConfig.DEBUG)` 包裹 | 3 处 |
| S3 | 登出后 WebView 仍登录 | `AuthRepository.logout()` 里加 `CookieManager.getInstance().removeAllCookies { it.flush() }`；`MatterNavGraph` 的 uid 变化分支同样处理 | 2 处 |
| S4 | 换号时 WebView 残留旧 SUB | 登录页创建 WebView 前先 `removeAllCookies`；并把 `cookiePool` 从"多域拼接"改为"**按优先级取第一个非空 SUB，且全池去重**" | `LoginScreen.kt` 1 处 |

### P1 —— 鉴权健壮性，改动量 1~3 天

| 编号 | 问题 | 修法 |
|---|---|---|
| A1 | Cookie 注入分散 | ✅ 已完成。`CookieInjection.kt` 的 `CookieAuth` 插件按 `WEIBO_CONTENT_HOSTS` 兜底注入；业务方法统一走 `WeiboEndpoint.kt` 的 `weiboGet` / `weiboPostForm` / `weiboPostJson`，凭据类型在 `WeiboAuth` 里显式声明 |
| A2 | `-100` 无收口 | 新增 `SessionExpiredBus`（`SharedFlow<Unit>`）；所有 `ok == -100` / `login != true` 处 `emit`；导航根订阅后统一清内存态 + 跳登录页 + `Toast/提示`。文案从各页面收敛为 1 份 |
| A3 | 静默游客降级 | `getEffectiveCookie()` 拆成两条路径：`requireSession = true` 的接口**不再**回落游客（失败就报失效）；只有明确公开的接口走游客 |
| A4 | 启动不探活 | App 进前台时打一次 `/api/config`（复用 `verifyConfig`），`login != true` 直接置 `SessionExpired` |
| A5 | 密码/短信链路大概率不可用 | 二选一：**（推荐）** 下线密码登录 Tab，只留网页授权 + Cookie 导入；或补 `prelogin` → `pubkey` → RSA → `nonce/servertime` 完整握手 |
| A6 | `clearSession()` 误清偏好 | `prefs.remove(Sub/Subp/Gsid/RawCookie/Token/Uid/ScreenName/Avatar/…)` 逐键删除，保留 `is_onboarding_completed` 与分组键 |

### P2 —— 纵深防御，可与 KMP 迁移一起做

| 编号 | 问题 | 修法 |
|---|---|---|
| D1 | 明文落盘 | 抽象 `CredentialCipher` 接口（`encrypt/decrypt: String`），Android 实现用 **AndroidKeyStore + AES/GCM**（密钥不可导出、`setUserAuthenticationRequired(false)`）；DataStore 只在写入前加密 `sub/subp/rawCookie`。注意 `androidx.security:security-crypto` 的 `EncryptedSharedPreferences` 自 1.1.0-alpha07 起已标记 deprecated，且只支持 SharedPreferences，不如自封装 GCM 干净 |
| D2 | 无多账号 | 若要支持：`matter_auth_pref` 改为 `session_<uid>` 分区 + `current_uid` 指针；切换时清 WebView Cookie、清内存仓库（现有 `sessionGeneration` 可复用）。**不建议**在没有需求时提前做 |
| D3 | WebView 加固 | `shouldOverrideUrlLoading` 白名单（`*.weibo.com` / `*.weibo.cn` / `*.sina.cn` / `*.sina.com.cn`）；`onReceivedSslError` 直接 `cancel()`；`setSafeBrowsingEnabled(true)`；用完即 `destroy()` |
| D4 | 死字段 | 删 `UserSession.token` / `gsid`，或真正接入（`gsid` 在 H5 路线下无意义） |
| D5 | KMP 合规 | 加密/WebView/备份都属平台能力，按 `docs/kmp-cmp-guidelines.md` 放到接口后注入，不要让 `core/datastore` 直接依赖 `Context` |

---

## 5. 目标架构（改完后的形状）

```
                 ┌─────────────────────────────────────┐
   UI ──事件──▶  │ ViewModel                            │
                 └───────────────┬─────────────────────┘
                                 │
                 ┌───────────────▼─────────────────────┐
                 │ Repository                           │
                 │  · AuthRepository  (login/logout)     │
                 │  · SessionExpiredBus (SharedFlow)     │
                 └───────────────┬─────────────────────┘
                                 │
        ┌────────────────────────▼─────────────────────────┐
        │ CredentialStore (接口)                            │
        │   └─ Android: DataStore + KeystoreAesGcmCipher    │
        └────────────────────────┬─────────────────────────┘
                                 │
        ┌────────────────────────▼─────────────────────────┐
        │ HttpClient (Ktor)                                 │
        │   · AuthPlugin：按 host 注入 Cookie / st          │
        │   · Logging：仅 BuildConfig.DEBUG                 │
        │   · SessionExpiredInterceptor：-100 → Bus         │
        └────────────────────────┬─────────────────────────┘
                                 │
                          weibo H5 / passport
```

关键点：**凭据只有一个出口（Plugin）、一个入口（CredentialStore）、一个失效信号（Bus）**。做到这三点，后面加多少接口都不会再漏鉴权。

---

## 6. 验收清单

安全项
- [ ] `aapt dump xmltree` 确认 `allowBackup=false`，或 `dataExtractionRules` 明确排除 datastore
- [ ] `adb logcat | grep -iE "sub=|st=|SUB=|user_token"` 无命中（debug 与 release 各跑一遍主流程）
- [ ] 登出 → 重新进入登录页 → **不自动登录**；`adb shell` 检查 WebView Cookies 库无 `SUB`
- [ ] 换账号 A→B→A，三次 `verifyConfig` 返回的 `uid` 与预期一致（验证 §2.4 串号修复）
- [ ] 用 `run-as` 或 root 设备确认 datastore 文件内 `sub` 已是密文（若做 T10）

鉴权项
- [ ] 会话失效（手动改坏 Cookie）后：任一页面首次失败即触发统一失效流程，而非 5 种文案
- [ ] 未登录状态访问公开接口（热搜/搜索）仍可用（游客兜底未被 A3 误伤）
- [ ] 写操作（赞/藏/关注/评论/发博）在会话失效后返回「请重新登录」且**不会**进 60s 冷却
- [ ] 连续点赞 5 次触发频控后，冷却期内不再发请求（现有 `WeiboWriteSession` 行为回归）
- [ ] 冷启动 → 直接进入时间线，不发多余的 `/api/config`（A4 探活只在有会话时一次）

---

## 7. 需要实测确认的项（本报告未验证）

1. **短信/密码登录是否真的不可用**：结论基于「全仓无 `prelogin`/`pubkey`」的静态推断，未真机验证；若实测可用，则 T11 降级为 P2。
2. ~~`api.weibo.com/2/friendships/groups.json` 用 Cookie 鉴权的成功率~~ 已实测：403 `auth by Null spi`，该分支已从分组读 / 写 / 排序中删除。
3. `-100` 之外是否还存在 `errno 21332/21327` 出现在 H5 接口（One 的样本来自 `api.weibo.cn/2/`，H5 端通常只有 `-100`）。
4. `st` 的有效期与「同一 Cookie 换 IP 后是否失效」——决定 `WeiboWriteSession` 的缓存是否够稳。
5. WebView Cookies 数据库在 Android 14+ 的落盘位置与备份包含情况（不同 OEM 有差异）。

---

## 8. 一句话总结

Matter 的鉴权**路线选对了**（H5 Cookie，不做 native 签名逆向），**写操作闸门做得比多数同类项目好**（`st` 成对缓存 + 串行 + 频控冷却），但**凭据的静态保护与生命周期收尾不合格**：备份可导出、日志可读、登出不彻底、失效靠各页面自觉。修掉 §4 的 S1~S4 与 A1~A4（即交接卡 T1~T9），这套凭据体系就可以称为「够安全」；其余项属于加固与产品能力，不影响当前可用性。

---

## 9. 已确认事实（不必重复探索）

本轮已逐项核实，下一会话可直接采信：

| 事实 | 核验方式 |
|---|---|
| 全仓无 `security-crypto` / Tink / SQLCipher | `libs.versions.toml` + 全仓搜索 |
| 主源码无 `Keystore` / `Cipher` / `AES` / `SecretKey` | 全仓搜索 |
| 全仓无 `removeAllCookies` / `clearCookies` / `flush()` | 全仓搜索 |
| 全仓无 `prelogin` / `pubkey` / `rsa` | 全仓搜索 |
| 全仓无 `refresh_token` / `reissue` | 全仓搜索 |
| 全仓无多账号（`switchAccount` / `accountList`） | 全仓搜索 |
| 无崩溃上报 / 无第三方统计 SDK | 全仓搜索 |
| `app/src/main/res/xml/` 目录不存在 | 文件系统确认 |
| `buildConfig = true` 已开启，可直接用 `BuildConfig.DEBUG` | `app/build.gradle.kts:115-118` |
| 唯一 WebView 在 `LoginScreen`，无 `addJavascriptInterface` | 全仓搜索 |
| `WeiboApiService` 是 Koin `single`，内部持有 `WeiboWriteSession` 缓存 | `core/di/AppModule.kt:66` |
| 登出有两条路径：`ProfileScreen` 直调 `AuthPreferences`、`TimelineViewModel` 走 `AuthRepository.logout()` | 调用点确认 |
| 换号内存态清理已存在于 `MatterNavGraph.kt:99-103` | 源码确认 |
| `-100` 仅产出 UI 文案，无任何登出/重试副作用 | 源码确认 |
| 网页分榜接口为 `GET /ajax/statuses/{entertainment,social,technology,life,sport,acg}`（带游客 SUB 即可用，纯匿名 `ok=-100`），热榜 `hot_band` 匿名可用，「我的」`mineBand` 需 SUB；`/ajax/statuses/` 下无同城榜端点 | 2026-10-27 实测 |
| 热搜保留 m 站：m 站一次 `container/getIndex` 提供 channels + sections + entries + 同城榜，网页端均缺 | 2026-10-27 实测对比 |

## 10. 关键文件清单

| 类型 | 文件 | 作用 |
|---|---|---|
| 凭据存储 | `core/datastore/AuthPreferences.kt` | 唯一读写入口（明文 DataStore） |
| 凭据模型 | `core/datastore/UserSession.kt` | `cookieHeader` 权威凭据拼装 |
| 登录编排 | `data/repository/AuthRepository.kt` | 四条登录链路的落地与登出 |
| 鉴权注入 | `core/network/WeiboEndpoint.kt` | `WeiboHost` / `WeiboAuth` / `WeiboEndpoint` 与 `weiboGet` / `weiboPostForm` / `weiboPostJson` 统一入口 |
| Cookie 兜底 | `core/network/CookieInjection.kt` | `CookieAuth` 插件按 `WEIBO_CONTENT_HOSTS` 注入 Cookie（游客凭据仍由 `getEffectiveCookie` 显式触发） |
| 端点与解析 | `core/network/WeiboApiService.kt`（2400+ 行） | 业务接口、`-100` 判定、432 游客重试 |
| 写操作闸门 | `core/network/WeiboWriteSession.kt` | `st` 缓存 + 串行 + 频控冷却（质量最好的一段，改造时勿动语义） |
| 网络装配 | `core/network/KtorClientFactory.kt` | UA / 日志 / 超时 |
| 私信鉴权 | `core/network/MessageApi.kt` | 独立 host、独立错误翻译 |
| 收藏鉴权 | `core/network/FavoritesApi.kt` | 复用 `writeSession` |
| 登录 UI | `ui/login/LoginScreen.kt` | WebView 抓 Cookie + 三个表单 Tab |
| 导航根 | `ui/navigation/MatterNavGraph.kt` | 会话变化时的内存态清理与路由分发 |
| 依赖装配 | `core/di/AppModule.kt` | `AuthPreferences` / `WeiboApiService` 单例 |
| Manifest | `app/src/main/AndroidManifest.xml` | `allowBackup` / 权限 |

## 11. 变更记录

| 日期 | 变更 | 说明 |
|---|---|---|
| 2026-10-24 | 初版 | 静态审计（Matter 主源码 + `D:/Project/One` jadx 反编译产物对照），未修改业务代码 |
| 2026-10-24 | 增加「交接卡」 | 拆出 T1~T12 可执行分片、编号对照、改动指引、验证命令、已确认事实；供后续会话直接执行 |
| 2026-10-24 | T1~T12 全部落地（12 个独立提交 db9fc6b…b761e99） | 每片一提交一验证：`./gradlew.bat :app:testDebugUnitTest` 全绿（223+ 用例，新增 AuthCookieSanitizerTest / AuthPreferencesTest / SessionExpiredBusTest / CookieInjectionTest / MessageApiTest 总线用例），`assembleDebug` 通过，merged manifest 实测 `allowBackup="false"`。**未验证（无真机）**：§6 中依赖设备的项目——logcat 全流程检查、登出后 `run-as` 检查 WebView Cookies、换号 A→B→A 三次 verifyConfig、datastore 密文 `run-as` 检查、KeystoreCredentialCipher 真机加解密（单测用假 Cipher 覆盖了 AuthPreferences 侧语义）。实现与报告的差异：① T2 连带删掉了时间线两处响应 `preview=`（完成定义要求 logcat 无 `preview=`）；② T4 的「旧账号 SUB 在前只保留新账号」由「登录页创建 WebView 前 removeAllCookies + 去重取首个非空值」共同保证（去重规则按关键改动指引的『同名以高优先级域为先』实现）；③ T5 的兜底注入只覆盖微博内容域且不主动请求游客凭据（避免插件内递归），游客获取仍由 `getEffectiveCookie` 显式触发；④ T7 探活加了 5 分钟节流；⑤ Ktor 3.6.0 的自定义插件 API 在 `io.ktor.client.plugins.api`。 |
| 2026-10-27 | 新增端点层 `WeiboEndpoint.kt`（`WeiboHost` / `WeiboAuth` / `weiboGet` / `weiboPostForm` / `weiboPostJson`），`WeiboApiService` 全部 29 个请求点迁移；主页类读接口共用的「432 → 换游客凭据重试」脚手架收敛进 `weiboGetWithVisitorRetry`。同时删除评论改走 `weibo.com/ajax/statuses/destroyComment`（表单 + 详情页 Referer + 网页域 XSRF），m 站残留 7 处（登录校验、热搜、搜索、点赞列表、博主动态、关注/粉丝容器）。实测记录：网页分榜 `ajax/statuses/<榜名>` 需 SUB（游客可）、`hot_band` 匿名可、无同城榜端点 → 热搜保留 m 站。验证：`./gradlew.bat :app:testDebugUnitTest` 全绿（322 用例）。**未验证**：端点层无真机回归；`hot_band` 兜底方案仅记录未实现。 |

> 维护约定：每完成一片，更新交接卡任务板的状态列，并在「变更记录」追加一行（改了哪些文件、跑了什么验证、未验证什么）。不要把未执行的测试写成已通过。
