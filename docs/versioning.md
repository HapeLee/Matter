# Matter 版本号与分发

## 两种编号

`version.properties` 维护用户可见版本；内部编号由 Git 提交数派生。两者都写入 APK 的版本元数据与 `BuildConfig`，设置页显示实际构建值。

| 项目 | 示例 | 规则 | 谁决定 |
| --- | --- | --- | --- |
| 显示版本 | `1.0.0` | `VERSION_MAJOR/MINOR/PATCH` 手工修改 | 发布者 |
| 预发布版本 | `1.0.0-beta.3` | `VERSION_SUFFIX=1`，`N` 由已有 tag 递增 | CI |
| 内部编号 `VERSION_CODE` | `42` | `git rev-list --count HEAD` | Git 历史 |
| 设置页正式版 | `v1.0.0 (构建 42)` | 同时便于用户识别与问题反馈 | — |
| 设置页调试版 | `v1.0.0-debug (构建 42)` | `debug` 变体保留 `-debug` 后缀与独立包名 | — |

当前基线为 `1.0.0`、`VERSION_SUFFIX=0`，尚未发布任何版本。

三点关键性质：

- **构建不写任何计数**。同一提交在任何机器上得到同一个 `versionCode`，不需要预留、不需要锁、不会因为构建失败而跳号。
- **`versionCode` 的单调性依赖 `main` 历史不被重写**。`squash`、`rebase`、force push 会让提交数下降，安装包无法覆盖升级。发布分支禁止改写历史。
- **预发布的 `N` 以 tag 为唯一记录**。`1.0.0-beta.3` 对应 tag `v1.0.0-beta.3`，下一个预发布自动是 `beta.4`。

## 本地构建

日常构建、同步、测试直接使用 Gradle，无需任何脚本：

```powershell
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:testDebugUnitTest
```

- `versionCode` 由 Gradle 调用 `git rev-list --count HEAD` 得到；`-PmatterVersionCode=...` 可显式覆盖。
- 浅克隆（`--depth 1`）只会得到 `1`，本地浅克隆需要 `git fetch --unshallow` 后再构建。
- `VERSION_SUFFIX=1` 时本地构建的 `versionName` 固定为 `-beta.local`，因为 `N` 需要联网查询 tag；这个名称不会与线上版本重名。
- 本地构建的编号可能超前于 CI：未推送的提交也计入提交数。因此**本地包只用于调试，不用于分发**。
- 工程当前未启用 configuration cache。若将来启用，`providers.exec` 的结果会被缓存，切换提交后编号可能滞后；此时用 `--no-configuration-cache` 或显式 `-PmatterVersionCode` 覆盖。CI 始终显式传参，不受影响。

## 正式发布

正式版只通过 GitHub Actions 触发，不再有本地分发脚本。在 `main` 分支的 Actions 页面手动运行 **Release** workflow（`workflow_dispatch`）：构建成功后自动打 tag `v<版本名>` 并创建 GitHub Release，附 APK、SHA-256 与 `mapping.txt`。

- 入口 workflow 带 `if: github.actor == 'HapeLee' && github.ref == 'refs/heads/main'`，其他分支或其他人触发不会发布。
- 构建逻辑在被调用的 `auto-release.yml` 中；`publish` 默认 `false`，普通手动运行只产出构建产物，不创建 Release。
- 提交前必须先改 `version.properties`：修复走 `1.0.1`，新增功能走 `1.1.0`，重大调整或兼容性变化走 `2.0.0`。这是面向应用的项目约定，不声称应用 UI 是 SemVer 中定义的公共 API。
- 发布成功后若配置了 `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID`，同一条流水线会把发布说明与 APK 推送到指定频道；未配置则跳过。
- tag 已存在时发布会直接失败并提示升版本，不会静默覆盖同版本发布。

### 预发布

把 `VERSION_SUFFIX` 改为 `1` 后运行同一个 Release workflow：`versionName` 为 `1.1.0-beta.1`，Release 标记为 pre-release；再次运行得到 `beta.2`。发布正式版时把 `VERSION_SUFFIX` 改回 `0`，`versionName` 为 `1.1.0`，编号继续跟随提交数增长，不复位。

## 签名

release 构建仅在存在 `RELEASE_STORE_FILE` 属性时启用签名，否则产出未签名包（可编译，不可安装分发）。

CI 使用的 secrets：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` 的结果 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 口令 |
| `ANDROID_KEY_ALIAS` | key 别名 |
| `ANDROID_KEY_PASSWORD` | key 口令 |

工作流把 keystore 解码到 `$RUNNER_TEMP`，再把四个 `RELEASE_*` 属性追加到 runner 上的 `gradle.properties`，不写入仓库。

当前发布使用外部 keystore `E:\my-release-key.jks`（别名 `key0`，RSA 2048，有效期至 2050-08-10），证书 SHA-256 为 `ddc9ebf7c8a739e8ebb21903ee4363bc94eba114af33b05068431f9c04dcbb96`。密钥文件本身不在仓库内，只存在于发布者的机器与 secrets 中。

本地需要签名时，把同样的四个属性写进用户级 `~/.gradle/gradle.properties`（不被版本控制跟踪），或在命令行传 `-PRELEASE_STORE_FILE=...`。**不要把它们写进仓库的 `gradle.properties`**。

密钥必须异地备份：keystore 丢失意味着同一 `applicationId` 无法再发布升级包，用户只能卸载重装。首次发布前确认签名指纹，不要先用调试密钥发一版再更换。

### 首次配置（Windows）

只在第一次发布前执行一次。已有可用 keystore 时跳过第 1 步，但此后不允许更换。

```powershell
# 1. 生成 keystore（-validity 10000 天；PKCS12 的 key 口令与 store 口令相同）
keytool -genkeypair -v -keystore release.jks -alias matter `
  -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12 `
  -dname "CN=Matter, O=Matter, C=CN"

# 2. 转成单行 base64（不要手动换行）
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\release.jks")) |
  Set-Content -NoNewline release.jks.b64
```

```powershell
# 3. 写入仓库 secrets。PowerShell 不支持 `<` 重定向，base64 用管道传入；
#    三个口令用交互式输入，不写进 shell 历史。
Get-Content release.jks.b64 -Raw | gh secret set ANDROID_KEYSTORE_BASE64 --repo HapeLee/Matter
gh secret set ANDROID_KEYSTORE_PASSWORD --repo HapeLee/Matter   # 交互式输入，不回显
gh secret set ANDROID_KEY_ALIAS --repo HapeLee/Matter
gh secret set ANDROID_KEY_PASSWORD --repo HapeLee/Matter

# 4. 确认名字都在（值不可读）
gh secret list --repo HapeLee/Matter

# 5. 先跑一次不发布的构建，验证签名链路
gh workflow run auto-release.yml --repo HapeLee/Matter --ref main
gh run watch
```

也可以通过网页配置：仓库 **Settings → Secrets and variables → Actions → New repository secret**，逐个粘贴四项。

- `gh` 需要具备 `repo` 与 `workflow` 权限；当前账号已满足。
- `release.jks`、`release.jks.b64` 均被 `.gitignore` 覆盖，确认不要 `git add -f`。
- 备份 `release.jks` 与四项口令（离线介质或密码管理器）。keystore 与口令分离存放。
- 生成后可用 `keytool -list -v -keystore release.jks` 记下 SHA-256 指纹；首次发布后核对 Release 内 APK 的签名指纹是否一致。
- `ANDROID_KEY_ALIAS` 是 `matter`（上例），与 `keytool` 的 `-alias` 一致。

## 失败处理与限制

- 构建失败、测试失败、签名失败都不改变任何计数；重跑得到同一个编号。
- 编号没有"预留"概念，因此也不存在跳号；同一提交重复构建得到同一 `versionCode`，无法用编号区分两次构建，需要区分时看提交哈希。
- `versionCode` 上限为 2,100,000,000；提交数远低于上限，无需处理。
- 换分支或换克隆不影响编号计算，但**并行发布多个分支会产生相同的编号**。当前约定只从 `main` 发布。

## 验证

- 版本注入：`./gradlew.bat :app:generateDebugBuildConfig :app:processDebugResources`，然后检查 `app/build/generated/source/buildConfig/debug/.../BuildConfig.java` 中的 `VERSION_NAME` / `VERSION_CODE`。
- 编号一致性：`git rev-list --count HEAD` 应等于 APK 的 `versionCode`（CI 用 `aapt2 dump badging` 断言）。
- 单测入口：`./gradlew.bat :app:testDebugUnitTest`。
- 正式发布链路无法在本地完整复现，只能通过 Actions 实跑验证；首次发布前先用 `publish: false` 的普通运行确认构建与签名通过。
- 首次实跑（2026-10-21，`publish: false`）：产物 `Matter-1.0.0-10.apk`，`versionCode=10` 等于提交数，`apksigner verify` 通过（V2，证书 `CN=Matter`）。
- 首次正式发布（2026-10-21）：`v1.0.0` / build 16，Release 与 Telegram 频道同时收到产物；证书为 `key0`。

参考：[Android 版本管理](https://developer.android.com/studio/publish/versioning)、[SemVer](https://semver.org/lang/zh-CN/)。机制参考同作者项目 `legado-with-MD3`（`app/version.properties` 与 `.github/workflows/auto-release.yml`）。核对日期：2026-10-21。
