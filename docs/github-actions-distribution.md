# GitHub Actions 分发与版本号自增（方案研究）

> 状态：**已落地（2026-10-21）**。编号方案、签名配置与三条 workflow 已写入仓库，实际行为以 [版本管理说明](versioning.md) 为准；本文保留方案取舍与风险记录，供以后回看"为什么这么选"。
>
> 尚未完成：仓库 secrets（签名四项）未配置，正式发布链路还没有实跑过。下文标注"未验证"的条目仍然有效。
>
> 参考实现：`D:\Project\legado-with-MD3`（同一作者维护，已有可用的 Actions 分发链路）。该项目的版本号机制值得借鉴，本文 §4 专门拆解并给出取舍。

## 一、结论

可行。借鉴参考项目后，**推荐把 `VERSION_CODE` 从"本地文件自增"改为"Git 提交数派生"**，`version.properties` 只维护显示版本：

- `VERSION_CODE = git rev-list --count HEAD`（加偏移量），**不需要回写仓库**，因此不需要机器人推 `main`、不需要持久化计数器、不依赖分支保护配置，本地与 CI 结果一致。
- `VERSION_NAME = MAJOR.MINOR.PATCH` 由人决定，预发布后缀（`-beta.N`）由 git tag 推导。
- 这条路线能成立的关键前提：**只从 `main` 发布，且不重写 `main` 的历史**。提交数是历史的函数，`squash` / `rebase` / force push 会让编号回退——这是它唯一的实质代价，必须写成硬约束。
- 仍然需要补 **release 签名**，否则一切产物都装不上。这是真正的阻塞项，与 CI 选型无关。
- 上一版推荐过的"回写 `version.properties`"方案在 §4 保留为备选：它换来"编号即预留、跳号可解释"的显式记录，代价是机器人推送权限与并发写入约束。

## 二、改造前的状态（分发链路测绘）

| 环节 | 当前实现 | 位置 |
| --- | --- | --- |
| 版本来源 | `VERSION_NAME` 手改；`VERSION_CODE` 由脚本 +1 | `version.properties` |
| 注入 Gradle | `-PmatterVersionName` / `-PmatterVersionCode`，缺省读文件 | `app/build.gradle.kts:14-26` |
| 打包 | `./scripts/package-distribution.ps1 [-Variant Debug\|Release] [-Format Apk\|Bundle]` | 脚本第 72 行组装 `:app:assemble<X>` / `:app:bundle<X>` |
| 并发保护 | 文件锁 `.gradle/matter-versioning/distribution.lock` | 脚本第 29-36 行 |
| 编号写入 | 临时文件 + `File.Replace` 原子替换 | 脚本第 61-69 行 |
| 产物 | `app/build/outputs`，release 为 **未签名** APK | `docs/versioning.md:43` |
| 上传 / 发布 | **没有** | 脚本 SYNOPSIS |
| 签名 | 无 `signingConfigs`、无 keystore | `app/build.gradle.kts` 全文 |
| 打包环境 | 硬编码 `gradlew.bat`（Windows 专用） | 脚本第 23 行 |

工具链：Gradle 9.8.1、AGP 9.4.1、Kotlin 2.4.21、JDK 21、`compileSdk 37` / `minSdk 31` / `targetSdk 35`。仓库 `origin` = `github.com/HapeLee/Matter`，`main` 分支 7 次提交，**无任何已发布版本**。

最后一点很关键：因为没有存量用户，现在切换编号方案的成本为零，不需要兼容历史 `versionCode`。

## 三、缺口清单

| # | 缺口 | 影响 | 需要的改动 |
| --- | --- | --- | --- |
| 1 | release 无签名 | APK 不可安装；AAB 不可上传 | `signingConfigs` + keystore secrets |
| 2 | 编号分配只在本机 | CI 上无法复用同一套分配 | 改用提交数派生（推荐）或回写 |
| 3 | 脚本依赖 Windows | ubuntu runner 上 `gradlew.bat` 不存在 | 按 OS 选择 wrapper |
| 4 | 无 CI | 无法自动构建/发布 | `.github/workflows/*.yml` |
| 5 | 无 tag / Release 约定 | 无法定位"某编号对应哪个提交" | 打 tag 并让 Release 指向该提交 |
| 6 | 无变更日志 | 用户不知道更新了什么 | `generate_release_notes` 或 `git log` |

## 四、参考项目的版本号机制（拆解）

### 4.1 它做了什么

`app/version.properties` 只有三段版本加一个通道标志：

```properties
VERSION_MAJOR=3
VERSION_MINOR=26
VERSION_PATCH=17
VERSION_SUFFIX=0   # 1 = Pre, 0 = Release
```

Gradle 侧（`app/build.gradle.kts:25-29, 59-60`）：

```kotlin
val projectVersionName = "$versionMajor.$versionMinor.$versionPatch"
versionCode = System.getenv("COMMIT_NUMBER")?.toInt()?.let { 10000 + it } ?: 32640
versionName = System.getenv("APP_VERSION_NAME") ?: projectVersionName
```

`auto-release.yml` 的 `prepare` job 负责算出全部版本信息：

| 量 | 计算方式 |
| --- | --- |
| `COMMIT_COUNT` | `git rev-list --count HEAD`，作为 `versionCode`（偏移 10000） |
| `current_version` | 文件里的 `MAJOR.MINOR.PATCH` |
| `VERSION_SUFFIX=0` | 正式版，`versionName` 无后缀，Release 标记为正式 |
| `VERSION_SUFFIX=1` | 预发布：找最近一个 `X.Y.Z-beta.*` tag，取其 N+1，`versionName = X.Y.Z-beta.N` |
| `last_tag` | 正式版取同 `MAJOR.MINOR.*` 的上一正式 tag；预发布取最近任意 tag，作为 changelog 起点 |

其余可复用做法：

- **触发分层**：`release.yml` 只是一个 6 行的 wrapper（`if: github.actor == 'HapeLee' && ref == main`，`workflow_call` 调用 `auto-release.yml`，`publish: true`）。日常 push/PR 走同一个可复用 workflow 但 `publish` 默认 false，只出构建产物。权限门禁和构建逻辑分离得很干净。
- **签名**：keystore 以 base64 存 secret，构建时解码到工作区，再把 `RELEASE_STORE_FILE/PASSWORD/KEY_ALIAS/KEY_PASSWORD` **追加到 `gradle.properties`**；Gradle 用 `project.hasProperty("RELEASE_STORE_FILE")` 判断是否启用 `signingConfig`，本地无密钥时仍能正常构建。
- **CI 网络**：显式 `sed -i '/maven.aliyun.com/d' settings.gradle`，并在注释里写明原因——**aliyun 镜像在 runner 上会 502，且 Gradle 一旦判定某仓库失败就整个停用它**。这一点修正了我上一版的判断：镜像有 `google()` 兜底不等于没问题，CI 里应当直接移除而不是指望回退。
- **产物形态**：`splits.abi` 开启（`armeabi-v7a` / `arm64-v8a` + universal），三个 APK 分别上传 artifact，再统一重命名进 Release。
- **可追溯性**：`mapping.txt`、`missing_rules.txt` 一并上传（配合 `retrace-log.yml` 解混淆）。
- **分发终点**：Release 之后发布静态更新清单到孤儿分支 `update-manifests`（`official.json` / `beta.json`），再由 Telegram bot 推送 APK 与更新说明。应用内检查更新读的就是这份清单。
- **门禁**：`Verify` workflow 与发布分离，跑单测 + lint + 架构检查 + debug 构建，`--continue` 收集全部失败项并上传报告。

### 4.2 它的代价与坑

| 问题 | 说明 |
| --- | --- |
| 单调性依赖历史 | 提交数只在"线性追加"时单调。squash/rebase/force push 会让 `versionCode` 回退，用户无法覆盖安装。它用"只走 merge、不重写 main"的工作流规避 |
| 必须 `fetch-depth: 0` | 默认浅克隆下 `rev-list --count HEAD` 等于 1，`versionCode` 直接错。两条流水线都显式设置了 |
| `System.getenv` 读版本 | 环境变量作为构建输入对配置缓存不友好，所以两处都加了 `--no-configuration-cache`。改用 Gradle property（`-P`）就不必放弃缓存 |
| 偏移量是历史包袱 | `10000 + count` 和 fallback `32640` 是因为旧版本已经发到那个数字附近；新项目不需要 |
| `cancel-in-progress: true` | 对无状态的提交数方案无害（取消不消耗编号），但换成回写方案就必须关闭 |
| 无状态的副作用 | 编号无法"预留"，同一 commit 永远得到同一编号——重建同一个包不会跳号，这既是优点（可复现）也是缺点（无法用编号区分两次构建） |

## 五、编号方案对比（结合参考实现）

| 方案 | 机制 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- | --- |
| **A′. 提交数派生**（借鉴参考项目） | `code = OFFSET + git rev-list --count HEAD` | 零回写、零状态；本地可复现；无并发冲突；`cancel-in-progress` 可开；实现只有一行 | 依赖 `main` 历史不被重写；浅克隆会算错；同 commit 多次构建同编号 | **推荐**（Matter 无存量版本，切换成本为零） |
| A. 回写 `version.properties` | CI 读 → +1 → 提交 → 构建 | 编号是显式记录，跳号可解释；严格单调 | 需机器人推 `main`；并发靠 `concurrency`；失败也烧号 | 备选：需要"编号即预留"的审计性时采用 |
| B. `run_number` 派生 | `code = OFFSET + github.run_number` | 零状态 | workflow 删除重建会重置；本地不可复现；与提交不对应 | 不采用。参考项目只在临时 debug 构建里用它，正式发布不用 |
| C. 外部持久计数器 | repo variable / gist / 服务 | 不写 `main` | 多一套状态源；并发仍靠 `concurrency` | 不采用 |
| D. tag 触发 + 本地分配 | 本地脚本编号并打 tag，CI 只构建发布 | 完全不动现有语义 | "自增"仍在本机 | 降级备用 |

### 已采用的形态

`version.properties` 只保留三个版本段和一个通道标志，编号彻底移出文件：

```properties
VERSION_MAJOR=1
VERSION_MINOR=0
VERSION_PATCH=0
VERSION_SUFFIX=0   # 0 = 正式版，1 = 预发布
```

Gradle 侧显式覆盖优先，否则回落到提交数：

```kotlin
val appVersionCode = providers.gradleProperty("matterVersionCode").orNull?.trim()?.toIntOrNull()
    ?: gitCommitCount
    ?: 1
```

其中 `gitCommitCount` 来自 `providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }` 并包了 `runCatching`（无 git 环境的源码包回落到 1）。CI 仍然显式传 `-PmatterVersionCode`，把它作为权威值。

连带改动（已完成）：

- 删除 `scripts/package-distribution.ps1` 与 `scripts/test-versioning.ps1`。+1 逻辑、文件锁、跳号语义都不再有对象，保留会形成第二套实现。
- `docs/versioning.md` 重写为"提交数派生 + 只从 main 发布 + 不重写历史"，并写入签名与发布步骤。

### 预发布口径

最终采用参考项目的做法（`VERSION_SUFFIX` + tag 计数），而不是手写完整后缀：这样"连续内测"只需要重复触发发布流程，`beta.N` 自动递增，不会因为手写错序号导致重名。`versionName` 的校验正则仍允许 `alpha/beta/rc.N`，兼容手工传入的情况。

本地构建 `VERSION_SUFFIX=1` 时，`N` 无法在不访问 tag 的前提下得到，因此固定使用 `-beta.local`，明确标记"不是线上版本"。

## 六、需要的改动（落地清单）

1. `app/build.gradle.kts`：新增 `signingConfigs.release`，**仅在 `project.hasProperty("RELEASE_STORE_FILE")` 时启用**（CI 追加到 runner 上的 `gradle.properties`），本地无密钥不破坏日常构建。
2. `app/build.gradle.kts`：`splits.abi` 是否启用按需（见 §8）；当前无 native 大依赖，单 universal APK 足够。
3. 删除 `scripts/package-distribution.ps1` 与 `scripts/test-versioning.ps1`：编号不再需要预留与锁，脚本失去对象，保留会成为第二套语义。
4. `.github/workflows/verify.yml`：`push` / `pull_request` 跑 `:app:testDebugUnitTest` + `:app:assembleDebug`，上传报告。
5. `.github/workflows/release.yml`：wrapper（actor + 分支门禁）+ `workflow_call` 调 `auto-release.yml`，`publish` 默认 false。
6. `.github/workflows/auto-release.yml`：`prepare`（算版本）→ `build`（签名 + 构建 + 上传）→ `create_release`（tag + Release）。
7. `.gitignore`：`keystore.properties`、`*.jks`、`*.keystore`。
8. `docs/versioning.md`：按 §5 结论重写编号语义。

## 七、Workflow 落点

三条流水线已按 §5 的结论写入仓库，早期草案不再保留在文档里，以避免和实际文件分叉：

| 文件 | 作用 |
| --- | --- |
| `.github/workflows/verify.yml` | `push` / `pull_request` / 手动：`:app:testDebugUnitTest` + `:app:assembleDebug`，上传测试报告，权限只读 |
| `.github/workflows/release.yml` | 正式版唯一入口：`workflow_dispatch`，`if: github.actor == 'HapeLee' && ref == 'refs/heads/main'`，以 `publish: true` 调用下者 |
| `.github/workflows/auto-release.yml` | 可复用流程：`prepare`（算版本）→ `build`（签名、单测、构建、断言 APK 元数据、上传产物）→ `create_release`（tag + `gh release create`） |

与 §5 草案的差异（实际实现已调整）：

- 发布改用 `gh release create`，不再依赖第三方 release action，tag 由 `gh` 以 `--target "$GITHUB_SHA"` 创建。
- `versionCode` 由 `prepare` 显式传给 Gradle，因此保留 configuration cache 使用空间，不需要 `--no-configuration-cache`。
- 预发布序号 `beta.N` 由 tag 反查递增；正式版若 tag 已存在则直接失败，不覆盖既有发布。
- 构建后断言 `aapt2 dump badging` 的 `versionCode` / `versionName` 与 `prepare` 输出一致，避免"传参被忽略但仍发布"。

本地可验证的部分：`prepare` 的脚本已用 Git Bash 在临时克隆中跑过正式版、预发布（`beta.1`/`beta.2` → `beta.3`）、非法 `VERSION_SUFFIX`、重复键与前导零五类场景，结果符合预期。

2026-10-21 首次实跑（`publish: false`）通过：产物 `Matter-1.0.0-10.apk`，`versionCode=10` 与提交数一致，`apksigner verify` 显示 V2 签名证书 `CN=Matter`，SHA-256 `17ccf357…`。首次实跑暴露并修复了两个问题：

- 仓库里的 `gradlew` 是被裁剪过的脚本，缺少 `APP_HOME` 计算，导致 `CLASSPATH=/gradle/wrapper/gradle-wrapper.jar` 在 Linux runner 上找不到主类。已用 `./gradlew wrapper --gradle-version 9.8.1` 重新生成。
- `gh secret set` 经 PowerShell 管道写入时带入了换行，`base64 -d` 报 `invalid input`。工作流已加 `tr -d '\r\n\t '` 容错，secret 也改为不带换行重新写入。

尚未验证：`publish: true` 的 Release 创建与 tag 推送（首次正式发布时确认）。

## 八、签名、产物与渠道

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 口令 |
| `ANDROID_KEY_ALIAS` | 别名 |
| `ANDROID_KEY_PASSWORD` | key 口令 |

- **密钥必须异地备份**。keystore 丢失意味着同 `applicationId` 再也无法发布升级包，用户只能卸载重装；这比 CI 配置重要得多。
- 首次发布前确认签名指纹，不要先用 debug 密钥发一版再换——那会成为两个不同应用。
- 当前无 native 依赖，**不要照抄 ABI splits**：拆包只有在体积明显受 native 库影响时才值得，否则徒增产物管理与"装错包"风险。真要拆，`isUniversalApk = true` 保底 + `-PenableAbiSplits` 开关（参考项目的做法）。
- AAB 只能用于 Google Play；自建分发只能给 APK。
- 渠道：GitHub Releases（首选，与 tag/源码对应，国内直连可能受限）→ 对象存储镜像 → 内测平台 → Telegram/群组推送（参考项目已实现，Matter 可后续再加）。
- 参考项目的 `update-manifests` 孤儿分支值得记一笔：应用内检查更新只需读取一个静态 JSON，不依赖 GitHub API 配额。Matter 目前没有自动更新功能，**暂不实现**，但可以把清单结构留作后续扩展点。

## 九、风险与未验证项

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| `main` 历史被重写 | squash/rebase 使提交数下降，`versionCode` 回退 | 硬约束"不 rewrite main、只从 main 发布"；或改用回写方案 |
| 浅克隆 | `fetch-depth: 1` 时提交数为 1 | 所有相关 job 显式 `fetch-depth: 0` |
| 本地编号超前 | 本地未推送的提交会让本地算出的 code 大于未来 CI 的值 | 只从 CI 发布；本地构建仅用于调试 |
| 签名密钥泄露/丢失 | 无法升级或被人冒名发布 | base64 secret + 最小权限 + 离线备份 |
| 镜像 502 | aliyun 在 runner 上不稳定，Gradle 会整体禁用失败仓库 | CI 里 `sed` 删除镜像行（参考项目实测有效） |
| runner 缺 SDK 平台 | `compileSdk 37` 较新 | 依赖 AGP 自动下载；失败时显式 `sdkmanager "platforms;android-37"` |
| 未实测 | 上述 YAML 未在真实 runner 跑过；`gradlew` 执行位、`apksigner` 路径、产物文件名均需验证 | 先跑 `publish: false` 的 dry-run |

## 十、分阶段落地与验证

| 阶段 | 内容 | 验证 |
| --- | --- | --- |
| 1 | `verify.yml`：单测 + debug 构建 | CI 绿灯即证明 runner 能编译本工程（最大未知项先行排除） |
| 2 | `auto-release.yml` + `publish: false` | 打印出的 version/code 与本地 `git rev-list --count HEAD` 一致；artifact 可下载 |
| 3 | 接签名 secrets | `apksigner verify` 通过；真机安装后设置页显示 `v<NAME> (构建 <CODE>)` |
| 4 | 打开 `publish: true` | Release 页面出现 APK + SHA256 + 变更日志；prerelease 标记正确 |
| 5 | 删除旧编号脚本，重写 `docs/versioning.md` | 本地 `git rev-list --count HEAD` 与 CI 输出的编号一致 |

验证清单：

- [ ] 两次 CI 构建同一 commit → 同一 `versionCode`（可复现）
- [ ] 新提交后 `versionCode` 严格增大
- [ ] `aapt2 dump badging` 的 `versionCode` 与 `prepare` 输出一致
- [ ] 带 `-beta.1` 的 `VERSION_NAME` 生成 prerelease，tag 与版本名一致
- [ ] 签名 APK 覆盖安装上一版成功；debug 包仍可与 release 共存
- [ ] 无密钥的环境（本地 `verify.yml`）仍能构建

## 十一、参考

- [Android 应用版本管理](https://developer.android.com/studio/publish/versioning)
- [GitHub Actions: concurrency](https://docs.github.com/en/actions/using-jobs/using-concurrency)
- [gradle/actions](https://github.com/gradle/actions)
- 仓库内：[版本管理说明](versioning.md)、[KMP / CMP 架构规范](kmp-cmp-guidelines.md)
- 参考实现：`legado-with-MD3` 的 `app/version.properties`、`app/build.gradle.kts:25-131`、`.github/workflows/auto-release.yml`、`.github/workflows/verify.yml`、`.github/scripts/publish-update-manifest.sh`

核对日期：2026-10-21。
