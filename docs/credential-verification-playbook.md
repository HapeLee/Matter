# 凭据失效的确认方法

配套：[credential-expiry-research.md](credential-expiry-research.md)（成因分析）、
`scripts/verify-credentials.ps1`（对照实验脚本）、`CredentialAudit`（debug 埋点）。

本文只回答一件事：**怎么用证据判断掉登录属于哪一条通道**，而不是靠猜。
每节都标了「多久出结论」和「结论怎么用」。

---

## 0. 三个待确认的问题

| # | 问题 | 如果答案是「是」 | 如果答案是「否」 |
|---|---|---|---|
| Q1 | 服务端到底会不会轮换 / 续期 `SUB`？ | 轮换落盘（已实现）是必需的 | 落盘无害但没收益，掉线另有原因 |
| Q2 | `/api/config` 的 `login=false` 有多少是真失效？ | 复核策略是必需的 | 复核只是白等 3 秒 |
| Q3 | Matter 存的凭据集合够不够？ | 缺配套 Cookie，先补齐 | 掉线来自时效或风控，不是缺项 |

Q1/Q2/Q3 都能在**当天**出初步结论（实验 1~3），长期比例由实验 4 收口。

---

## 0.1 已实测确认的事实（2026-11-23，匿名游客身份）

下列结论来自真实请求，不依赖任何账号凭据，可直接采信。

### 事实 1：`SUB` 是长期凭据，不是短期会话

`passport.weibo.com/visitor/genvisitor` + `incarnate` 换到的游客凭据：

```
SUB=_2AkMcWKEA...  expires=Tuesday, 23-Nov-2027   ← 1 年
SUBP=0033WrSXq...  expires=Tuesday, 23-Nov-2027   ← 1 年
```

游客都是 1 年，登录态不会更短。**「用几天就掉」不可能是自然过期**，只可能是
轮换、风控或本地判定问题。

### 事实 2（陷阱）：服务端会用 `SUB=deleted; Max-Age=0` 要求删除凭据

带着 `domain=.weibo.com` 的 SUB 去请求 `m.weibo.cn`（或 `weibo.com/`），响应里是：

```
SUB=deleted; expires=Thu, 01-Jan-1970 00:00:01 GMT; Max-Age=0; path=/; domain=.weibo.cn
SUBP=deleted; expires=Thu, 01-Jan-1970 00:00:01 GMT; Max-Age=0; path=/; domain=.weibo.cn
```

这是**删除指令**，不是新值。任何「把 Set-Cookie 合并进 Cookie 头」的代码都必须
过滤它 —— 否则 Cookie 头会变成 `SUB=deleted`，此后每个请求都被判未登录，
**一次请求就能废掉整份凭据**。

本次已修（`parseSetCookie`），覆盖三处合并点：轮换落盘、`desktopWriteCredentials`、
`syncDesktopCredentials`。诊断时看 `set_cookie_deleted` 日志即可知道服务端在哪个
host 上要求删除。

> 注意：`SUB=deleted` 针对的是**单个 domain**，不等于整个登录失效。本地 SUB 的
> domain 是 `.weibo.com`，而 App 大量请求走 `m.weibo.cn`，所以这条指令会**频繁**出现，
> 但 `weibo.com` 侧的登录态可能完全正常。不要据此判失效。

### 事实 3：`/api/config` 会给游客下发配套 Cookie，但丢弃它们不影响能否访问

匿名请求 `/api/config` 收到：

| Cookie | 有效期 |
|---|---|
| `_T_WM` | ~3.7 小时 |
| `MLOGIN` | 1 小时 |
| `XSRF-TOKEN` | 20 分钟（值等于响应体的 `st`） |
| `mweibo_short_token` | 15 秒 |
| `WEIBOCN_FROM` | 会话 |

但实测：**保留 `_T_WM` 等配套项后，游客依然无法访问需登录的接口**
（热搜 `container/getIndex` 仍返回 `ok:-100`）。所以「缺配套 Cookie」不是
「游客过不了」的原因，实验 1 的 D/E/F 组在**登录态**下才需要重新判断。

### 事实 4：游客被拒的 `-100` 与「登录过期」在响应上完全一致

游客凭据请求需登录接口 → `{"ok":-100,"url":"https://passport.weibo.com/sso/signin..."}`。
这与真实登录失效的响应没有区别。这正是「`-100` 必须区分凭据来源」的原因，
本次已由 `carriesLoginCredential` 处理。

### 事实 5（真实账号实测）：凭据集合够用，掉线是 30 天硬过期

2026-11-23 用一个实验账号（`uid=7790688418`）跑了实验 1 与实验 2。

**实验 1 裁剪矩阵**：A~F 组全部 `login=True`。

| 组 | 内容 | 结果 |
|---|---|---|
| A | 浏览器原样全量（11 项） | True |
| B | `SUB` + `SUBP`（Matter 最小形态） | **True** |
| C | 只有 `SUB` | True |
| D | B + `_T_WM` | True |
| E | B + `MLOGIN` | True |
| F | B + `_T_WM` + `MLOGIN` + `SSOLoginState` | True |

→ **Q3 答案：否。** Matter 存的 `SUB + SUBP` 完全够用，掉线**不是**缺配套 Cookie。

**实验 2 续期观察**：三个高频端点都不回 `SUB` / `ALF` / `SUBP`。

| 端点 | 状态 | 下发的 Set-Cookie |
|---|---|---|
| `m.weibo.cn/api/config` | 200, `login=true` | 无（只有 `WEIBOCN_FROM`/`MLOGIN`/`_T_WM`/`XSRF-TOKEN`） |
| `weibo.com/` | 200 | `MLOGIN`、`XSRF-TOKEN`、`mweibo_short_token`、`M_WEIBOCN_PARAMS` |
| `m.weibo.cn/api/container/getIndex`（热搜，登录态） | 200, `ok=1` | **无** |

**决定性的时间戳**：

```
SSOLoginState = 1795436528  →  2026-11-23 20:22:08   （登录时刻）
ALF           = 1798028528  →  2026-12-23 20:22:08   （有效期至）
差值          = 2592000 秒 = 30 天整
```

`ALF` 是微博的会话硬过期时间，**等于登录时刻 + 30 天**，且服务端不走 `Set-Cookie`
续期。结论：

> **「用一阵子就掉登录」不是随机的，是 30 天到期。**
> 服务端不给静默续期，客户端唯一能延长的手段是**到期前重新授权一次**。

这条推翻了两条早前的假设：

- 原 R2「服务端轮换 `SUB` 而本地不写回」——在这三个端点上**不成立**（`SUB` 值始终不变）。
  轮换落盘的代码保留（成本低、可防未知端点），但它不是掉线的主因。
- 「定时打接口做静默续期」——**无效**，因为 `ALF` 不会因访问而被推后。

**已落地的应对**：

- `UserSession.sessionExpiresAt` / `sessionDaysRemaining()` 从 `rawCookie` 的 `ALF`
  算出剩余天数。
- 个人中心显示「登录有效期至 X（剩 N 天）」，剩余 ≤7 天标红。
- 自动退出时把**原因 + 时间**落盘（`SessionExpiryReason`），登录页顶部显示
  「上次登录已于 X 自动退出：<原因>」，区分「微博判定失效 / 启动校验未通过 /
  本地凭据读取失败」三种情况。

### 事实 6：实测「一周就掉」与 30 天 `ALF` 的矛盾

运维反馈：实际约 **一周**就需要重新登录，但 `ALF` 给的是 30 天。两者不可能同时成立，
所以必然是某条通道在 `ALF` 之前就判定了失效。旧版本代码里三条候选都存在：

| 候选 | 旧版本行为 | 现状 |
|---|---|---|
| 探活单次 401 即判死 | `probeSession` 没有复核，风控/限流一次就把用户踢出 | 已改为连续两次 |
| 游客降级后的 `-100` 被当登录失效 | `getEffectiveCookie` 会话读空时回落游客，`-100` 直接上报 | 已加 `carriesLoginCredential` |
| 解密瞬时失败 → 空 Cookie → 探活必判未登录 | 没有防护 | 已改为读空即 SKIP |

**验证方式**：装含本次改动的 debug 包，正常使用一周。若仍掉线，登录页会直接写明
原因；同时 `adb logcat -s CredAuthAudit:D` 里 `probe` / `minus100_*` / `decrypt_failed`
会给出完整时间线。

如果一周后登录页显示的是「微博判定登录已失效」（`API_REJECTED`），说明服务端确实
在 30 天内提前作废了会话——那就不是客户端能修的了，需要在日志里找作废前的最后一次
成功请求，看是否有异常行为（频繁请求、多端登录、改密码）。

### 仍然需要你的数据才能推进

| 实验 | 状态 |
|---|---|
| 实验 1 裁剪矩阵 | ✅ 已完成（2026-11-23，真实账号）→ 凭据集合够用 |
| 实验 2 续期观察 | ✅ 已完成 → 三个端点都不回 `SUB`/`ALF`，30 天内不续期 |
| 实验 2 后半段：30 天后是否真失效 | ⏳ 只能等；折中办法是 **隔 12 小时**再跑一次 `-Watch 1`，看 `ALF` 有没有变 |
| 实验 3 双端对照 | ⏳ 需要真机 + 手机抓包（`adb` 证书安装） |
| 实验 4 长期观测 | ⏳ 需要手机接 USB（`adb devices` 当前为空）+ debug 包 |

---

## 1. 实验准备（10 分钟）

1. **手机和电脑接同一个 WiFi**。出口 IP 变化本身会触发风控，跨网络对照得出的
   `login=false` 无法解读。
2. 电脑浏览器（Chrome / Edge）登录 `weibo.com`，登录后**不要再登录其他设备**，
   否则多端互相顶号会污染对照。
3. 按下节的方法复制 Cookie。
4. 存成文件传给脚本（**不要写进仓库、不要贴到任何对话里**）：

```powershell
./scripts/verify-credentials.ps1 -CookieFile .\cookies.txt
```

> 脚本输出只含键名与 SUB 指纹（长度 + 8 位哈希），不含凭据值，可以直接贴结论。

### 1.1 怎么复制 Cookie（关键：`SUB` 是 HttpOnly）

`SUB` / `SUBP` 的响应头带 `HttpOnly`，所以**在 Console 里跑 `document.cookie` 拿不到**，
网上那些「一行 JS 取 Cookie」的教程对微博无效。必须从 DevTools 的协议层读。

**方法 A（推荐，Chrome / Edge）**：从请求头整行复制

1. 在已登录的标签页按 F12 → **Network**。
2. 刷新页面（Ctrl+R）。
3. 点列表里任一发往 `m.weibo.cn` 或 `weibo.com` 的请求。
4. 右侧 **Headers → Request Headers → `Cookie:`**，全选这一行的值复制。

这一行就是 App 实际会带的那份凭据，最贴近实验。

**方法 B（推荐，Chrome / Edge）**：Cookies 表格整段复制

1. F12 → **Application**（应用）→ 左侧 **Storage → Cookies**。
2. 点 `https://m.weibo.cn`（或 `https://weibo.com`），**一次只点一个域**。
3. 在右侧表格里 Ctrl+A 全选、Ctrl+C 复制。
4. 直接存成 `cookies.txt` —— 脚本认 Tab 分隔的表格，会自动取 Name/Value 两列。

**方法 C（Firefox）**

F12 → **存储 → Cookie** → 选中域 → 右键「复制全部」。

### 1.2 该复制哪个域

| 域 | 用途 | 建议 |
|---|---|---|
| `m.weibo.cn` | **App 登录时优先抓的就是它**（`LoginScreen` 的 cookiePool 顺序第一） | ✅ 首选 |
| `weibo.com` | 网页主域，写操作（点赞/关注/发博）用它 | 要看写路径就选它 |

两个域的 `SUB` **不是同一个值**（domain 分别是 `.weibo.cn` 和 `.weibo.com`）。
这也解释了一个实测现象：拿 `.weibo.com` 的 SUB 去请求 `m.weibo.cn`，服务端会回
`SUB=deleted; Max-Age=0` 要求删掉 `.weibo.cn` 域上的同名 Cookie。
**只导出一个域**，实验结论才干净。

### 1.3 关于「直接把 Cookie 发给我」

**不建议，也不需要。**

`SUB` 是长期全权凭据（有效期一年，可发博、私信、改资料、删内容），泄露等同于
账号被接管；而且 HttpOnly 防的是页面脚本，不防人。任何对话、Issue、截图里出现
过它就应当视为已泄露，只能靠改密码 + 服务端踢下线来补救。

实验不需要我看到凭据值：脚本已经把输出做成「键名 + 指纹」，**你只需要贴这几行**：

```
浏览器 Cookie 键名：SUB, SUBP, _T_WM, MLOGIN
SUB 指纹：len98:5c59861a

Case                                 Login    Uid     Ms    SubRotated
A 全量（浏览器原样）                  True     1234... 256   no
B SUB+SUBP（Matter 最小形态）         True     1234... 216   no
...
```

这已经足够判断走哪条通道。真要我看请求细节，把 `CredAuthAudit` 的 logcat
（`verify` / `set_cookie` 那几行）贴出来即可，那些也只有指纹。

---

## 2. 实验 1：凭据集合够不够（10 分钟，当天出结论）

```powershell
cd d:/Project/Matter
./scripts/verify-credentials.ps1
```

脚本会拿你的全量 Cookie 逐级裁剪，每一步打一次 `m.weibo.cn/api/config`：

| 组 | 内容 | 用途 |
|---|---|---|
| A | 浏览器原样全量 | 基线 |
| B | `SUB` + `SUBP` | **Matter 的最小形态**（`UserSession.cookieHeader`） |
| C | 只有 `SUB` | 看 SUB 单独够不够 |
| D | B + `_T_WM` | 设备标识是否必需 |
| E | B + `MLOGIN` | 登录标记是否必需 |
| F | B + `_T_WM` + `MLOGIN` + `SSOLoginState` | 常见组合 |

**怎么读**

- **B=true**：Matter 存的集合够用 → Q3 是否，掉线来自时效 / 误判，去看实验 2、3。
- **B=false 且 A=true**：Matter 缺关键项。这是「掉得快」的直接解释——服务端要求
  的配套 Cookie 在登录时被 `AuthCookieSanitizer` 或 `cookieHeader` 拼装丢掉了。
  下一步：把 D/E/F 里让 `login` 回到 true 的最小集合记下来，补进 `rawCookie`。
- **A=false**：这份 Cookie 本身已失效，先重新登录再测，否则结论无效。
- **ERR 432 / 403**：触发了风控或频控，等 10 分钟、确认同一 IP 后重跑。

> 注意：脚本用的是与 App 完全一致的 UA / Referer / `X-Requested-With`
> （见 `WeiboRequestHeaders.USER_AGENT` 与 `WeiboApiService.verifyConfig`）。
> 换了 UA 对照就不成立。

---

## 3. 实验 2：服务端会不会轮换 / 续期（1~2 小时出结论）

### 3.1 电脑端

```powershell
# 每 60 秒打一次首页，看 Set-Cookie 里的 SUB 指纹与过期时间
./scripts/verify-credentials.ps1 -Watch 20 -IntervalSeconds 60 -Url https://weibo.com/

# /api/config 也测一遍：接口不同，行为可能不同
./scripts/verify-credentials.ps1 -Watch 20 -IntervalSeconds 60
```

**怎么读**

| 观测 | 含义 | 对本次改动的意义 |
|---|---|---|
| `SUB=none` 一直是 | 该端点不回 SUB | 轮换落盘在该链路上没有收益 |
| 指纹恒定、`expires` 往后推 | **只续期不换值** | 本地值不会过期失效；只要持续使用就不会掉 |
| 指纹变化 | **确实换值** | 不落盘必然掉线；本次改动是必需的 |

### 3.2 为什么要连打 20 次

单次请求说明不了问题：微博可能只在**长时间无请求后首次访问**时才下发新 Cookie。
建议分两段做：

1. 连续 20 次（每次 60 秒）—— 看「高频访问时是否轮换」。
2. 隔 12 小时再打一次 —— 看「长时间空闲后是否换值」。第二段才是「放着不用就掉」
   的直接证据。

### 3.3 手机端对照

实验 4 的 `set_cookie` 日志与这里互为验证：如果电脑端能看到指纹变化，而手机端
`set_cookie` 一条都没有，说明 App 的请求链路（不同 host / header）压根没收到 Set-Cookie，
这本身就是问题。

---

## 4. 实验 3：`login=false` 是不是真失效（30 分钟）

在同一时刻、同一 IP 下并发做两件事：

1. 电脑上跑 `./scripts/verify-credentials.ps1`（A 组全量）。
2. 手机上让 App 触发一次探活（冷启动，或切后台再回前台）。

然后对照：

| A 组（电脑） | App 结果 | 结论 |
|---|---|---|
| true | 探活通过 | 正常 |
| true | 探活判失效 | **误判**：App 的请求姿势或凭据集合与浏览器不同，看实验 1 |
| false | 探活判失效 | 真失效，但要看是不是**刚**失效（对照实验 2 的时效） |

App 侧的判定依据在实验 4 的 `verify` 日志里，`sentKeys` 直接列出它实际带了哪些
Cookie 键 —— 与浏览器的键名表逐项比对，缺哪个一目了然。

**「部分 cookie 场景」补充**：把实验 1 的 B 组结果也纳入对照。如果 B 组
`login=false` 而 A 组 `true`，那么 App「探活判失效」就**不是误判**，而是
凭据集合不足 —— 这是最容易被误诊成「微博又把我们踢了」的情况。

---

## 5. 实验 4：App 端长期观测（7~14 天）

### 5.1 采集

埋点只在 debug 构建生效（`CredentialAudit` 用 `BuildConfig.DEBUG` 守住），
release 不落任何日志，符合 `credential-auth-review.md` T2 的约束。

```powershell
adb logcat -c
adb logcat -s CredAuthAudit:D > cred.log
# 正常使用 7~14 天，中途不要清日志
```

也可以只在自己想复现时开：

```powershell
adb logcat -s CredAuthAudit:D
```

### 5.2 日志字段

| 事件 | 字段 | 用途 |
|---|---|---|
| `verify` | `login` / `uid` / `sentKeys` / `sentSub` / `localSub` | 第 n 次 `/api/config` 的真实结论与**实际发出去的**凭据 |
| `probe` | `result` / `firstWasExpired` | 探活结论；`firstWasExpired=true` 且 `result=VALID` 就是「复核挡下的一次误登出」 |
| `set_cookie` | `host` / `subChanged` / `subpChanged` / `newSub` / `oldSub` / `uid` | 服务端轮换事件，频率 = 真实轮换频率 |
| `minus100_expired` | `uid` / `sub` / `keys` | 确认是登录凭据被拒 |
| `minus100_ignored` | `usedSub` / `sessionSub` | 游客降级被拦下的次数 |
| `decrypt_failed` | `len` / `reason` | Keystore 解密失败（`AEADBadTagException` = 密钥不匹配；其他 = 服务不可用） |
| `set_cookie_deleted` | `host` / `name` | 服务端回了删除指令（见下方陷阱），**不代表登录失效** |
| `session_suspended` / `logout` | — | 两条不同路径各触发了几次 |

### 5.3 分析（PowerShell）

```powershell
# 1) 轮换总次数 / 换值次数
(Select-String -Path cred.log -Pattern 'set_cookie').Count
(Select-String -Path cred.log -Pattern 'set_cookie.*subChanged=true').Count

# 2) 探活结论分布
Select-String -Path cred.log -Pattern 'probe result=' |
    Group-Object { ($_.Line -split 'result=')[1] } |
    Select-Object Count, Name

# 3) 复核挡下的误登出（关键指标）
(Select-String -Path cred.log -Pattern 'probe result=VALID.*firstWasExpired=true').Count

# 4) 本地凭据读空被降级成游客的次数（sentSub 与 localSub 指纹不一致）
Select-String -Path cred.log -Pattern 'verify' | ForEach-Object {
    $sent = [regex]::Match($_.Line, 'sentSub=(\S+)')
    $local = [regex]::Match($_.Line, 'localSub=(\S+)')
    if ($sent.Success -and $local.Success -and
        $sent.Groups[1].Value -ne $local.Groups[1].Value) { $_.Line }
} | Measure-Object

# 5) 真失效 vs 被忽略的 -100
(Select-String -Path cred.log -Pattern 'minus100_expired').Count
(Select-String -Path cred.log -Pattern 'minus100_ignored').Count
```

### 5.4 判定表

| 观测（7 天内） | 结论 | 下一步 |
|---|---|---|
| `set_cookie` 0 条 | 服务端不回 SUB | 轮换落盘无收益（保留无害）；掉线来自时效或风控，转 Q2 |
| `set_cookie` 有，`subChanged=true` 少 | 只续期，偶尔换值 | 当前改动覆盖了；若仍掉线，重点看探活误判 |
| `subChanged=true` 频繁 | 确实在轮换 | 改动必需；掉线应显著减少，否则看下一条 |
| `verify login=False` 远多于 `minus100_expired` | 探活误判占主导 | 复核已挡住；可把复核间隔从 3s 拉长到 5~10s |
| `probe result=VALID.*firstWasExpired=true` > 3 次/周 | 单次判定会频繁误登出 | 复核策略有效，考虑加第三信号（如换 IP 前不判死） |
| `sentSub != localSub` 反复出现 | 本地凭据读空被降级游客 | 看同时间的 `decrypt_failed`：有则是 Keystore 问题，没有则是字段本身为空 |
| `minus100_ignored` 多 | 游客降级曾频繁误触发登出 | 本次的凭据一致性判定在起作用 |
| `session_suspended` 与 `logout` 次数相同 | 用户全在手动登出 | 自动失效几乎没发生，说明改动生效 |

---

## 6. 「静默续期」要不要做，用哪条证据决定

静默续期 = 定时打一次 `/api/config` 保持服务端会话活跃。它只在**同时满足**下面两条时才有价值：

1. 实验 2 观察到的 `expires` **确实在往后推**（服务端在续期，而不是只认签发时间）；
2. 实验 4 里掉线集中在「长时间没打开 App 之后」（`verify login=False` 的时间点前
   有 > N 小时的空窗）。

如果实验 2 显示 `SUB=none`（服务端不回 Cookie），那续期多半不依赖客户端行为，
加定时请求只是白耗流量与风控额度。

反过来，如果第 1、2 条都成立，方案是：`WorkManager` 每 12 小时一次前台任务，
带当前 Cookie 打 `/api/config`，只在 `login=false` 时静默标记（**不登出**）。
这条留到实验结论明确后再做。

---

## 7. 不要做的事

- **不要用不同网络做对照**：IP 变化是风控的独立变量，会让所有结论失效。
- **不要把真实 `SUB` 贴到 issue / 日志 / 截图**：脚本已做指纹化，手工对照时也照此处理。
- **不要在多台设备同时保持登录**：微博会互相顶号，观测到的「失效」不是 Matter 的问题。
- **不要为了「验证」把 `CredentialAudit` 改成 release 可见**：那会把凭据指纹写进用户日志。
- **不要只看单次结果下结论**：轮换和风控都是概率事件，至少跨 24 小时。
