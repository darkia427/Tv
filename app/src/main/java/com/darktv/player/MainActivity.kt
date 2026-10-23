package com.darktv.player

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        background = Color(0xFF020617),
                        surface = Color(0xFF0F172A),
                        primary = Color(0xFF3B82F6)
                    )
                ) {
                    DarkTvApp()
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun DarkTvApp() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val allChannels = remember { ChannelRepository.channels }
    var selectedCategory by remember { mutableStateOf("همه") }
    var searchQuery by remember { mutableStateOf("") }

    val filteredChannels = remember(selectedCategory, searchQuery) {
        allChannels.filter { ch ->
            val matchCat = selectedCategory == "همه" || ch.category == selectedCategory
            val matchQuery = searchQuery.isEmpty() ||
                    ch.fullName.contains(searchQuery, ignoreCase = true) ||
                    ch.codeName.contains(searchQuery, ignoreCase = true)
            matchCat && matchQuery
        }
    }

    var currentChannel by remember { mutableStateOf(allChannels.first()) }
    var isUsingFallbackPort by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusBannerText by remember { mutableStateOf<String?>(null) }

    // Player UI Controls State
    var isSidebarOpen by remember { mutableStateOf(true) }
    var showHudOverlay by remember { mutableStateOf(true) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var isMuted by remember { mutableStateOf(false) }

    val dataSourceFactory = remember { NetworkHelper.createDataSourceFactory() }

    val exoPlayer = remember {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 1500, 3000)
            .build()

        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build().apply {
                playWhenReady = true
            }
    }

    // Play Stream Function with Auto-Healing
    fun loadStream(channel: Channel, useFallback: Boolean = false) {
        errorMessage = null
        isBuffering = true
        isUsingFallbackPort = useFallback
        val targetUrl = if (useFallback) channel.getFallbackUrl() else channel.url
        statusBannerText = if (useFallback) "ترمیم هوشمند پورت فعال شد (${if (targetUrl.contains(":8080")) "8080" else "80"})" else null

        val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
            .setAllowChunklessPreparation(true)
            .createMediaSource(MediaItem.fromUri(targetUrl))

        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // ExoPlayer Listener for Auto-Healing Port Switch
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    errorMessage = null
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!isUsingFallbackPort) {
                    // Auto-Heal: Switch Port 80 <-> 8080 automatically!
                    loadStream(currentChannel, useFallback = true)
                } else {
                    isBuffering = false
                    errorMessage = "شبکه «${currentChannel.fullName}» در حال حاضر آفلاین است یا ارتباط با سرور برقرار نشد."
                }
            }
        }
        exoPlayer.addListener(listener)
        loadStream(currentChannel, useFallback = false)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Auto-hide HUD after 5 seconds when playing
    LaunchedEffect(showHudOverlay, currentChannel, isBuffering) {
        if (showHudOverlay && !isBuffering) {
            delay(5000)
            if (!isSidebarOpen) {
                showHudOverlay = false
            }
        }
    }

    // Channel Switch Helper (Zapping via TV Remote Up/Down)
    fun switchChannelByOffset(offset: Int) {
        val currentIndex = allChannels.indexOfFirst { it.id == currentChannel.id }
        if (currentIndex != -1) {
            val nextIndex = (currentIndex + offset + allChannels.size) % allChannels.size
            currentChannel = allChannels[nextIndex]
            showHudOverlay = true
            loadStream(currentChannel, useFallback = false)
        }
    }

    BackHandler(enabled = isSidebarOpen && isLandscape) {
        isSidebarOpen = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617))
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    showHudOverlay = true
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                            switchChannelByOffset(-1)
                            true
                        }
                        KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                            switchChannelByOffset(1)
                            true
                        }
                        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                            isSidebarOpen = !isSidebarOpen
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (!isSidebarOpen && isLandscape) {
                                isSidebarOpen = true
                                true
                            } else false
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        if (isLandscape) {
            // Landscape Mode (Android TV / Tablet / Horizontal Phone)
            Box(modifier = Modifier.fillMaxSize()) {
                // Fullscreen Video Surface
                VideoPlayerSurface(
                    exoPlayer = exoPlayer,
                    resizeMode = resizeMode,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            showHudOverlay = !showHudOverlay
                            if (isSidebarOpen) isSidebarOpen = false
                        }
                )

                // Overlay Loading / Error / Top Info HUD
                PlayerOverlays(
                    channel = currentChannel,
                    isBuffering = isBuffering,
                    errorMessage = errorMessage,
                    statusBannerText = statusBannerText,
                    showHud = showHudOverlay,
                    isSidebarOpen = isSidebarOpen,
                    isMuted = isMuted,
                    resizeMode = resizeMode,
                    onToggleSidebar = { isSidebarOpen = !isSidebarOpen },
                    onRetry = { loadStream(currentChannel, useFallback = false) },
                    onToggleMute = {
                        isMuted = !isMuted
                        exoPlayer.volume = if (isMuted) 0f else 1f
                    },
                    onToggleResize = {
                        resizeMode = when (resizeMode) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    onNextChannel = { switchChannelByOffset(1) },
                    onPrevChannel = { switchChannelByOffset(-1) }
                )

                // Glassmorphic Sliding Channel Drawer (Right Side in RTL)
                AnimatedVisibility(
                    visible = isSidebarOpen,
                    enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    ChannelSidebar(
                        categories = ChannelRepository.categories,
                        selectedCategory = selectedCategory,
                        onSelectCategory = { selectedCategory = it },
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        channels = filteredChannels,
                        currentChannel = currentChannel,
                        onSelectChannel = { ch ->
                            currentChannel = ch
                            loadStream(ch, useFallback = false)
                        },
                        modifier = Modifier
                            .width(360.dp)
                            .fillMaxHeight()
                            .background(Color(0xE6090D16))
                            .border(width = 1.dp, color = Color(0x3338BDF8))
                    )
                }
            }
        } else {
            // Portrait Mode (Mobile Vertical)
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black)
                        .clickable { showHudOverlay = !showHudOverlay }
                ) {
                    VideoPlayerSurface(
                        exoPlayer = exoPlayer,
                        resizeMode = resizeMode,
                        modifier = Modifier.fillMaxSize()
                    )

                    PlayerOverlays(
                        channel = currentChannel,
                        isBuffering = isBuffering,
                        errorMessage = errorMessage,
                        statusBannerText = statusBannerText,
                        showHud = showHudOverlay,
                        isSidebarOpen = false,
                        isMuted = isMuted,
                        resizeMode = resizeMode,
                        onToggleSidebar = {},
                        onRetry = { loadStream(currentChannel, useFallback = false) },
                        onToggleMute = {
                            isMuted = !isMuted
                            exoPlayer.volume = if (isMuted) 0f else 1f
                        },
                        onToggleResize = {
                            resizeMode = when (resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        },
                        onNextChannel = { switchChannelByOffset(1) },
                        onPrevChannel = { switchChannelByOffset(-1) },
                        isCompact = true
                    )
                }

                ChannelSidebar(
                    categories = ChannelRepository.categories,
                    selectedCategory = selectedCategory,
                    onSelectCategory = { selectedCategory = it },
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    channels = filteredChannels,
                    currentChannel = currentChannel,
                    onSelectChannel = { ch ->
                        currentChannel = ch
                        loadStream(ch, useFallback = false)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF020617))
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerSurface(
    exoPlayer: ExoPlayer,
    resizeMode: Int,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
                this.resizeMode = resizeMode
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { view ->
            view.resizeMode = resizeMode
        },
        modifier = modifier
    )
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerOverlays(
    channel: Channel,
    isBuffering: Boolean,
    errorMessage: String?,
    statusBannerText: String?,
    showHud: Boolean,
    isSidebarOpen: Boolean,
    isMuted: Boolean,
    resizeMode: Int,
    onToggleSidebar: () -> Unit,
    onRetry: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleResize: () -> Unit,
    onNextChannel: () -> Unit,
    onPrevChannel: () -> Unit,
    isCompact: Boolean = false
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Buffering Spinner
        if (isBuffering) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(76.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC0F172A))
                    .border(1.dp, Color(0x4438BDF8), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Color(0xFF38BDF8),
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(42.dp)
                )
            }
        }

        // Error Box with Retry
        if (errorMessage != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xE60F172A))
                    .border(1.dp, Color(0x88EF4444), RoundedCornerShape(20.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = errorMessage,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("تلاش مجدد و بررسی پورت‌ها")
                }
            }
        }

        // Bottom & Top Pro HUD Controls
        AnimatedVisibility(
            visible = showHud,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top Bar (Auto-Healing Status + Sidebar Toggle)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xCC000000), Color.Transparent)
                            )
                        )
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isCompact) {
                        IconButton(
                            onClick = onToggleSidebar,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x991E293B))
                                .border(1.dp, Color(0x4438BDF8), RoundedCornerShape(12.dp))
                        ) {
                            Icon(
                                imageVector = if (isSidebarOpen) Icons.Default.Close else Icons.Default.Menu,
                                contentDescription = "لیست شبکه‌ها",
                                tint = Color.White
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (statusBannerText != null) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0x3310B981))
                                    .border(1.dp, Color(0xFF10B981), RoundedCornerShape(50))
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = statusBannerText,
                                    color = Color(0xFF34D399),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                        }

                        // LIVE Indicator Badge
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xCCDC2626))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "پخش زنده | LIVE",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Bottom Channel Info & Pro Player Controls Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xE6020617))
                            )
                        )
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Current Channel Logo & Name
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChannelLogoBadge(channel = channel, size = if (isCompact) 42 else 54)
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = channel.fullName,
                                color = Color.White,
                                fontSize = if (isCompact) 15.sp else 19.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "دسته: ${channel.category} • شماره کانال: ${channel.id}",
                                color = Color(0xFF94A3B8),
                                fontSize = if (isCompact) 11.sp else 13.sp
                            )
                        }
                    }

                    // Player Quick Action Buttons (Prev/Next, Aspect Ratio, Mute, Reload)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HudControlButton(
                            icon = Icons.Default.SkipPrevious,
                            label = "قبلی",
                            onClick = onPrevChannel
                        )
                        HudControlButton(
                            icon = Icons.Default.SkipNext,
                            label = "بعدی",
                            onClick = onNextChannel
                        )
                        HudControlButton(
                            icon = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            label = if (isMuted) "صدا" else "بی‌صدا",
                            onClick = onToggleMute
                        )
                        HudControlButton(
                            icon = Icons.Default.AspectRatio,
                            label = when (resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_FILL -> "پر کردن"
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "زوم"
                                else -> "اصلی"
                            },
                            onClick = onToggleResize
                        )
                        HudControlButton(
                            icon = Icons.Default.Sync,
                            label = "تازه سازی",
                            onClick = onRetry
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HudControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        color = if (isFocused) Color(0xFF2563EB) else Color(0x991E293B),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .onFocusChanged { isFocused = it.isFocused }
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) Color.White else Color(0x33FFFFFF),
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun ChannelSidebar(
    categories: List<String>,
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    channels: List<Channel>,
    currentChannel: Channel,
    onSelectChannel: (Channel) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    Column(modifier = modifier.padding(16.dp)) {
        // App Title Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "DARK TV PRO",
                    color = Color(0xFF38BDF8),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "پلیر هوشمند و بدون تحریم • ۲۷ شبکه زنده",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1E293B))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "TV & Mobile",
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Search Box
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("جستجوی نام شبکه...", fontSize = 13.sp, color = Color(0xFF64748B)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF64748B)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF38BDF8),
                unfocusedBorderColor = Color(0xFF1E293B),
                focusedContainerColor = Color(0xFF0F172A),
                unfocusedContainerColor = Color(0xFF0F172A),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Category Filter Pills
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(categories) { category ->
                val isSelected = category == selectedCategory
                var isChipFocused by remember { mutableStateOf(false) }

                Surface(
                    onClick = { onSelectCategory(category) },
                    shape = RoundedCornerShape(50),
                    color = when {
                        isSelected -> Color(0xFF2563EB)
                        isChipFocused -> Color(0xFF334155)
                        else -> Color(0xFF0F172A)
                    },
                    modifier = Modifier
                        .onFocusChanged { isChipFocused = it.isFocused }
                        .border(
                            width = if (isChipFocused) 2.dp else 1.dp,
                            color = if (isSelected || isChipFocused) Color(0xFF60A5FA) else Color(0xFF1E293B),
                            shape = RoundedCornerShape(50)
                        )
                ) {
                    Text(
                        text = category,
                        color = if (isSelected) Color.White else Color(0xFFCBD5E1),
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Channel List (Full D-Pad Focus & Touch Support)
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(channels, key = { it.id }) { channel ->
                val isPlaying = channel.id == currentChannel.id
                var isItemFocused by remember { mutableStateOf(false) }

                Surface(
                    onClick = { onSelectChannel(channel) },
                    shape = RoundedCornerShape(16.dp),
                    color = when {
                        isPlaying -> Color(0xFF1E3A8A)
                        isItemFocused -> Color(0xFF1E293B)
                        else -> Color(0xFF0F172A)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isItemFocused = it.isFocused }
                        .border(
                            width = when {
                                isItemFocused -> 2.5.dp
                                isPlaying -> 1.5.dp
                                else -> 1.dp
                            },
                            color = when {
                                isItemFocused -> Color(0xFF38BDF8)
                                isPlaying -> Color(0xFF60A5FA)
                                else -> Color(0xFF1E293B)
                            },
                            shape = RoundedCornerShape(16.dp)
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            ChannelLogoBadge(channel = channel, size = 46)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = channel.fullName,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = if (isPlaying || isItemFocused) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = channel.category,
                                    color = if (isPlaying) Color(0xFF93C5FD) else Color(0xFF64748B),
                                    fontSize = 11.sp
                                )
                            }
                        }

                        if (isPlaying) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0x33EF4444))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFEF4444))
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "در حال پخش",
                                    color = Color(0xFFFCA5A5),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            Text(
                                text = "#${channel.id}",
                                color = Color(0xFF475569),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChannelLogoBadge(channel: Channel, size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(channel.primaryColor, channel.secondaryColor)
                )
            )
            .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (!channel.logoUrl.isNullOrEmpty()) {
            AsyncImage(
                model = channel.logoUrl,
                contentDescription = channel.fullName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = channel.badgeText,
                color = Color.White,
                fontSize = (size * 0.30).sp,
                fontWeight = FontWeight.Black
            )
        }
    }
}
