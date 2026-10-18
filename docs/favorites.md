# 赞过与收藏

卡片右上角与正文顶栏可收藏、取消收藏；「我的 → 赞过与收藏」提供「赞过 / 收藏」两个分栏，支持下拉刷新、分页和错误重试。各分栏分别保存数据和滚动位置，返回正文前选择的分栏会保留。

## 接口依据

One v1.1.6 的 `j3f` 接口使用移动客户端 `favorites/create`、`favorites/destroy`、`favorites`，需要客户端鉴权；其列表项包裹在 `status` 内。Matter 使用 Cookie 登录，因此采用微博当前移动网页实现：

- `GET https://m.weibo.cn/api/config`：写入前检查 `data.login` 并获取最新 `data.st`，合并响应的 Cookie。
- `POST https://m.weibo.cn/api/statuses/createfav`：表单 `id`、`st`。
- `POST https://m.weibo.cn/api/statuses/destroyfav`：表单 `id`、`st`。
- `GET https://m.weibo.cn/api/container/getIndex?containerid=230259&openApp=0&page=1`：读取 `data.cards[].mblog`；兼容 `card_group`。
- `GET https://m.weibo.cn/api/likes/byme?page=1`：读取 `data.statuses` 和下一页 `data.page`，保留微博真实的 `favorited` 字段，不把所有赞过的微博标记为已收藏。

路径核对自微博官方网页资源 [微博菜单实现](https://h5.sinaimg.cn/m/weibo-lite/js/collect-main-page-profile-statusLite.e90e10b5.js) 与 [收藏页实现](https://h5.sinaimg.cn/m/weibo-lite/js/collect.58de57ec.js)。静态资源哈希可能随微博更新而改变。

收藏分页必须使用 `cardlistInfo.page`，不能按每页条数判断：实测第 2 页只有 9 条，仍返回下一页 3。列表末尾可返回 `{"ok":0,"msg":"这里还没有内容"}`。服务端收藏列表的 `title_top` 实测误标为「我的赞」，页面使用固定分栏标题。

点赞分页使用 `data.page`：实测第一页 18 条、第二页 14 条，仍分别有下一页 2、3。末页即使返回下一页编号，只要 `statuses` 为空、缺省或 null 就停止翻页。错误响应不能当作空列表处理。

## 状态与验证

收藏状态按登录会话共享，接口成功后才更新图标；请求期间禁止重复点击。卡片离开视口后继续完成已发出的操作。取消收藏只重新加载收藏第一页，避免服务端页偏移变化导致漏项，保留点赞分栏。切换账号时重新创建收藏仓库。

2026-10-18 验证：构建、全量单元测试通过，含 8 项收藏测试；真实 Cookie 会话成功读取分页，完成测试微博收藏、列表出现、取消收藏。测试收藏已撤销。设备锁屏，未完成界面点按验证。

同日扩展两个分栏：真实会话读取点赞页 1、2 和末页；新增接口及分栏状态测试，覆盖短页继续加载、登录错误、分栏请求乱序、失败重试、取消旧刷新、收藏变化隔离和分栏恢复。设备仍锁屏，未完成界面点按验证。
