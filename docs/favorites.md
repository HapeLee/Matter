# 赞过与收藏

卡片右上角与正文顶栏可收藏、取消收藏；「我的 → 赞过与收藏」提供「赞过 / 收藏」两个分栏，支持下拉刷新、分页和错误重试。各分栏分别保存数据和滚动位置，返回正文前选择的分栏会保留。

## 接口依据

One v1.1.6 的 `j3f` 接口使用移动客户端 `favorites/create`、`favorites/destroy`、`favorites`，需要客户端鉴权；其列表项包裹在 `status` 内。Matter 使用 Cookie 登录，收藏读写与点赞 / 关注一致走网页域（写操作受同一套账号级风控，m 站 `statuses/createfav` 已不再使用）：

- `POST https://weibo.com/ajax/statuses/createFavorites`：JSON 体 `{"id": mid}`，`ok == 1` 才算成功。
- `POST https://weibo.com/ajax/statuses/destoryFavorites`：同上（`destory` 是服务端路由本身的拼写）。
- `GET https://weibo.com/ajax/favorites/all_fav?page=1&tid=0&with_total=true`：读取顶层 `data[]`（数组项即微博对象，`deleted` 为「已取消收藏」占位，需丢弃），`total_page` 决定是否还有下一页。
- `GET https://m.weibo.cn/api/likes/byme?page=1`：赞过列表没有写操作，仍用 m 站；读取 `data.statuses` 和下一页 `data.page`，保留微博真实的 `favorited` 字段，不把所有赞过的微博标记为已收藏。

写操作用网页域自己的 `XSRF-TOKEN`（缺失时先 `GET https://weibo.com/` 换一次），与点赞 / 关注共用 `WeiboWriteSession` 的串行提交、凭据缓存和频控冷却；写入前仍先打一次 `GET https://m.weibo.cn/api/config` 校验登录态。

路由与请求体核对自微博网页主包 `https://h5.sinaimg.cn/m/weibo-pro-next/assets/index-Cek3Wq5B.js`（`DeletedToolbar` 的 `destoryFavorites`、`setFavorited` 的 `createFavorites` / `destoryFavorites`，以及收藏页的 `all_fav` 调用）。静态资源哈希可能随微博更新而改变。

收藏分页按 `total_page` 判断，不能按每页条数判断；没有 `total_page` 时按「翻到空页为止」处理，避免把末页当终点漏掉尾页。列表末尾也可返回 `{"ok":0,"msg":"这里还没有内容"}`。

点赞分页使用 `data.page`：实测第一页 18 条、第二页 14 条，仍分别有下一页 2、3。末页即使返回下一页编号，只要 `statuses` 为空、缺省或 null 就停止翻页。错误响应不能当作空列表处理。

## 状态与验证

收藏状态按登录会话共享，接口成功后才更新图标；请求期间禁止重复点击。卡片离开视口后继续完成已发出的操作。取消收藏只重新加载收藏第一页，避免服务端页偏移变化导致漏项，保留点赞分栏。切换账号时重新创建收藏仓库。

2026-10-18 验证：构建、全量单元测试通过，含 8 项收藏测试；真实 Cookie 会话成功读取分页，完成测试微博收藏、列表出现、取消收藏。测试收藏已撤销。设备锁屏，未完成界面点按验证。

同日扩展两个分栏：真实会话读取点赞页 1、2 和末页；新增接口及分栏状态测试，覆盖短页继续加载、登录错误、分栏请求乱序、失败重试、取消旧刷新、收藏变化隔离和分栏恢复。设备仍锁屏，未完成界面点按验证。

2026-10-27 收藏读写迁到网页域：单测覆盖读取分页 / 空列表 / 登录错误、JSON 体与 `X-XSRF-TOKEN`、失败可重试和并发去重，构建与全量单元测试通过。**尚未用真实账号验证**——`all_fav` 的返回结构由网页主包推断（`data[]` + `total_page`），首次真机联调需确认列表能出内容、收藏/取消收藏后列表一致。
