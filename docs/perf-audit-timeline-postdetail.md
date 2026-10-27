# 性能排查：首页（时间线）与微博正文

排查方式：静态代码路径分析（Compose 状态依赖链 + 数据层调用链）。
当前环境无法运行真机 profile，文中"影响"为路径推断，每条都给了**可验证的测量方法**。

> **状态**：文中标注的 P0/P1 条目基本已落地，清单见文末「六、本轮已落地的改动」
> （已跑过编译 + 单测）。仅剩 GPU 侧的玻璃层收敛需要真机数据支撑，见「仍未处理」。
> 数据层的阻塞点另见「七、数据层阻塞点审计」。

---

## 一、结论摘要

两条页面是同一个病根：**状态粒度太粗 + 动画状态在组合期被读**。具体三类：

| 类别 | 首页 | 微博正文 |
| --- | --- | --- |
| 状态粒度 | 点赞/收藏态以「整张 Map + static CompositionLocal」下发，一次点赞写两次、整屏读取者失效 | 楼中楼态是一张**整 Map**，且在**每一行内**取读；任意一条展开/翻页 → 全部可见评论行重组 |
| 动画状态进组合 | `ImageMorphState.isPresent` 读 `progress.value`，被 `TimelineScreen`/收藏按钮在组合期读取 → 形变全程每帧整页重组 | 同左（`PostDetailScreen:817`）+ 骨架屏 pulse 每帧重组 + `animateContentSize` 套在 LazyColumn 上 |
| 单次组合成本 | 每张卡片：富文本 8~10 个 `Regex` 新建、`SelectionContainer`、`SideEffect` 写 LruCache、`listKey` 反复分配 | 每行评论：`commentRepliesOf()` 每次分配 `LinkedHashMap`+List、`remember(comment)` 深比较、`replyText.replace` 每次重算 |

另有两条一次性但体感明显的：**点击卡片时主线程 `SharedPreferences.commit()`**、**进入正文时浏览历史落库串在评论请求之前**。

---

## 二、首页（时间线）

### P0-1 一次点赞 = 两次根级重组 + 全部可见卡片失效

```13:23:app/src/main/java/org/kazusa/matter/ui/components/LikeStates.kt
/** 点赞状态的只读快照（导航根从 [LikeRepository.postStates] 收集一次后下发）。 */
val LocalPostLikeStates = staticCompositionLocalOf<Map<String, LikeState>> { emptyMap() }
```

```126:127:app/src/main/java/org/kazusa/matter/ui/navigation/MatterNavGraph.kt
    val postLikeStates by likeRepository.postStates.collectAsStateWithLifecycle()
    val commentLikeStates by likeRepository.commentStates.collectAsStateWithLifecycle()
```

- `MatterNavGraph` 在根收集这两个 Map，并作为 `CompositionLocalProvider` 的参数传下去 → Map 一变，**根作用域重组**，整棵树重新走一遍（`NavDisplay`、`MainTabsScreen`、`HorizontalPager` 全部参与）。
- 因为是 `staticCompositionLocalOf`，值变化会**无条件**让所有读取者失效：每个可见卡片的 `PostLikeActionButton`、每条评论行都会重组。
- `LikeRepository.togglePost` 一次点赞写两次 Map（`pending=true` 乐观态 → `pending=false` 确认态，见 `LikeRepository.kt:108`、`113`），所以**一次点赞触发两轮**。

**修复**：把"这一条的状态"下沉到消费者手里。
1. `LocalPostLikeStates` 改 `compositionLocalOf`（订阅粒度到读者）；
2. 更好：卡片只订阅自己 id 的派生态，避免 Map 整体替换：
   ```kotlin
   // PostLikeActionButton 内部
   val states = LocalPostLikeStates.current
   val likeState by remember(states, key) { derivedStateOf { states[key] } }
   ```
   `derivedStateOf` 结果相等即不失效，只有点赞的那一条会重组。
3. 把 `pending` 与最终态合并为**一次**写入（乐观态带 `pending=true`，成功只需更新计数），或让 `pending` 走独立轻量流。

### P0-2 `ImageMorphState.isPresent` 把动画状态读进了组合期

```111:111:app/src/main/java/org/kazusa/matter/ui/media/morph/ImageMorphState.kt
    val isPresent: Boolean get() = isOpen || progress.value > 0.001f
```

```482:482:app/src/main/java/org/kazusa/matter/ui/timeline/TimelineScreen.kt
                        userScrollEnabled = (morphState?.isPresent != true && uiState.pendingRestoreIndex == null),
```

- `progress` 是 `Animatable`，`progress.value` 是 snapshot state。在**组合期**读它 = 订阅整个动画的每一帧。
- 打开/收起大图（约 350~420ms）期间，`TimelineScreen` 每帧重组一次，连带 `LazyColumn` 的 content lambda（含全部 item key lambda、`posts.subList`）重建。
- 同类问题：`FavoriteActions.kt:53` 的 `enabled = ... && morphState?.isPresent != true`（正文页/卡片收藏按钮每帧重组）、`PostDetailScreen.kt:817`。
- 对比正确写法：`ImageMorphOverlay.kt:122` 用了 `derivedStateOf { morphState.isPresent }`。

**修复**（两行级改动，收益最大）：
```kotlin
// ImageMorphState 内新增只翻转一次的状态，不挂在进度上
var overlayActive by mutableStateOf(false)   // open() 置 true，收起动画结束置 false
```
或退一步，所有组合期读取点统一改成：
```kotlin
val present by remember(morphState) { derivedStateOf { morphState?.isPresent == true } }
```
`derivedStateOf` 只在 false↔true 翻转时失效，不再逐帧。

### P0-3 每张卡片各自订阅收藏流

```88:92:app/src/main/java/org/kazusa/matter/ui/components/FavoriteActions.kt
    val repository = LocalFavoritesRepository.current
    val state = repository?.state?.collectAsStateWithLifecycle()?.value
    val id = post.favoriteId
    val busy = state != null && id in state.pending
    val favorited = state?.overrides?.get(id) ?: post.isFavorited
```

- 每个可见卡片的 `FavoriteActionButton` 都启动**一份独立的 Flow 收集**（`collectAsStateWithLifecycle` = `LaunchedEffect` + 生命周期观察者 + 协程）。一屏 8~10 张就是 8~10 个收集器，且每张卡片进入组合都要新建一个。
- 任何一次收藏写入，这些收集器逐个收到并各自重组。
- 这也和项目自己的约定冲突：`MatterNavGraph.kt:125` 的注释写着"这里收集一次下发给列表项，避免每张卡片各自订阅"。

**修复**：照点赞的做法，在导航根收集一次，用 `CompositionLocal` 下发 `FavoriteState`；按钮内 `derivedStateOf { overrides[id] to (id in pending) }` 只取自己需要的两个字段。

### P1-4 滚动时每张新进入组合的卡片的主线程成本

单张卡片首次组合时（`WeiboPostCard` → `PostCardContent`）：

| 成本 | 位置 | 说明 |
| --- | --- | --- |
| 8~10 个 `Regex` 编译 | `WeiboTextFormatter.kt:65,80,81,85,92,93,108,254,258` | 每次调用都 `Regex(...)`，无缓存；约 0.3~1ms/条 |
| `SelectionContainer` | `WeiboPostCard.kt:317` | 注册选择区 + 额外 pointer 修饰，列表里逐卡开启代价明显 |
| `SideEffect` 写 LruCache | `WeiboPostCard.kt:236` | **每次重组都执行**，且 `getVerifyType()` 在 `:238` 与 `:282` 各算一次 |
| `listKey` 现算字符串 | `WeiboModels.kt:71` | getter 每次 `idStr ?: mid ?: id.toString()`，被 key lambda、`distinctBy`、`indexOfFirst` 反复调用 |

**修复**：
1. `WeiboTextFormatter` 的 `Regex` 全部提为 `private val` 顶层常量（单点改动，全仓受益）；
2. 再进一步：`LruCache<String, FormattedWeiboText>` 以 `text + primaryColor` 为键。滚动往返时同一段文本不再重解析；
3. `SideEffect` → `LaunchedEffect(post.user?.id, avatar, screenName)`；
4. `WeiboStatusDto.listKey` 改 `by lazy`（`WeiboCommentDto` 同样）；
5. 信息流卡片的 `SelectionContainer` 改为"长按进入选择模式后启用"，或仅正文页开启。

### P1-5 液态玻璃的双层 backdrop + 相邻页同时组合

```229:292:app/src/main/java/org/kazusa/matter/ui/main/MainTabsScreen.kt
    val bottomBarScrollConnection = remember(pagerState, timelineListState, scrollTracker) { ... }
    val bottomBarBackdrop = if (runtimeShaderSupported) rememberLayerBackdrop() else null
    val timelineBackdrop = if (runtimeShaderSupported) rememberLayerBackdrop() else null
```

- `HorizontalPager` 整体挂了 `layerBackdrop`，`TimelineScreen` 的 `LazyColumn` 又挂一层自己的 backdrop（`TimelineScreen.kt:485`），底栏 + 顶栏各自采样。滚动时整页内容要**离屏录制**并按帧模糊/折射。
- `beyondViewportPageCount = 1`：停在首页时「热门」页也在组合中。而 `HotFeedViewModel.init` 直接 `loadCategories()`（`HotFeedViewModel.kt:67`），启动即发请求 + 下载封面图，和首页抢首屏带宽与主线程。
- `barTranslationY`/`buttonReveal`/`menuProgress.value` 在组合期读（`MainBottomBarLayer.kt:233, 244, 308`），底栏显隐弹簧动画期间整层每帧重组。

**修复（按性价比排序）**：
1. 首页滚动不需要"实时采样整页"时，把 backdrop 从 pager 挪到**静态背景层**（或降低采样频率），并只保留底栏一级 backdrop；
2. 热门页改成"首次可见时才加载"（`isActive` 首次为 true 时触发），不要用 `init` 提前打请求；
3. 底栏这三个动画值改为 `State` 传入，在 `graphicsLayer`/叶子作用域读。

### P1-6 顶栏在组合期读动画值

```133:142:app/src/main/java/org/kazusa/matter/ui/components/ImmersiveTopBar.kt
    val animatedAlpha = animateFloatAsState(targetValue = targetAlphaState.value, ...)
    val scrollStateHolder = TopBarScrollState(
        targetAlpha = targetAlphaState.value,
        alpha = animatedAlpha.value
    )
```

注释说"渐变透明度在绘制阶段读取，滚动过程中不驱动渐变层重组"（`:149` 的 `graphicsLayer` 确实做到了），但 `:134` 和 `:141` 又在组合期读了同一个值：

- 滚动前 96dp 内，每个滚动像素都让 `ImmersiveTopBar` 重组；
- 淡入的 180ms 里每帧重组，进而 `topBarTitleStyle(alpha)`（`TextStyle.lerp`）→ `.copy(shadow = liquidTextShadow())`（`ImmersiveTopBar.kt:326`）每帧新建 TextStyle → **标题文字每帧重新排版** + 玻璃胶囊重新着色。

**修复**：`TopBarScrollState` 改成持有 `State<Float>` 而不是 `Float`，把 `topBarTitleStyle`/padding 的下沉到叶子 composable 内部读；或标题字号切换改用 `graphicsLayer { scaleX/scaleY }` 近似。

### P1-7 点击卡片时主线程同步落盘

```38:42:app/src/main/java/org/kazusa/matter/data/cache/TimelinePositionManager.kt
        if (synchronous) {
            editor.commit()
        } else {
            editor.apply()
        }
```

`TimelineScreen.kt:181` 的 `saveCurrentPosition(true)` 在 `handlePostClick` 里同步调用 → `SharedPreferences.commit()`（fsync）跑在**主线程**，正好落在共享元素转场起手那一帧。`ON_PAUSE`/`onDispose` 也走同步路径。

**修复**：点击路径改 `apply()`（`commit()` 只在 `ON_STOP` 保留）；点击时真正需要的只是"idx / offset / postId"三个值，`apply` 足够。

### P2-8 `loadTimeline` 在 `_uiState.update {}` 里 launch 协程

```331:345:app/src/main/java/org/kazusa/matter/ui/timeline/TimelineViewModel.kt
                        _uiState.update { state ->
                            val combined = (state.posts + newPosts).distinctBy { it.listKey }
                            val snapshot = scrollSnapshot

                            viewModelScope.launch(Dispatchers.IO) {
                                timelineRepository.saveCache(...)
                            }
```

`MutableStateFlow.update` 的 lambda 在 CAS 失败时会**重试**，副作用会重复执行 → 可能对同一份列表重复 JSON 编码 + 落盘（`saveCache` 无节流、每次最多 150 条）。应把 launch 移到 `update` 外面。

---

## 三、微博正文

### P0-1 楼中楼状态：一张整 Map + 在行内取读 + 每次取读都分配

```163:165:app/src/main/java/org/kazusa/matter/ui/detail/PostDetailScreen.kt
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 楼中楼状态按评论 id 存在 ViewModel 里；这里收集一次，按 key 取用
    val repliesStates by viewModel.repliesStates.collectAsStateWithLifecycle()
```

```479:485:app/src/main/java/org/kazusa/matter/ui/detail/PostDetailScreen.kt
                                            CommentThread(
                                                comment = comment,
                                                repliesProvider = { comment ->
                                                    repliesStates[viewModel.repliesKeyOf(comment)]
                                                        ?: viewModel.commentRepliesOf(comment)
                                                },
```

```97:97:app/src/main/java/org/kazusa/matter/ui/detail/CommentThread.kt
    val state = repliesProvider(comment)
```

- 这个 provider 在**每一行自己的作用域里**被调用，读的是同一个 `State<Map<...>>`。所以任意一条评论的楼中楼 `loading`/翻页/失败替换整张 Map 时，**所有可见评论行一起重组**。
- 未展开过楼中楼的评论（常态，一屏 9/10 条）走 `?:` 分支 → `commentRepliesOf()`（`PostDetailViewModel.kt:720`）→ `initialCommentReplies()` → `mergeCommentReplies(emptyList(), comment.comments, ...)`（`CommentRepliesState.kt:48`）**每次新建 LinkedHashMap + List + copy 一个 data class**。`repliesKeyOf` 还每次 `comment.id.toString()` 分配。
- 即：滚动或任意一行重组时，每行都在做一次 O(回复数) 的分配 + 深比较。

**修复**：
1. 把 provider 换成"行内只订阅自己的 key"：
   ```kotlin
   val state by remember(comment.id, states) { derivedStateOf { states[keyOf(comment.id)] } }
   ```
   `derivedStateOf` 结果相等不失效 → 只有被展开的那一行重组；
2. 更彻底：`repliesStates: Map<String, StateFlow<CommentRepliesState>>`，行内 `collectAsStateWithLifecycle` 只订自己；
3. 首次展示的空状态不要每次现算：`_repliesStates` 初始化时就把「接口下发的预览」灌进去，或 `commentRepliesOf` 用 `HashMap` 记忆化；
4. `repliesKeyOf` 改成 `WeiboCommentDto` 上的 `by lazy` 字段。

### P0-2 评论点赞态 + 正文点赞态走 static CompositionLocal

```105:106:app/src/main/java/org/kazusa/matter/ui/detail/CommentThread.kt
    val likeState = likeRepository?.commentState(comment, LocalCommentLikeStates.current)
        ?: LikeState(comment.isLiked, parseInteractionCount(comment.likeCount))
```

与首页 P0-1 同源：`staticCompositionLocalOf<Map<Long, LikeState>>`，值一变所有读过它的评论行失效，一次点赞写两次 Map。

**修复**：同上，`compositionLocalOf` + 行内 `derivedStateOf { states[comment.id] }`。

### P0-3 列表级 `animateContentSize()` + 每行 `SelectionContainer`

```346:354:app/src/main/java/org/kazusa/matter/ui/detail/PostDetailScreen.kt
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                            .background(MaterialTheme.colorScheme.surface)
                            .animateContentSize(),
```

- `animateContentSize` 挂在 LazyColumn 上：首屏到达、切页签、翻页、展开楼中楼……任何内容尺寸变化都会启动多轮 measure 动画，并且和 LazyColumn 自身的滚动测量互相干扰。淡入效果已经有 `contentFade`，这个动画是纯额外开销。
- `SelectionContainer` 每行一个（`PostDetailScreen.kt:1480`），评论区滚动时是持续成本。

**修复**：删掉列表级 `animateContentSize()`；评论行的文本选择改为"长按进入选择模式"再挂 `SelectionContainer`。

### P1-4 每行重组的固定开销

```1324:1333:app/src/main/java/org/kazusa/matter/ui/detail/PostDetailScreen.kt
    val commentFormatted = remember(replyText, primaryColor, onMentionClick, onTopicClick, onlineEmojiImages) {
        WeiboTextFormatter.formatWeiboContent(...)
    }
    val metadata = remember(comment) { formatCommentMetadata(comment) }
```

- 富文本解析本身被 `remember` 保护住了（key 里那几个 lambda 在 Kotlin 2.4 强跳过模式下会被记忆化，通常不会每次都失效）——这点和常见猜想不同，不必优先动。
- 真正每次重组都跑的是：
  - `remember(comment)` 的 key 必须是**整个 `WeiboCommentDto` 的深比较**（含 `comments` 预览列表）；
  - `formatCommentMetadata`（`PostDetailScreen.kt:1503`）在**首次/换实例**时编译 3 个 `Regex`（`formatHtmlText` 内部 1 个 + `:1506` 1 个 + `:1518` 的 `formatSubtitle` 同款）；
  - `CommentRow` 顶部 `replyText = rawReplyText.replace("图片评论","").trim()`（`:1310`）每次组合都做字符串分配。
- `CommentThread` 每次重组新建 3 个 lambda 给 `CommentRow`（`:163-166`），行内 `CommentRow` 因此难以跳过。

**修复**：`remember(comment)` 的 key 改为 `comment.id / comment.source / comment.regionName / comment.createdAt` 四个值；`Regex` 提为顶层常量；`replyText`、`isPostAuthor` 用 `remember(comment.id, comment.text, ...)` 固定；`CommentRow` 参数改成一个 `onEvent: (CommentEvent) -> Unit` 的稳定引用。

### P1-5 骨架屏 pulse 每帧重组

```1058:1067:app/src/main/java/com/.../PostDetailScreen.kt
    val transition = rememberInfiniteTransition(label = "commentSkeleton")
    val pulse by transition.animateFloat(...)
```

```1092:1092:.../PostDetailScreen.kt
            .graphicsLayer { alpha = pulse }
```

注释写"alpha 走绘制阶段，脉冲动画不会引起这三行的重组"，但 `by` 拆包在**组合期**读了 `pulse`，而它又是 `CommentSkeletonRow(pulse = pulse, ...)` 的参数 → 900ms 一个来回，每帧重组 3 行。低端机上正好挤占评论首屏回来的排版预算。

**修复**：不要 `by` 拆包，把 `State<Float>` 传进去，行内 `graphicsLayer { alpha = pulse.value }`。

### P1-6 楼中楼非 lazy 嵌套

```204:226:app/src/main/java/org/kazusa/matter/ui/detail/CommentThread.kt
                val visibleReplies = if (expanded) state.replies else state.replies.take(3)
                visibleReplies.forEach { reply ->
                    androidx.compose.runtime.key(reply.listKey) {
                        CommentThread(... depth = depth + 1 ...)
```

- 展开后 n 条回复（各自带头像 `AsyncImage`、富文本、`SelectionContainer`）全塞进**同一个 LazyColumn item**；该 item 高度巨大，滚过时单次布局成本极高，且外层还套着 `animateContentSize()`（`:202`）。
- `take(3)` 每次组合新建 List。

**修复**：展开后把回复扁平化到主列表（`depth` 作为 item 参数），让 LazyColumn 复用回收；至少先去掉 item 内的 `animateContentSize`。

### P1-7 首屏：浏览历史落库串在评论请求前面

```868:877:app/src/main/java/org/kazusa/matter/ui/detail/PostDetailViewModel.kt
                    is ApiResult.Success -> {
                        ...
                        _uiState.update { it.copy(post = res.data, errorMessage = null) }
                        PostDetailCache.put(res.data)
                        // 本地浏览历史：同一条微博只保留最近一次
                        recordHistory(res.data)
                        val resolvedId = targetIdOf(res.data)
                        if (commentsRequestedForId != resolvedId) loadComments(resolvedId)
```

`recordHistory` → `BrowsingHistoryRepository.recordPost` 串行做了：HTML 正则清洗 + 整条 DTO JSON 序列化 + Room `findByStatusId` + upsert + `countForUid` + 可能的裁剪删除，全部在 IO 线程但**被 await**。评论首屏请求因此被推迟这几毫秒到几十毫秒（冷启动 DB 首开更久），用户看到的是"正文已出、评论区还在骨架屏"。

**修复**：
```kotlin
viewModelScope.launch { runCatching { recordHistory(res.data) } }
loadComments(resolvedId)
```

### P2-8 其他

- `PostDetailCache` 是无上限 `ConcurrentHashMap`（`PostDetailCache.kt:15`），每条微博最多存 3 个 key，只在切账号时清空 → 长时间浏览后内存持续增长。
- `isCommentsLoading` 每次加载先 `true` 后 `false`（`PostDetailViewModel.kt:675`、`713`），每次都是**整页 uiState 重组**并重建 `LazyListScope`（含全部 key lambda）。可拆成独立 flow 只让骨架屏订阅。
- `SideEffect { contentFade.syncKey(selectedTab to commentsVersion) }`（`PostDetailScreen.kt:236`）每次组合新建 `Pair`——量小，但可改成两个 Int 参数。

---

## 四、建议的落地顺序

1. **先测量，别靠猜**（半天）：
   - Android Studio Layout Inspector → 勾选 *Show recomposition counts*，滚动首页/正文各 10s；
   - 或 `Macrobenchmark` + `FrameTimingMetric` 记录 `frameDurationCpuMs` 的 P90；
   - GPU：Perfetto/Studio Profiler 看 `DrawFrame` 是否被 glass shader 占满。
2. **第一批（改动用小时计、收益最大、风险最低）**
   - `isPresent` 退出组合期（首页 + 正文 + 收藏按钮）—— 治"打开大图/收起大图时整页卡一下"；
   - `SelectionContainer` 从评论行 / 信息流卡片上撤下（改长按启用）；
   - 删掉正文 `LazyColumn` 的 `animateContentSize()`；
   - 顶层常量化 `WeiboTextFormatter` / `formatCommentMetadata` 的 `Regex`；
   - `saveCurrentPosition(true)` → `apply()`；
   - `recordHistory` 改为不 await。
3. **第二批（状态粒度，改动稍大）**
   - 点赞/评论点赞/收藏：`staticCompositionLocalOf` → `compositionLocalOf` + 行内 `derivedStateOf`；
   - 楼中楼 `repliesStates` 按 id 拆 key，`commentRepliesOf` 记忆化。
4. **第三批（结构性）**
   - 液体玻璃 backdrop 层级收敛 + 滚动采样策略；
   - 热门页延迟加载；
   - 楼中楼扁平化到主列表；
   - 顶栏 / 底栏动画值改 `State` 下沉。

---

## 五、本次未发现问题的部分（免得误改）

- 数据层没有轮询、没有主线程 IO/JSON；`BrowsingHistoryRepository` 已 `withContext(Dispatchers.IO)`。
- `PostDetailViewModel` 四个分区各自持有 token + cancel，没有旧响应覆盖新数据的问题；`load()` 有 `started` 去重。
- 回复草稿输入已拆到独立 `replyState` 流，打字不带动整页。
- 三个子页签的 `items()` 都带 `key` + `contentType`，没有嵌套 Lazy 滚动；`AsyncImage` 都有尺寸约束；视频在 item 里只渲染封面，没有初始化播放器。
- 时间线滚动位置刻意不写进 `uiState`（`TimelineViewModel` 注释与实现一致），这条设计是对的，别退回去。

---

## 六、本轮已落地的改动（2026-10-28）

验证：`.\gradlew.bat :app:compileDebugKotlin` 与 `.\gradlew.bat :app:testDebugUnitTest` 均通过（BUILD SUCCESSFUL）。
未做真机帧率对比，下面「预期」是路径推断。

### 1. 动画状态退出组合期

- `ImageMorphState.kt`：新增 `rememberImageMorphPresent(morphState)`（`derivedStateOf` 包裹 `isPresent`），
  并在注释里写明为什么不能直接读 `isPresent`。
- `TimelineScreen.kt:482` 的 `userScrollEnabled`、`FavoriteActions.kt` 的 `enabled` 改用该派生态。
- 复查后发现 `WeiboPostCard` / `PostDetailScreen` 里的 `isPresent` 读取都在**点击回调内**（延迟读），
  本来就不订阅组合，因此未改动。

> 预期：打开/收起大图时，首页不再整页逐帧重组。

### 2. 点赞 / 评论点赞 / 收藏 / 转发态改成按 id 精确订阅

- `LikeStates.kt`：`LocalPostLikeStates` / `LocalCommentLikeStates` 由「Map + staticCompositionLocalOf」
  改为「`State<Map>` + `compositionLocalOf`」，新增 `LocalFavoriteState`。
- `RepostLocals.kt`：`LocalRepostStates` 同样改为 `State<Map>`。
- `MatterNavGraph.kt`：四个快照都在根收集一次后下发 **State 对象**（不再 `by` 拆包）。
- `WeiboPostCard.kt`（点赞按钮、转发按钮）、`PostDetailScreen.kt`（正文点赞、转发分栏）、
  `CommentThread.kt`（评论点赞）消费方一律 `derivedStateOf { states.value[key] }`。

> 预期：一次点赞从「根重组 + 全部可见卡片/评论行失效」收敛到「只有那一个按钮重组」。

### 3. 收藏按钮不再每卡片订阅一条 Flow

- `FavoriteActions.kt`：去掉 `repository.state.collectAsStateWithLifecycle()`（每个可见卡片一个收集器），
  改用根下发的 `LocalFavoriteState` + 按 id 的 `derivedStateOf`。

> 预期：一屏 8~10 张卡片少 8~10 个收集协程与生命周期观察者；收藏写入不再让全部卡片重组。

### 4. 正文页评论列表

- `CommentThread.kt`：楼中楼状态改为 `remember(comment) { derivedStateOf { repliesProvider(comment) } }`。
  这是评论页滚动的主要开销来源——原来任意一条楼中楼展开/翻页会替换整张 Map，所有可见评论行重组。
  规则复查过：`publishedReplies` 的每次写入都伴随 `_repliesStates` 更新（`PostDetailViewModel.kt:536-542`），
  所以以 Map 变化为失效边界不会漏更新。
- `PostDetailViewModel.commentRepliesOf`：没有「刚发布未确认」的回复时直接返回接口下发的预览，
  不再无条件多 merge 一次（省掉每行每次取用的 `LinkedHashMap` + List 分配）。
- `PostDetailScreen.kt`：删掉 `LazyColumn` 上无意义的 `animateContentSize()`（它只动画这个本来就
  `fillMaxSize()` 的容器边界，淡入已由 `contentFade` 负责）。
- 骨架屏 `pulse` 改为传 `State<Float>`，行内 `graphicsLayer` 取 `.value`，不再每帧重组三行。
- `CommentRow`：`replyText` / `metadata` 的 `remember` key 从「整个 DTO 深比较」收窄到真正参与计算的字段。

### 5. 每个卡片 / 每行评论的固定开销

- `WeiboTextFormatter.kt`：9 个 `Regex` 全部提为 `object` 内常量（原来每次调用现编译 8~10 个）。
- `PostDetailScreen.kt`：`formatCommentMetadata` 的「IP属地/发布于/来自」正则、`formatSubtitle` 的
  剥标签正则提为常量；`WeiboPostCard.kt` 同样处理。
- `WeiboPostCard.kt`：头像/昵称写 `UserAvatarCache` 由 `SideEffect`（每次重组都执行）改为
  `LaunchedEffect(userId, avatar, screenName, verifyType)`；`getVerifyType()` 从两次计算改为一次。
- `WeiboModels.kt`：`WeiboStatusDto.listKey` / `WeiboCommentDto.listKey` 由 getter 改为 `by lazy`
  （它们被 LazyColumn key lambda、`distinctBy`、`indexOfFirst` 反复读）。

### 6. 点击与首屏

- `TimelineScreen.kt`：点击卡片/评论进入正文时的滚动位置落盘由 `commit()`（主线程 fsync）改为 `apply()`；
  `ON_PAUSE` / `ON_STOP` 与 `onDispose` 仍保留同步写。
- `PostDetailViewModel.requestDetail`：浏览历史落库改为独立协程、不 await，评论首屏请求不再被
  「正则清洗 + JSON 序列化 + Room 往返」推后。
- `TimelineViewModel`：翻页成功后的 `saveCache` 从 `_uiState.update {}` 内部移到外面，
  避免 `update` 的 CAS 重试导致同一份列表被重复编码/写盘。

### 7. 列表项不再逐条挂 `SelectionContainer`

- 新增 `ui/components/CardTextSelection.kt`：`LocalCardTextSelectionEnabled`（默认 `true`）+ `CardTextSelection` 包装。
- `WeiboPostCard`：主正文、转发原文两处改用 `CardTextSelection`。
- `MainTabsScreen`：在分页的 `CompositionLocalProvider` 里对信息流页下发 `false` —— 一屏十几张卡片
  不再各自拥有一份选区注册器、`DisposableEffect` 订阅与手势节点。
- `PostDetailScreen`：评论正文不再挂 `SelectionContainer`（评论列表是这一页最大的列表）；
  主正文 / 转发原文的选区能力保留。

> 交互变化：信息流卡片与评论正文不再支持长按选词复制；正文页正文仍可复制。
> 若要恢复，删掉 `MainTabsScreen` 里那行 `provides false` 即可（其他页面本来就是默认开启）。

### 8. 顶栏槽位可跳过重组

- `ImmersiveTopBar.kt`：`TopBarScrollState` 由 `@Immutable data class`（每次重组建新实例）
  改为 `@Stable class`，内部持有 `State<Float>` 并在 `remember` 里固化实例。
- 此前每次重组都新建 holder → title / actions / navigation 三个插槽 lambda 的捕获值逐帧变化 →
  槽位内容无法跳过重组，滚动前 96dp 内相当于整根顶栏逐像素重建
  （`TextStyle.lerp` + `.copy(shadow)` + 文字重排 + 玻璃重新着色）。
- 现在：滚动只在淡入淡出的 180ms 动画窗口内驱动叶子组件重组，动画结束后滚动不再引起顶栏重组。

### 9. 热门页延迟加载

- `HotFeedViewModel`：去掉 `init { loadCategories() }`，改为 `ensureLoaded()`（幂等）。
- `HotFeedScreen`：新增 `isPageVisible` 参数，`LaunchedEffect(isPageVisible)` 首次为 true 时才加载。
- `MainTabsScreen`：传 `pagerState.currentPage == page`。
- 单测：`HotFeedViewModelTest` 的辅助函数改为「构造 + ensureLoaded」，并新增
  `doesNotRequestUntilThePageBecomesVisible` 覆盖「不可见时不发请求 / 重复可见只加载一次」。
  该文件现在 5 个用例全绿。

> 预期：停在首页时不再有热门分类 + 热门首页流两条请求与首页抢首屏带宽，
> 代价是首次滑到「热门」时多等一次分类请求。

### 10. 液态玻璃 backdrop：试过收敛成单层，不可行，已回退

原设计是两层：`HorizontalPager` 一层（供悬浮底栏 + 发微博按钮采样）、每个页面各自一层
（供该页顶栏采样）。曾尝试去掉 Pager 那层、让底栏直接采样「当前页那一份」，**已回退**，原因：

- **横划时底栏模糊会黏住不动**。横划时屏幕上滑动的是两页内容，而某一页自己的那层是静止的；
  底栏采它 → 采样区域对不上真实内容，模糊像被钉住。
  底栏的样本必须来自「横向滚动之上」的那一层，这是结构要求，不是可选优化。
- **顶栏不能采样 Pager 那层**：顶栏本身在 Pager 的录制范围内，采它等于采自己（自反馈）。

保留的改进（无行为变化）：`layerBackdrop` 的挂载点从列表上移到页面内容区容器
（`TimelineScreen` 的内容 Box、`HotFeedScreen` / `SearchScreen` 的 `PullToRefreshBox`），
这样非列表状态（加载 / 报错 / 空）也有内容可采样；热搜页顺带不再需要「只让当前那份写入」防双写。

**若要继续降 GPU 开销**，可行的折中是「只在横划期间挂 Pager 那层」：静止时底栏采样当前页那层、
横划时切到 Pager 层。静止时两层的底栏区域像素相同，理论上切换不可见，但需要真机确认
切换瞬间不会有一帧空样本。未实现。

### 仍未处理

1. **`animateFloatAsState(targetValue = ...)` 仍让 `ImmersiveTopBar` 自身在顶部 96dp 内按像素重组**
   （`animateFloatAsState` 必须在组合期拿到目标值）。当前已把这次重组的代价压到只剩函数体重建，
   槽位内容可跳过；要进一步消除需要自定义动画驱动。

---

## 七、数据层阻塞点审计

排查方式：沿「会话 → 请求 → 解析 → 落库 / 缓存」四条链路逐个检查线程上下文。

### 已修复：会话读取在每个请求上做 3 次 AndroidKeyStore 解密

这是数据层最大的主线程阻塞点，链路如下：

```126:148:app/src/main/java/org/kazusa/matter/core/datastore/AuthPreferences.kt
val sessionFlow: Flow<UserSession> = context.authDataStore.data
    .catch { ... }
    .map { prefs ->
        UserSession(
            sub = decodeCredential(prefs[Keys.SUB]),
            subp = decodeCredential(prefs[Keys.SUBP]),
            rawCookie = decodeCredential(prefs[Keys.RAW_COOKIE]),
```

- Flow 的 `map` 跑在**收集者上下文**。收集者有 29 处，其中 `MatterNavGraph.kt:132`、
  `ProfileScreen.kt:89` 是 `collectAsStateWithLifecycle`（主线程），而十几处
  `sessionFlow.first()`（`TimelineRepository.kt:29`、`HotFeedRepository.kt:25/46`、
  `WeiboApiService.kt:142/216/271`…）都在 `viewModelScope` = **主线程**的请求协程里。
- 每次发射做 3 次 `KeystoreCredentialCipher.decrypt`，而 `obtainKey()` 每次都要
  `KeyStore.getInstance("AndroidKeyStore").load(null)` —— 跨进程调 Keystore 服务，典型数毫秒。
- 结果：冷启动首帧要等 3 次解密（导航根 `session == null` 时是 loading 态）；
  之后**每个请求**都要再付一次（时间线一次刷新里 `getTimeline` + `loadCache` + `saveCache`
  各读一次会话，最多 9 次解密）。

**改动**（`AuthPreferences.kt` / `KeystoreCredentialCipher.kt` / `UserSession.kt`）：

1. `sessionFlow` 末尾加 `.flowOn(Dispatchers.Default)` —— 一处生效，覆盖全部 29 个调用点；
2. `KeystoreCredentialCipher` 缓存 `SecretKey`（`@Volatile` 字段），不再每次解密都 load keystore；
   失效兜底路径（`freshKey` 的删旧建新、`decrypt` 失败）都会清空缓存，语义不变；
3. `UserSession.cookieHeader` 改 `by lazy` —— 原来每次访问都重新 split/map/join 整串 Cookie。

> 预期：请求发起前的主线程开销从「3 次 Keystore 往返 + 一次字符串重组」降到「一次后台解密」。

### 第二批已修复

| # | 位置 | 改动 |
| --- | --- | --- |
| 1 | `BrowsingHistoryDao.kt` / `BrowsingHistoryRepository.kt` | 把「查重 → 插入/更新 → count → 取最旧 id → 删除」收成一个 `@Transaction` 方法 `upsertAndTrim`（KSP 已确认为 `performInTransactionSuspending` 生成包装）：5 次事务提交变 1 次，裁剪与写入原子，避免并发写入插进 count/delete 之间多删。会话读取（`currentUid()`）放在事务外，不在持锁时等 DataStore/解密。 |
| 2 | `UserProfileViewModel.kt` | `recordUser(p)` 改为独立协程、不 await（与正文页一致）：主页头部与动态不再等正则清洗 + DTO 序列化 + 多次 Room 往返。 |
| 3 | `TimelinePositionManager.kt` | `commit()` 改为投递到内部 IO scope（`synchronous` 语义仍是「立即落盘」，只是不再占主线程 fsync）；`getPosition` 改为 `suspend` + `withContext(IO)`，冷启动不再在主线程触发 SharedPreferences 整文件读盘。`TimelineRepository.getScrollPosition` 随之 suspend。 |

### 第三批已修复

| # | 位置 | 改动 |
| --- | --- | --- |
| 5 | `WeiboApiService.kt`（`loadAllGroups`） | `mutex.withLock { 网络请求 }` → **单飞（single-flight）**：锁只保护「读缓存 / 挂上在途任务」，请求在锁外跑；同 Cookie 的并发调用共享同一个 `Deferred`，任务结束即摘除（失败不共享）。请求挂在服务自有的 `CoroutineScope` 上，发起方页面被取消不会连带取消别的等待方。失败路径不再「第二个调用方再排一次」，最坏情况从 2× 超时降到 1×。 |
| 6 | `GroupRepository.kt` | 新增 `currentVisibility()`：`hiddenGroupIdsFlow.first()` + `customOrderFlow.first()` 两次顺序收集合成一次 `combine(...).first()`，三处调用点统一（缓存命中 / 请求成功 / 失败兜底）。 |
| 7 | `FavoritesApi.kt` / `MessageApi.kt` | `decodeFavoritesPage` / `decodeLikedPostsPage` / `parseNoticePage` / `parseChatPage` 全部 `withContext(Dispatchers.IO)`：逐条 DTO 解码不再留在调用方主线程，与 `KtorClientFactory` 的约定一致。 |

### 第四批已修复

| # | 位置 | 改动 |
| --- | --- | --- |
| 4 | `WeiboWriteSession.kt` | 写闸门的间隔改**自适应**：常规 `250ms`，本会话一旦踩到频控就退避到 `800ms`，退避期间连续成功 5 次再恢复 `250ms`。冷却（60s）与凭据缓存逻辑不变。单测新增 `writesAreSpacedByTheNormalIntervalBeforeAnyRateLimit`、`rateLimitBacksOffTheSpacingForFollowingWrites`、`spacingRecoversAfterConsecutiveSuccesses`（该文件 8 个用例全绿）。 |
| 4b | `FavoritesRepository.kt` + `WeiboPostCard.kt` / `FavoriteActions.kt` / `PostDetailScreen.kt` / `PostDetailFloatingBar.kt` | 收藏原本是**非乐观**更新（图标等请求回来才变），所以之前只能靠转圈告知「正在提交」。改为乐观更新 + 失败按单条回滚（不整体替换 map），然后点赞 / 收藏 / 评论点赞的 `pending` 期间一律不再转圈：直接显示结果态图标，重复点击由 `enabled` / `!pending` 拦住。 |
| 8 | `EmojiRepository.kt` | `updateImages` 改 `suspend` + `withContext(Dispatchers.Default)`；同时去掉每个表情一个 `listOf(code) + aliases` 的临时表（几千个表情就是几千次多余分配）。 |
| 9 | `BrowsingHistoryEntity.kt` / `MatterDatabase.kt` | 新增 `(uid, create_at)` 复合索引（列表按 `uid` 取页 + `create_at DESC`、裁剪按 `create_at ASC` 找最旧）。DB 升到 v3，用 **`autoMigrations = [AutoMigration(from = 2, to = 3)]`**（不是手写 Migration）：schema 文件 `1/2/3.json` 都在，Room 编译期按 `2.json` 推导 DDL，杜绝手写 SQL 与 `@Entity(indices=…)` 漂移。v2 的建表迁移保持手写不动（线上已生效）。 |

> 注意：Room 3 对「加索引」生成的自动迁移并不是一句 `CREATE INDEX`，而是**重建表**
> （`CREATE TABLE _new_…` → `INSERT SELECT` → `DROP` → `RENAME` → 重建两个索引），
> 见 `app/build/generated/ksp/.../MatterDatabase_AutoMigration_2_3_Impl.kt`。
> 浏览历史表的 `json` 列存的是整条 DTO，单账号上限 500 条，所以这次升级首次打开数据库
> 会复制一遍数据（一次性、在后台线程）。若更在意这次升级的开销，可换回手写
> `CREATE INDEX IF NOT EXISTS`（增量、不动表数据），代价是 SQL 要自己保证与 schema 一致。

### 关于 `WeiboWriteSession`

它不是纯限速器，而是写路径（点赞 / 收藏 / 关注 / 评论 / 删除 / 私信）的**统一闸门 + 凭据缓存**，
做三件事：

1. **凭据缓存**：写路径全走 `weibo.com`，需要 `XSRF-TOKEN`（作 `X-XSRF-TOKEN` 头）+ 合并过
   `Set-Cookie` 的 Cookie，两者必须成对；按「来源 Cookie」缓存，换号或 401 时 `invalidate()`。
2. **串行 + 自适应最小间隔**：一条 mutex 把所有写操作排队；间隔在未被限流时是 250ms，
   踩到频控后退避到 800ms，连续成功 5 次恢复。避免连点被判为连点，又不至于正常连点就排长队。
3. **频控冷却**：识别到 429 或「操作频繁」文案后，本地冷却 60s 直接拒绝，
   避免用户每点一次都在服务端延长冷却。

### 数据层结论

到此，数据层里「明确在主线程上做重活」的点已清零：会话读取、DTO 解码、缓存读写、DB 写入、
滚动位置持久化、表情表重建全部在后台线程；请求之间没有互相阻塞的临界区。
再往下的收益只能在真机上用 Perfetto 定位（排版 / 绘制 / 首帧区间）。
- **C. 不动**：现有行为是「如实反映排队状态」，配合文案提示也说得过去。

### 做得对的地方（别改坏）

`KtorClientFactory.bodyText/decodeBody/jsonBody` 全部 `withContext(IO)`（文件头注释还写明了原因）；
`TimelineCacheManager` 读写全在 IO；`PostDetailViewModel` 的四个分区各有 token + cancel；
`MatterDatabase` 单例、无 `allowMainThreadQueries`；`SessionCookieSnapshot` 已是同步快照设计
（`MatterApplication.kt:35` 在 IO 收集）—— 上面第 1 条修完后它就是自然的内存快照。
