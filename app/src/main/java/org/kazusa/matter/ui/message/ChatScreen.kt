package org.kazusa.matter.ui.message

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kazusa.matter.core.util.LocalOnlineEmojiImages
import org.kazusa.matter.core.util.WeiboTextFormatter
import org.kazusa.matter.data.model.ChatMessage
import org.kazusa.matter.data.model.WeiboEmojis
import org.kazusa.matter.ui.components.EmptyMessage
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.TopBarTitleText
import org.kazusa.matter.ui.components.composer.ComposerSheet
import org.kazusa.matter.ui.components.composer.EmojiDraftState
import org.kazusa.matter.ui.components.composer.OnlineEmojiViewModel
import org.kazusa.matter.ui.components.composer.WeiboEmojiPanel
import org.kazusa.matter.ui.components.composer.WeiboEmojiTextField
import org.kazusa.matter.ui.components.composer.limitEmojiDraft
import org.kazusa.matter.ui.components.composer.rememberComposerEmojiState
import org.kazusa.matter.ui.effect.liquid.LiquidGlassCapsule
import org.kazusa.matter.ui.effect.liquid.LiquidGlassIcon
import org.kazusa.matter.ui.effect.liquid.liquidTextShadow
import org.kazusa.matter.ui.effect.rememberScreenBackdrop
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 收取对方新消息的轮询间隔。
 *
 * 官方客户端的实时私信走 kylin 二进制长连接（gsid 鉴权体系，cookie 拿不到凭据），
 * m.weibo.cn 消息页自身也只有进页拉取；cookie 体系下定时拉最新一页是唯一现实手段。
 * 参考实测低于 20s 的间隔容易触发私信频控，这里取保守的 60s，外加发送成功后立即拉一次。
 */
private const val CHAT_POLL_INTERVAL_MS = 60_000L

/** 私信输入的长度上限；与评论面板一致，超出后按整枚表情 / 代理对截断。 */
private const val ChatMaxInputLength = 2000

/** 输入胶囊里图标按钮的固定尺寸，同时也是触摸热区。 */
private val ChatBarIconSlot = 44.dp

/**
 * 首帧还没量到输入栏真实高度时的兜底让位高度。
 *
 * 44dp 胶囊 + 胶囊内 4dp 上下内边距 + 悬浮边距 6/10dp，量到真实值后立即被替换。
 */
private val DefaultInputBarHeight = 72.dp

/**
 * 会话详情：与某个人的私信历史，支持发送文本 / 图片。
 *
 * 列表用 `reverseLayout` 从底部开始，索引 0 是最新一条；向上滚动到接近末尾时用 `max_id` 继续取更早的页。
 * 发送走 webim（api.weibo.com）表单协议（契约见 [org.kazusa.matter.core.network.WeiboApiService.sendChatMessage]）；
 * 发送乐观插入 pending 气泡，失败回滚并经 Snackbar 提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    peerUid: String,
    peerName: String,
    onBackClick: () -> Unit,
    onOpenImage: (url: String) -> Unit
) {
    val viewModel: ChatViewModel = koinViewModel { parametersOf(peerUid, peerName) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val backdrop = rememberScreenBackdrop()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.loadIfNeeded()
        viewModel.startCometIfNeeded()
    }

    // 发送失败等一次性事件：Snackbar 展示后即消费
    LaunchedEffect(viewModel) {
        viewModel.events.collect { snackbarHostState.showSnackbar(it) }
    }

    // 页面可见期间轮询收取对方新消息；离开页面即随协程取消
    LaunchedEffect(viewModel) {
        while (isActive) {
            delay(CHAT_POLL_INTERVAL_MS)
            viewModel.poll()
        }
    }

    LaunchedEffect(listState, viewModel) {
        snapshotFlow {
            val layout = listState.layoutInfo
            layout.totalItemsCount to (layout.visibleItemsInfo.lastOrNull()?.index ?: 0)
        }.distinctUntilChanged().collect { (total, lastVisible) ->
            val state = viewModel.uiState.value
            if (state.canLoadMore && total > 0 && lastVisible >= total - 3) viewModel.loadMore()
        }
    }

    // 自己发的消息（含乐观插入的气泡）一定滚到最新；对方的新消息只在本来就停在底部时跟随，
    // 免得用户翻历史时被突然拽回底部。reverseLayout 下索引 0 就是视觉最底部的让位项。
    val newestMessage = uiState.messages.firstOrNull()
    LaunchedEffect(newestMessage?.key) {
        val newest = newestMessage ?: return@LaunchedEffect
        val atBottom = listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset == 0
        if (newest.fromMe || atBottom) {
            listState.animateScrollToItem(0)
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            // 读原图字节属于阻塞 IO，放 IO 线程；发送状态由 ViewModel 自己管理
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                }
                if (bytes != null) viewModel.sendImage(bytes)
            }
        }
    }

    // 输入区（胶囊 + 可能展开的表情面板）的高度随内容变化，列表底部按实测高度留白
    val density = LocalDensity.current
    var composerHeight by remember { mutableStateOf(DefaultInputBarHeight) }

    // 表情：编辑器状态与面板显隐；最近使用与所选分组按页面存活期保存
    val emojiViewModel: OnlineEmojiViewModel = koinViewModel()
    val onlineEmojis by emojiViewModel.state.collectAsStateWithLifecycle()
    var recentEmojis by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selectedEmojiGroup by rememberSaveable { mutableStateOf("weibo") }
    val editorFocus = remember { FocusRequester() }
    val emojiPanel = rememberComposerEmojiState(editorFocus)
    val draftEditor = remember(viewModel) { EmojiDraftState(input) }
    val emojiImages = LocalOnlineEmojiImages.current
    // 逐键把草稿同步给 ViewModel（换页保留草稿），发送失败时再靠 receive 回填
    LaunchedEffect(draftEditor, viewModel) {
        snapshotFlow { draftEditor.field.text.toString() }.collect { viewModel.updateInput(it) }
    }
    LaunchedEffect(input) { draftEditor.receive(input) }

    fun insertEmoji(code: String) {
        draftEditor.field.edit {
            val start = selection.min
            replace(start, selection.max, code)
            val limited = limitEmojiDraft(toString(), emojiImages, ChatMaxInputLength)
            if (limited.length < length) delete(limited.length, length)
            selection = TextRange((start + code.length).coerceAtMost(length))
        }
        recentEmojis = (listOf(code) + recentEmojis).distinct().take(28)
    }

    // 「输入胶囊 → 目标气泡」的飞行动画：起点是胶囊实测位置，终点是气泡落位后的实测位置。
    // 起点由输入栏 onGloballyPositioned 提供，终点要等乐观气泡真正测量完，所以这是一个两段式流程。
    var inputBounds by remember { mutableStateOf(Rect.Zero) }
    var sendFlight by remember { mutableStateOf<SendFlight?>(null) }
    // 初值必须是 0（= 停在输入胶囊位置）：若从 1 开始，落点一量到的那一帧会先在终点闪一下再回起点
    val sendFlightProgress = remember { Animatable(0f) }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }

    // 飞行中的气泡按「最新一条、自己发的、文字相同」定位：发送被串行化，400ms 内不可能出现两条同文案的我方消息，
    // 这样即便服务端确认后把本地 id 换成真实 id，飞行中的气泡也不会因为 key 变化而提前显形。
    val flyingKey = sendFlight?.let { flight ->
        uiState.messages.firstOrNull()
            ?.takeIf { it.fromMe && it.text == flight.text }
            ?.key
    }

    // 只按「落点是否量到」重启：服务端确认会换掉消息 id，进而换掉 flyingKey，
    // 若把 flyingKey 也当 key，飞行途中会被中途重启、从输入框重新飞一次
    LaunchedEffect(sendFlight?.target) {
        val flight = sendFlight ?: return@LaunchedEffect
        val target = flight.target
        if (target == null) {
            // 目标还没量到：最多等 600ms 就放弃动画，绝不把气泡一直藏着
            delay(600)
            if (sendFlight?.target == null) sendFlight = null
            return@LaunchedEffect
        }
        sendFlightProgress.snapTo(0f)
        sendFlightProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
        )
        // 先归零再收尾：中间没有挂起点，下一次重组只会看到 sendFlight == null，
        // 不会出现「ghost 弹回输入框再消失」的一帧
        sendFlightProgress.snapTo(0f)
        sendFlight = null
    }

    // 聊天列表是 reverseLayout：firstVisibleItem 的滚动量衡量的是「离最新消息有多远」，
    // 直接喂给顶栏会出现「滑到最新消息（触底）玻璃又消失、标题压在气泡上」。
    // 这里改看内容是否超出视口 —— 超出了就说明有气泡压在顶栏下方，玻璃常显；没占满则保持通透。
    val topBarScrimOffset = remember(listState) {
        derivedStateOf {
            if (listState.canScrollForward || listState.canScrollBackward) Float.MAX_VALUE else 0f
        }
    }
    val topBarScrimProvider = remember(topBarScrimOffset) { { topBarScrimOffset.value } }

    Scaffold(
        // 内容区自己做避让：列表必须铺满整屏才能滚到透明顶栏下方，顶栏玻璃才有可折射的底图
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ImmersiveTopBar(
                scrollOffsetProvider = topBarScrimProvider,
                animationLabel = "ChatTopBarScrim",
                title = { scroll ->
                    TopBarTitleText(
                        text = viewModel.peerName.ifBlank { "私信" },
                        scroll = scroll,
                        backdrop = backdrop,
                        onBackClick = onBackClick
                    )
                }
            )
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                // 浮在输入胶囊上方：避开导航栏，也不压住发送按钮
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 96.dp)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                // 导航栏与键盘取并集在底部统一避让：列表视口和输入胶囊一起上移，不会互相遮挡
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                // 飞行气泡用 root 坐标定位，这里记下容器自身的位置用于换算成本地偏移
                .onGloballyPositioned { overlayOrigin = it.positionInRoot() }
        ) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .background(MaterialTheme.colorScheme.surface),
                contentPadding = PaddingValues(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // reverseLayout 下索引 0 落在视觉最底部：这条空 item 就是输入区的让位
                item(key = "input_clearance") {
                    Spacer(modifier = Modifier.height(composerHeight))
                }

                items(
                    items = uiState.messages,
                    key = { it.key },
                    contentType = { "message" }
                ) { message ->
                    val flying = message.key == flyingKey
                    MessageBubble(
                        message = message,
                        onOpenImage = onOpenImage,
                        // 飞行期间真气泡先隐形但保留占位，落点才不会跟着列表跳动
                        hidden = flying,
                        onBubbleMeasured = if (flying) {
                            { rect ->
                                sendFlight?.let { flight ->
                                    if (flight.target == null) sendFlight = flight.copy(target = rect)
                                }
                            }
                        } else {
                            null
                        },
                        // 新气泡淡入、其余气泡平滑让位；图片确认回来的替换也会带着位移动画
                        modifier = Modifier.animateItem()
                    )
                }

                if (uiState.messages.isEmpty()) {
                    item(key = "empty") {
                        EmptyMessage(
                            message = uiState.errorMessage
                                ?: if (uiState.isInitialLoading) "正在加载聊天记录…" else "还没有聊天记录，发一条私信吧",
                            isLoading = uiState.isInitialLoading,
                            buttonText = if (uiState.errorMessage != null) "重试" else null,
                            onButtonClick = if (uiState.errorMessage != null) {
                                { viewModel.retry() }
                            } else {
                                null
                            },
                            modifier = Modifier.fillParentMaxHeight(0.6f)
                        )
                    }
                }

                if (uiState.isLoadingMore) {
                    item(key = "loading_more") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        }
                    }
                }

                if (uiState.messages.isNotEmpty() && uiState.errorMessage != null) {
                    item(key = "load_error") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = uiState.errorMessage.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            TextButton(onClick = { viewModel.retry() }) {
                                Text("重试")
                            }
                        }
                    }
                }

                if (uiState.messages.isNotEmpty() && !uiState.hasMore && !uiState.isLoadingMore) {
                    item(key = "end") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "没有更早的消息了",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 反向列表的最后一条 item 出现在视觉最上方：给透明顶栏留出让位
                item(key = "top_clearance") {
                    Spacer(modifier = Modifier.height(padding.calculateTopPadding()))
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // 量的是「胶囊 + 表情面板」整块，面板展开时列表让位要跟着变高
                    .onSizeChanged { size -> composerHeight = with(density) { size.height.toDp() } }
            ) {
                ChatInputBar(
                    draft = draftEditor,
                    sending = uiState.sending,
                    emojiVisible = emojiPanel.visible,
                    backdrop = backdrop,
                    focusRequester = editorFocus,
                    onToggleEmoji = { emojiPanel.toggle() },
                    onEditorFocused = { emojiPanel.onEditorFocused() },
                    onSendClick = {
                        // 编辑器持有 TextFieldState，发送时取一次快照直接交给 ViewModel
                        val sent = viewModel.send(draftEditor.publish())
                        if (sent != null) {
                            // 显式清空：外部草稿本来可能是空串，单靠 receive 回显比对清不掉
                            draftEditor.clear()
                            if (!inputBounds.isEmpty) {
                                sendFlight = SendFlight(text = sent.text, start = inputBounds)
                            }
                        }
                    },
                    onPickImage = {
                        emojiPanel.dismiss()
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp)
                        // padding 之后测量，拿到的是胶囊本身而不是含边距的整块，飞行起点才和胶囊等宽
                        .onGloballyPositioned { inputBounds = it.boundsInRoot() }
                )

                // 表情面板占据底部：与键盘互斥，展开时输入胶囊随之上移
                AnimatedVisibility(visible = emojiPanel.visible) {
                    ComposerSheet(
                        applyWindowInsets = false,
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                    ) {
                        WeiboEmojiPanel(
                            recentEmojis = recentEmojis,
                            online = onlineEmojis,
                            selectedGroup = selectedEmojiGroup,
                            onGroupChange = { selectedEmojiGroup = it },
                            onOpenOnline = emojiViewModel::openCatalog,
                            onRefreshOnline = emojiViewModel::refreshCatalog,
                            onInstall = emojiViewModel::install,
                            onEmojiClick = { insertEmoji(it) }
                        )
                    }
                }
            }

            // 飞行气泡：从输入胶囊的实测矩形插值到目标气泡的实测矩形，宽度随进度从胶囊宽度收到气泡宽度
            val flight = sendFlight
            val flightTarget = flight?.target
            if (flight != null && flightTarget != null) {
                SendFlightGhost(
                    text = flight.text,
                    start = flight.start,
                    target = flightTarget,
                    progress = sendFlightProgress,
                    origin = overlayOrigin
                )
            }
        }
    }
}

/** 一次发送的飞行参数；[target] 要等乐观气泡完成测量后才补齐。 */
private data class SendFlight(
    val text: String,
    val start: Rect,
    val target: Rect? = null
)

/**
 * 飞行中的气泡替身。
 *
 * 起止矩形都由真实布局测量而来：起点是悬浮输入胶囊，终点是气泡落位后的矩形，
 * 中间按 [progress] 插值宽度 / 高度 / 位置 —— 所以视觉上就是「从输入框飞出去、落到气泡大小」。
 * 真气泡在此期间只是 `alpha = 0`，仍占着位置，落点不会因为列表让位而漂移。
 */
@Composable
private fun SendFlightGhost(
    text: String,
    start: Rect,
    target: Rect,
    progress: Animatable<Float, AnimationVector1D>,
    origin: Offset
) {
    val rect = lerpRect(start, target, progress.value)
    if (rect.width <= 0f || rect.height <= 0f) return
    val density = LocalDensity.current

    Surface(
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .offset {
                IntOffset(
                    (rect.left - origin.x).roundToInt(),
                    (rect.top - origin.y).roundToInt()
                )
            }
            .size(
                width = with(density) { rect.width.toDp() },
                height = with(density) { rect.height.toDp() }
            )
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Clip
            )
        }
    }
}

private fun lerpRect(start: Rect, end: Rect, fraction: Float): Rect = Rect(
    left = start.left + (end.left - start.left) * fraction,
    top = start.top + (end.top - start.top) * fraction,
    right = start.right + (end.right - start.right) * fraction,
    bottom = start.bottom + (end.bottom - start.bottom) * fraction
)

/**
 * 底部输入栏：与正文页「写评论」悬浮栏同一套液态玻璃胶囊外观。
 *
 * 胶囊横跨可用宽度，左侧选图、中间输入、右侧发送，整体悬浮在消息列表之上（列表通过
 * `layerBackdrop` 作为玻璃底图），因此透出的是身后滚动的聊天气泡而不是一块实心底板。
 */
@Composable
private fun ChatInputBar(
    draft: EmojiDraftState,
    sending: Boolean,
    emojiVisible: Boolean,
    backdrop: LayerBackdrop?,
    focusRequester: FocusRequester,
    onToggleEmoji: () -> Unit,
    onEditorFocused: () -> Unit,
    onSendClick: () -> Unit,
    onPickImage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val text = draft.field.text.toString()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    // 用户点回输入框时收起表情面板，和正文回复面板的行为一致
    LaunchedEffect(focused) { if (focused) onEditorFocused() }

    LiquidGlassCapsule(
        backdrop = backdrop,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            ChatBarIconButton(
                imageVector = Icons.Outlined.Image,
                contentDescription = "发送图片",
                enabled = !sending,
                onClick = onPickImage
            )
            ChatBarIconButton(
                imageVector = if (emojiVisible) Icons.Outlined.Keyboard else Icons.Outlined.EmojiEmotions,
                contentDescription = "表情",
                tint = if (emojiVisible) MaterialTheme.colorScheme.primary else onSurface,
                onClick = onToggleEmoji
            )

            Box(modifier = Modifier.weight(1f)) {
                if (text.isEmpty()) {
                    Text(
                        text = "发送私信…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // 与编辑器相同的内边距，占位符才不会和光标错位
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 11.dp)
                    )
                }
                WeiboEmojiTextField(
                    state = draft.field,
                    interactionSource = interactions,
                    maxLines = 4,
                    maxLength = ChatMaxInputLength,
                    textShadow = liquidTextShadow(),
                    modifier = Modifier
                        .fillMaxWidth()
                        // 与 44dp 图标按钮对齐：图标贴在胶囊底边，单行文字正好与图标垂直居中
                        .padding(horizontal = 4.dp, vertical = 11.dp)
                        .focusRequester(focusRequester)
                )
            }

            if (sending) {
                Box(
                    modifier = Modifier.size(ChatBarIconSlot),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                }
            } else {
                ChatBarIconButton(
                    imageVector = Icons.AutoMirrored.Rounded.Send,
                    contentDescription = "发送",
                    enabled = text.isNotBlank(),
                    tint = if (text.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        onSurfaceVariant.copy(alpha = 0.5f)
                    },
                    onClick = onSendClick
                )
            }
        }
    }
}

/** 玻璃胶囊里的图标点击区：固定 44dp 热区，不额外铺水波纹，避免在玻璃上叠一层方块。 */
@Composable
private fun ChatBarIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Box(
        modifier = Modifier
            .size(ChatBarIconSlot)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        LiquidGlassIcon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    onOpenImage: (String) -> Unit,
    modifier: Modifier = Modifier,
    hidden: Boolean = false,
    onBubbleMeasured: ((Rect) -> Unit)? = null
) {
    // 乐观插入的气泡先半透明，服务端确认后淡回实色：既表达「还在发」，也不会有生硬的闪现
    val bubbleAlpha by animateFloatAsState(
        targetValue = if (message.pending) 0.6f else 1f,
        animationSpec = tween(durationMillis = 220),
        label = "ChatBubbleAlpha"
    )
    val imageUrl = message.imageUrl
    val showMeta = hasMetaContent(message)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (hidden) 0f else bubbleAlpha),
        horizontalArrangement = if (message.fromMe) Arrangement.End else Arrangement.Start,
        // 图片消息的时间贴在图片外侧，和图片底边对齐
        verticalAlignment = Alignment.Bottom
    ) {
        if (imageUrl != null) {
            // 图片不套气泡：原图本身就是内容，再垫一层底色只会变成「卡片」而不像聊天记录。
            // 时间放到图片外侧——自己发的在左、对方发的在右，视线仍落在图片上
            if (message.fromMe && showMeta) {
                ChatMetaContent(message = message)
                Spacer(modifier = Modifier.width(6.dp))
            }
            ChatImage(
                imageUrl = imageUrl,
                onOpenImage = onOpenImage,
                onMeasured = onBubbleMeasured
            )
            if (!message.fromMe && showMeta) {
                Spacer(modifier = Modifier.width(6.dp))
                ChatMetaContent(message = message)
            }
        } else {
            TextBubble(message = message, onMeasured = onBubbleMeasured)
        }
    }
}

/** 文字气泡：自己的靠右、对方的靠左，时间贴在自己这一侧的气泡内底部。 */
@Composable
private fun TextBubble(
    message: ChatMessage,
    onMeasured: ((Rect) -> Unit)?
) {
    Surface(
        shape = if (message.fromMe) {
            RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
        } else {
            RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
        },
        color = if (message.fromMe) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier
            .widthIn(max = 280.dp)
            // 飞行落点必须量气泡本身（不是整行），后续 ghost 才和气泡严丝合缝
            .then(
                if (onMeasured != null) {
                    Modifier.onGloballyPositioned { onMeasured(it.boundsInRoot()) }
                } else {
                    Modifier
                }
            )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (message.text.isNotBlank()) {
                // 私信正文也是微博文本：[微笑] 这类表情代码要渲成图片，而不是显示字面代码
                val emojiImages = LocalOnlineEmojiImages.current
                // 本地资源 / 已下载表情包优先，缺的再用接口下发的官方图片地址兜底
                val mergedEmojiImages = remember(message.emojiImages, emojiImages) {
                    if (message.emojiImages.isEmpty()) {
                        emojiImages
                    } else {
                        emojiImages + message.emojiImages.filterKeys { code ->
                            code !in emojiImages && WeiboEmojis.imageAssets[code] == null
                        }
                    }
                }
                val primary = MaterialTheme.colorScheme.primary
                val formatted = remember(message.text, mergedEmojiImages, primary) {
                    WeiboTextFormatter.formatWeiboContent(
                        rawText = message.text,
                        primaryColor = primary,
                        emojiImages = mergedEmojiImages
                    )
                }
                Text(
                    text = formatted.annotatedString,
                    inlineContent = formatted.inlineContent,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            BubbleMetaRow(message = message)
        }
    }
}

/**
 * 私信图片本体：不套气泡，压到 256dp 以内铺开原图。
 *
 * 附件缩略图只有 240px（≈68dp），铺开会小得看不清，所以用原图地址、由 Coil 按目标尺寸降采样。
 * [onMeasured] 供发送飞行动画量落点（量图片本身，不是整行）。
 */
@Composable
private fun ChatImage(
    imageUrl: String,
    onOpenImage: (String) -> Unit,
    onMeasured: ((Rect) -> Unit)?
) {
    AsyncImage(
        model = imageUrl,
        contentDescription = "图片私信",
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .widthIn(max = 256.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onOpenImage(imageUrl) }
            .then(
                if (onMeasured != null) {
                    Modifier.onGloballyPositioned { onMeasured(it.boundsInRoot()) }
                } else {
                    Modifier
                }
            )
    )
}

/** 时间统一由 [formatChatTime] 分档（今天 / 昨天 / 今年 / 更早）；发送中没有服务端时间也会占位显示转圈。 */
private fun hasMetaContent(message: ChatMessage): Boolean =
    message.pending || formatChatTime(message.timestampMillis, message.timeText) != null

/**
 * 气泡底部的时间行：贴着自己这一侧对齐，发送中把时间前的位置换成小转圈。
 */
@Composable
private fun ColumnScope.BubbleMetaRow(message: ChatMessage) {
    if (!hasMetaContent(message)) return

    Row(
        modifier = Modifier
            .align(if (message.fromMe) Alignment.End else Alignment.Start)
            .padding(top = 2.dp)
    ) {
        ChatMetaContent(message = message)
    }
}

/** 时间 + 发送中的转圈；文字消息放在气泡内底部，图片消息放在图片外侧。 */
@Composable
private fun ChatMetaContent(message: ChatMessage) {
    val time = formatChatTime(message.timestampMillis, message.timeText)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (message.pending) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        if (time != null) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
