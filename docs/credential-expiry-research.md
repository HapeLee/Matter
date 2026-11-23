# 登录凭证为什么会「很容易失效」—— 代码层调研

调研日期：2026-11-20
范围：`d:/Project/Matter` 主源码（只读，未修改任何代码）
结论口径：微博 H5 网页端 Cookie（`SUB` / `SUBP`）会话

---

## 0. 一句话结论

**不是「过期时间到了」，而是「本地只有登录那一刻的凭据快照，之后从不更新」，再加上「一次判定即永久删除密文」的放大器。**

- 结构性欠缺：响应里的 `Set-Cookie`（服务端轮换/续期的 `SUB`）**从不落盘**，本地凭据只能越来越旧。
- 放大器：任何一次「判定失效」都直接 `logout()` → `clearSession()` **删掉密文**，没有「先重试 / 先确认 / 先降级」的中间态。
- 具体缺陷：三条独立的误判通道都能触发那条删除路径。

所以用户体感是「莫名其妙被登出，且必须重新扫码/输密码」，而不是「提示我续期一下」。

---

## 1. 凭据生命周期全景

```
登录页 WebView 抓 6 个域 Cookie
  └─ AuthCookieSanitizer.canonicalize（首个非空胜出）
      └─ verifyConfig 打 /api/config 验一次
          └─ saveSession：uid / sub / subp / rawCookie 加密落 DataStore   ← 唯一写入口
              │
              ├─ 之后 90% 的请求：sessionFlow.first() → 3 次 AES/GCM 解密 → 拼 Cookie 头
              │       └─ 响应里的 Set-Cookie：丢弃（读路径）
              │
              └─ 任一失效信号 → SessionExpiredBus → AuthRepository.logout()
                      └─ clearSession()：remove(sub/subp/raw_cookie)          ← 密文消失
```

| 环节 | 位置 | 事实 |
|---|---|---|
| 唯一写入口 | `core/datastore/AuthPreferences.kt:160-185` | 仅登录成功 / 存访客两处调用 |
| 唯一清入口 | `AuthPreferences.kt:221-237` | 逐键 remove，不可恢复 |
| 加密 | `core/platform/KeystoreCredentialCipher.kt:38-47` | 失败退回明文（可用性优先） |
| 解密 | `KeystoreCredentialCipher.kt:49-63` | 失败**返回空串**，不区分原因 |
| 过期时间 | — | 全仓无 `expires` / `expireAt`，只有布尔 `is_logged_in` |
| 凭据拼装 | `core/datastore/UserSession.kt:34-58` | `sub`/`subp` 覆盖 `rawCookie` 同名项（设计正确） |

---

## 2. 三条「失效」通道（都能走到删除密文）

### 通道 A：服务端轮换 / 续期，本地收不到 —— 结构性，必然发生

```1901:1910:app/src/main/java/org/kazusa/matter/core/network/WeiboApiService.kt
            response.headers.getAll("Set-Cookie").orEmpty().forEach { header ->
                val pair = header.substringBefore(';')
                if ('=' in pair) cookies[pair.substringBefore('=').trim()] = pair.substringAfter('=').trim()
            }
        }
        ...
        return base.copy(cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
            token = csrf.decodeURLQueryComponent())
            .also { cachedDesktopCredentials = it }
```

- 全仓只有两处读 `Set-Cookie`：`WeiboApiService.kt:1901-1910` 与 `1917-1931`（`syncDesktopCredentials`），**两处都只写内存 `cachedDesktopCredentials`**。
- 注释自己写了「要像浏览器 cookie jar 一样跟着更新，缓存令牌才不会悄悄过期」——但**只对 `XSRF-TOKEN` 生效，且进程一重启就丢**。
- 读路径（时间线、分组、私信、`/api/config`）**完全丢弃** `Set-Cookie`。

后果：浏览器里 `SUB` 是滑动的（每次访问服务端回新 exp，必要时换值）；Matter 里它永远停在「登录那一刻」。当服务端认为该 `SUB` 需要轮换，浏览器会自动跟上，Matter 不会 → 下一次请求拿到 `-100`。

### 通道 B：探活一次 `login!=true` 即判死 —— 误判面宽

```194:206:app/src/main/java/org/kazusa/matter/data/repository/AuthRepository.kt
    suspend fun probeSession(): SessionProbe {
        val current = authPreferences.sessionFlow.firstOrNull()
            ?: return SessionProbe.SKIP
        if (!current.isLoggedIn || current.uid.isBlank() || current.uid == "guest") {
            return SessionProbe.SKIP
        }
        return when (val result = apiService.verifyConfig(current.cookieHeader)) {
            is ApiResult.Success -> SessionProbe.VALID
            is ApiResult.Error ->
                if (result.code == 401) SessionProbe.EXPIRED else SessionProbe.NETWORK_ERROR
```

`401` 的判定在 `WeiboApiService.kt:361-368`：**HTTP 200 且 `data.login != true`**。

问题：
1. 没有重试、没有交叉信号、没有「连续 N 次」。
2. `/api/config` 在风控、限流、配套 cookie 缺失、灰度时都可能回 `login:false`。
3. 更关键：**`current.cookieHeader` 为空时照样发请求**。空 Cookie 走 `CookieAuth` 兜底变成游客 → 必然 `login:false` → 401 → 判死。
   `reportSessionExpiredIfLoggedIn` 里那句 `if (usedCookie.isBlank()) return` 的保护**在探活路径上不存在**，等于「本地凭据读空」被当成「服务端判定失效」。

触发点：`MatterNavGraph.kt:226-244`，冷启动 + 每次回前台（5 分钟节流）。

### 通道 C：`-100` 一次全局登出，且无法区分「游客被拒」

```196:209:app/src/main/java/org/kazusa/matter/core/network/WeiboApiService.kt
    private suspend fun reportSessionExpiredIfLoggedIn(usedCookie: String, code: Int?) {
        if (code != -100 || usedCookie.isBlank()) return
        ...
        if (session.isLoggedIn && session.uid.isNotBlank() && session.uid != "guest") {
            sessionExpiredBus.notifySessionExpired()
        }
    }
```

`usedCookie` 只校验「非空」，不校验「是不是真的登录凭据」。而 `getEffectiveCookie`（`WeiboApiService.kt:217-236`）在会话为空时会**静默回落游客 Cookie**：

```224:235:app/src/main/java/org/kazusa/matter/core/network/WeiboApiService.kt
        if (sessionCookie.isNotBlank()) {
            return sessionCookie
        }
        if (!cachedVisitorCookie.isNullOrBlank()) {
            return cachedVisitorCookie!!
        }
```

于是：会话凭据读空 → 拿游客 Cookie 打登录接口 → 服务端按游客回 `-100` → 因 `usedCookie` 非空且本地 `isLoggedIn=true` → **上报全局失效 → 登出**。
游客被拒被解释成「你的登录过期了」。

---

## 3. 为什么「失效」等于「不可恢复」

这是最关键的一句代码：

```208:213:app/src/main/java/org/kazusa/matter/data/repository/AuthRepository.kt
    suspend fun logout() {
        authPreferences.clearSession()
        // 登录 WebView 的 Cookie 库里同样躺着 SUB/SUBP；不清掉的话下次进登录页
        // 会自动抓回旧凭据登回原账号。清理失败也不能阻断本地登出。
        runCatching { webCookieStore.clearAll() }
    }
```

`logout()` 被「会话失效」和「用户主动登出」共用。两者语义完全不同，但副作用一样：**删密文 + 清 WebView**。

叠加上 `LoginScreen.kt:134-137` 每次进登录页 `webCookieStore.clearAll()`，一次误判的代价是：

- DataStore 密文删除
- WebView Cookie 库清空（唯一可能还活着的、服务端最新那份凭据也一起没了）
- 用户必须重新走一遍网页授权（含人机验证）

**没有任何「凭据还在，只是这次没验通」的恢复路径。**

### 附带的加密侧隐患

```67:73:app/src/main/java/org/kazusa/matter/core/platform/KeystoreCredentialCipher.kt
    private fun obtainKey(): SecretKey {
        cachedKey?.let { return it }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = (keyStore.getKey(keyAlias, null) as? SecretKey) ?: generateKey()
        cachedKey = key
        return key
    }
```

`getKey` 返回 null 时**直接 `generateKey()` 覆盖同一个别名**（`generateKey` 用 `KeyGenParameterSpec.Builder(keyAlias, …)`）。也就是说，一次「别名短暂取不到」就会把密钥换掉，此前所有密文变成永久解不开的垃圾。

再叠加 `decrypt` 失败时 `cachedKey = null`（`KeystoreCredentialCipher.kt:60`）——失败只清缓存、不区分「密钥没了」和「这次没读到」，下一次就可能在旧密文上重建新密钥。

真实触发场景（非臆测）：
- 用户在系统设置里「清除凭据」（企业设备策略 / VPN 证书重置时会提示），**销毁所有 AndroidKeyStore 密钥**
- OEM 系统升级后 Keystore 数据不一致
- 应用被克隆/多用户切换导致 UID 变化

这些场景下单看日志只会显示「登录已过期」，看起来像服务端把用户踢了。

---

## 4. 三个次一级但真实的缺陷

| # | 位置 | 问题 |
|---|---|---|
| 1 | `AuthPreferences.kt:174-176` | `saveSession` 用 `if (sub.isNotBlank())` 才写。新登录串若不含 `SUB`（`canonicalize` 会剔掉 `SUB=""`），**旧账号的 SUB 被保留**，与新的 `uid` / `rawCookie` 混搭 → 立即失效或串号。 |
| 2 | `AuthPreferences.kt:177-185`（`saveGuestSession` 传空串）+ 上述守卫 | 从已登录态切访客时，`uid=guest` 但 `sub/subp/raw_cookie` **不清**，游客会话仍带旧账号 Cookie。 |
| 3 | `AuthPreferences.kt:128-158` | `sessionFlow` 的 `map` 里做 3 次 Keystore 解密。每次 `firstOrNull()` 都重跑——一个请求里 `getEffectiveCookie` + `reportSessionExpiredIfLoggedIn` + `resolveSessionUid` 会各收一次，即**单请求 6~12 次 AES/GCM**。既慢，也把「瞬时解密失败」的概率乘以次数。 |

---

## 5. 怎么确认到底是哪条通道（不要靠猜）

现有代码里失效路径**没有任何可区分的信息**——三条通道最终都只剩一句 `登录已过期`。建议先补诊断（不改行为）：

1. `SessionExpiredBus.notifySessionExpired()` 增加来源参数：`PROBE` / `API_MINUS_100` / `LOCAL_EMPTY_COOKIE` / `DECRYPT_FAILED`。
2. `KeystoreCredentialCipher.decrypt` 失败时区分 `AEADBadTagException`（密钥不匹配）与 `KeyStoreException`（服务不可用），分别打点。
3. 每次 `saveSession` / `logout` 记录 `uid` 前 4 位 + `sub` 长度 + 时间戳（不记值）。
4. 黑盒复现：真机连跑 7 天，每天首次冷启动记录 `/api/config` 的原始 `login` / `uid` 字段（不含 `st`）。

有了这四条，一周内就能区分「服务端轮换」（通道 A，`-100` 集中在登录后 N 天）与「探活误判」（通道 B，随机、多在前台切换后）。

---

## 6. 修复建议（按性价比排序）

### P0-1　`Set-Cookie` 落盘（治本，直接消掉通道 A）

在 `CookieInjection` 插件的 `onResponse` 里，对微博内容域的响应解析 `Set-Cookie`，把 `SUB` / `SUBP` / `SUBP` 等长期项合并后**写回 `AuthPreferences`**（带 30 秒合并窗口，避免每请求一次 DataStore 写）。

这就是浏览器 cookie jar 的语义，也是唯一能覆盖「服务端轮换」的手段。注意事项：
- 只合并不是凭据的项，`SUB` 值变化时必须同时更新 `sub` 字段（`UserSession.cookieHeader` 会用 `sub` 覆盖 `rawCookie`，不同步就会自相矛盾）。
- 写回要按 uid 做代次校验，防止换号时旧响应写进新会话。

**前置改造**：`WeiboApiService:196-209` 的失效判定必须先能区分「游客凭据」与「登录凭据」，否则落盘后仍会被通道 C 杀掉。

### P0-2　把「会话失效」和「用户登出」拆开

- `logout()`：用户主动 → 清密文 + 清 WebView（现状保留）。
- `invalidateSession(reason)`：判定失效 → **只置 `is_logged_in=false`，保留 `sub`/`subp`/`raw_cookie`**，跳登录页提示「请重新授权」。
  用户下次登录成功会覆盖；若这次是误判，重试一次就能自愈，不需要重新扫码。

这一条把「不可恢复」变成「可恢复」，成本最低、收益最大。

### P0-3　把「解密失败」与「服务端失效」分开

- `CredentialCipher.decrypt` 改为返回 `Result<String>` 或在 `AuthPreferences` 侧标记 `credentialReadFailed`。
- 读失败时**不发探活请求、不判失效**，直接提示「凭据读取失败，请重新登录」（且不删密文）。
- `KeystoreCredentialCipher.obtainKey()` 去掉「getKey 返回 null 就静默 generateKey」；改为抛出让上层决定是否提示重新登录。

### P1　探活加确认

`probeSession` 改为「连续 2 次失败且间隔 ≥ 5s 才判 EXPIRED」，或「401 之前先用当前 cookie 打一个必登录接口交叉验证」。

### P1　`-100` 判定带上凭据来源

`reportSessionExpiredIfLoggedIn` 改为接收 `WeiboAuth` 语义（Session / Guest）而非裸字符串，游客凭据的 `-100` 一律不报。

### P2　`saveSession` 守卫改为「显式覆盖」

登录成功时对 `sub`/`subp`/`raw_cookie` 无条件写入（空值就写空并 `remove`），避免旧账号凭据残留。`saveGuestSession` 同步清空三项。

---

## 7. 与既有文档的关系

`docs/credential-auth-review.md` 是 2026-10-24 的**安全评估**（T1~T12 已全部落地：关备份、清日志、登出清 WebView、Cookie 去重、注入集中化、失效总线、探活、加密）。
那份文档解决的是「凭据会不会泄露」，**没有覆盖「凭据为什么会失效」**——因为它是安全视角，而不是可用性视角。

本调研补的正是这个缺口：T6/T7/T10 三项落地后引入的**新语义问题**（总线一次即登出、探活单次判定、加密失败静默）正好是当前「容易失效」的三个放大器。

---

## 8. 未验证项（需要真机）

> 确认方法见 [credential-verification-playbook.md](credential-verification-playbook.md)：
> 前两项用 `scripts/verify-credentials.ps1` 当天可出结论，第三项靠 `CredentialAudit` 埋点长期观测。

1. `SUB` 在真实微博服务端多久轮换一次、是否每次响应都回 `Set-Cookie`。
2. `/api/config` 返回 `login:false` 时 `cookie` 是否真的失效（需对照浏览器同 cookie 访问）。
3. Keystore 密钥在目标机型上的稳定性——本机无法真机跑 `KeystoreCredentialCipher`（单测用的是假 Cipher）。
4. WebView Cookie 库与 DataStore 在登录成功时刻是否一致（可能存在「验证 A、保存 B」）。

---

## 9. 已落地的改动（2026-11-20）

按 §6 的 P0/P1 执行了第一批，均为「让凭据活得更久」直接相关项。

| # | 改动 | 文件:要点 |
|---|---|---|
| 1 | **Set-Cookie 轮换落盘**：`CookieAuth` 插件 `onResponse` 收集含 `SUB`/`SUBP` 的响应头，交 `WeiboApiService.rotateSessionCookie` 解析后写回 DataStore | `CookieInjection.kt`（`bindRotationSink` / `onResponse`）、`WeiboApiService.kt`（`rotateSessionCookie`）、`AuthPreferences.kt`（`rotateCredentials`） |
| 2 | **判定失效不再删凭据**：网络层与探活的上报改走 `suspendSession()`（只置 `is_logged_in=false`），`logout()` 仅保留给用户主动登出 | `AuthRepository.kt`、`MatterNavGraph.kt`、`AuthPreferences.kt`（`markSessionInvalid`） |
| 3 | **登录页不再无条件清 WebView**：凭据仍在时保留 WebView 会话，登录页可自动抓回并恢复 | `LoginScreen.kt`（`clearWebCookiesOnEnter`）、`MatterNavGraph.kt` |
| 4 | **探活加固**：本地凭据读空直接 SKIP（不发请求）；单次 401 后隔 3s 复核，两次才判失效；复核期间换号则放弃 | `AuthRepository.kt`（`probeSession` / `verifySession`） |
| 5 | **`-100` 只认登录凭据**：仅当本次请求携带的 `SUB` 等于当前会话权威 `SUB` 时才上报失效，游客降级不再误触发 | `WeiboApiService.kt`（`carriesLoginCredential`） |
| 6 | **登录写入整体覆盖**：`saveSession` 对 `sub`/`subp`/`rawCookie` 无条件写入（空即删除），旧账号凭据不再残留 | `AuthPreferences.kt`（`writeCredential`） |
| 7 | **解密不重建密钥**：`decrypt` 路径禁止 `generateKey()`，避免一次别名取不到就把所有旧密文永久废掉 | `KeystoreCredentialCipher.kt`（`obtainKey(allowCreate)`） |
| 8 | **过滤 Set-Cookie 删除指令**：实测发现服务端会用 `SUB=deleted; Max-Age=0` 要求删除凭据，之前会把它当新值落盘/合并，等于一次请求废掉凭据 | `CookieInjection.kt`（`parseSetCookie`）、`WeiboApiService.kt`（轮换、写凭据、写凭据同步三处） |

验证：`./gradlew.bat :app:testDebugUnitTest` 全绿（441 用例，含新增
`AuthRepositoryTest` 5 例、`SessionCookieRotationTest` 3 例、
`AuthPreferencesTest` +3 例、`CookieInjectionTest` +3 例）。

**仍需真机验证**（本机无法覆盖）：服务端 `SUB` 的实际轮换频率与 `Set-Cookie` 出现位置；
轮换落盘在真实请求链路上的读写时序；Keystore 密钥在目标机型上的稳定性。
确认方法见 [credential-verification-playbook.md](credential-verification-playbook.md)。

**未做**：后台静默续期探针（定时打 `/api/config` 保持服务端续期）——需要先确认
「服务端是否因为客户端不发 `Set-Cookie` 而拒绝续期」，否则只是增加请求量。

---

## 10. 实验结论（2026-11-23，真实账号）

用实验账号（`uid=7790688418`）跑完了实验 1 与实验 2，两条早前判断被推翻。

### R2「服务端轮换 SUB 而本地不写回」——**证伪**

| 端点 | 是否回 `SUB`/`ALF`/`SUBP` |
|---|---|
| `m.weibo.cn/api/config` | 否 |
| `weibo.com/` | 否（只有 `MLOGIN`/`XSRF-TOKEN`/`mweibo_short_token`） |
| `m.weibo.cn/api/container/getIndex`（登录态，`ok=1`） | 否 |

`SUB` 值在这些请求中始终不变。轮换落盘的代码保留（成本低、可防未采样到的端点），
**但它不是掉线主因**。

### R3/R4「探活误判 / 游客降级误判」——**仍然成立但只是次要因素**

真实账号下 A~F 组全部 `login=true`，说明凭据集合完整、请求姿势无问题。
这两条只会在「本地凭据读空」时被触发，而那是加密或字段问题，不是常态。

### 真正的主因：30 天硬过期

```
SSOLoginState = 1795436528 → 2026-11-23 20:22:08   （登录时刻）
ALF           = 1798028528 → 2026-12-23 20:22:08   （有效期至）
差值 = 2592000 秒 = 30 天整
```

`ALF` 是会话过期时间，服务端**不通过 `Set-Cookie` 续期**——三个高频端点都验证过。

因此：

- **掉线是可预测的 30 天到期，不是随机事件。**
- 静默续期（定时打接口）**无效**：`ALF` 不会因访问而被推后。
- 唯一能延长的手段是**到期前重新授权一次**，把 `ALF` 重置为新的 +30 天。

### 已落地

- `UserSession.sessionExpiresAt` / `sessionDaysRemaining(now)` 从 `rawCookie` 的 `ALF`
  直接算出剩余天数（`UserSessionTest` 5 例锁定口径）。
- 个人中心显示「登录有效期至 X（剩 N 天）」，≤7 天标红。
- 自动退出时记录**原因 + 时间**（`SessionExpiryReason`：`API_REJECTED` /
  `PROBE_FAILED` / `LOCAL_CREDENTIAL_MISSING`），登录页顶部展示
  「上次登录已于 X 自动退出：<原因>」。主动登出会清空这条记录。

### 与「一周就掉」的矛盾（待确认）

运维反馈实际约 7 天就要重登，但 `ALF` 是 30 天 —— 说明有通道在到期前就判了失效。
旧版本的三条候选（探活单次 401、游客降级 `-100`、解密失败导致空 Cookie）现已全部加固。
新版跑一周后，登录页的原因文案会直接给出结论：

- 显示「微博判定登录已失效」→ 服务端提前作废，客户端不可控，需查作废前的请求行为；
- 显示「启动校验时发现已退出」→ 探活仍在误判，需要再收紧（拉长复核间隔 / 加第三信号）；
- 显示「本地凭据读取失败」→ Keystore 问题，看 `decrypt_failed` 的 `reason`。

### 尚未验证

1. `ALF` 到期后服务端的具体表现（`login=false` 还是 HTTP 401）——只能等到 2026-12-23。
2. 长时间（>12 小时）无请求后首次访问是否回新的 `ALF`——用 `-Watch 1` 隔半天跑一次即可。
3. 用户改密码 / 异地登录是否会立即作废 `SUB` 并重置 `ALF`（这类「非到期失效」需单独统计）。

