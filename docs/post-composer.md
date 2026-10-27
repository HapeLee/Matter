# 发微博（Post Composer）

> 本轮接入「发微博」的纯文本 + 多图闭环；转发见 [reply-composer.md](reply-composer.md) 的补充说明。
> 端点字段尚未用真实账号完整回归，先读最后一节再改接口。

## 入口与页面

- 入口：动态页右下角的悬浮写作按钮（`MainTabsScreen`，与悬浮底栏同一套几何常量 `bottomBarPadding`）。
- 路由：`Screen.PostComposer`（`Destinations.kt`），全屏编辑器，离开即销毁（NavEntry 级 ViewModel）。
- 页面：`ui/composer/post/PostComposerScreen.kt`
  - 顶栏：取消（有内容时二次确认放弃）、字数 `n/2000`、发布；
  - 正文：`WeiboEmojiTextField`（最多 2000 字，按 UTF-16 代理对安全截断）；
  - 宫格：最多 9 张，3 列，末位是「+」；已上传的图左下角有圆点，上传中的图盖半透明进度；
  - 工具条：表情、添加图片；发布中显示「上传图片 n/m」或「发布中…」。
- 状态：`PostComposerViewModel`
  - 账号在编辑期间固定，切号会清空草稿与图片（`sessions.collect`）；
  - 提交单飞（`isSending`），发布在途时返回键被拦截，避免中途销毁导致状态不明；
  - 图片在**提交时**才读取上传，失败保留草稿与已上传的 pic id，重试不重复上传。

## 数据流

```
PostComposerScreen → PostComposerViewModel → PublishRepository → WeiboApiService
                                         ↘ ReplyImageReader（读取 → 压缩 JPEG）
                                         ↘ WeiboWriteSession（串行 / 800ms / 429 冷却）
```

- `PublishRepository`
  - `upload(session, handle)`：`ReplyImageReader.read` → `picupload.weibo.com` 换 pic id；
  - `publish(session, text, pictureIds)`：`WeiboApiService.publishStatus`；
  - 成功时 `publishedCount` 自增（只增计数，不用一次性事件：编辑器盖在时间线上时，
    时间线那一层已离开组合，`SharedFlow` 的即时事件会丢）。
- 消费方：
  - `MatterNavGraph`（根，始终在组合中）按计数出示「发布成功」提示；
  - `MainTabsScreen` 按计数回到动态页并 `loadTimeline(isRefresh = true)`，
    已处理计数存在 `rememberSaveable`，返回时补一次且不会重复刷新。

## 端点（2026-10-28 网页端抓包核实）

| 操作 | 端点 | 字段 |
| --- | --- | --- |
| 发布 | `POST https://weibo.com/ajax/statuses/update` | `content`、`visible=0`、`share_id` / `vote` / `media` 空串 |
| 发布（带图） | 同上 | 追加 `pic_id`（**JSON 数组字符串**）与 `need_transcode=1` |
| 图片 | `POST https://picupload.weibo.com/interface/upload.php` | 复用评论链路（`ent=miniblog`、`ori=1`、CRC32），响应取 `pic.pid` |

`pic_id` 的真实形态：

```json
[{"type":"image/jpeg","pid":"008veVWOgy1iihejc5f10g308g08cgvv"},
 {"type":"image/png","pid":"008veVWOgy1iihejdglnrj30e00bo42d"}]
```

- 不带图时网页端**根本不发** `pic_id` / `need_transcode`，App 侧同样按条件拼装；
- `type` 必须与图床里的真实格式一致：读取器只把 HEIF/WebP 转 JPEG，JPEG/PNG/GIF 原样透传，
  因此 `ReplyImageContent` 带 `mimeType` 一路传到 `StatusPicture`；
- 发布成功的响应**没有 `ok` 字段**，只有 `{"data":{...新微博...}}`，
  所以走 `readPublishResponse`（有 `data` 即成功），不能用按 `ok==1` 判定的 `readWriteResponse`。

网页端还会带 `fp`（浏览器指纹）与 `client-version` / `server-version` 头。App 无法生成
真实指纹，评论等既有写接口不带也正常，先不伪造；若真机出现 `ok=0` 且文案异常，再考虑。

频控、凭据轮换、`ok=0` 文案透传与 429 归一都和评论 / 转发共用 `WeiboWriteSession`。

## 已知边界

- 未接入：可见性、同步转发、定位、视频；
- 草稿不持久化（离开编辑器即丢弃），与「我的草稿」尚未做；
- `@` / 话题选择器已上移到 `ui/components/composer/`（`ComposerPickerSheet` +
  `ComposerPickerController` + `SuggestionRepository`），评论、发微博、转发三个输入框共用。

## 验证

- `./gradlew.bat :app:testDebugUnitTest`：`PublishApiTest`（表单字段 / Referer / XSRF /
  401 / 429 / 文案透传）、`PublishRepositoryTest`（发布计数、换号拒绝、读取与上传）、
  `PostComposerViewModelTest`（字数截断、9 图上限、顺序上传、失败保留与重试去重、单飞、换号清空）。
- `./gradlew.bat :app:assembleDebug`。
- 真机待验证：键盘避让、宫格增删、返回确认、9 图失败重试、发布后时间线首条是否可见。
