/*
 * Copyright (c) 2026 Rekluz Games. All rights reserved.
 * This code and its assets are the exclusive property of Rekluz Games.
 * Unauthorized copying, distribution, or commercial use is strictly prohibited.
 */
// Last Updated: March 7, 2026 - Rekluz Games

package com.rekluzgames.nikakudorimahjong

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.rekluzgames.nikakudorimahjong.ui.theme.ShisenShoTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

// --- SUPPORTING DATA CLASSES & HELPERS ---

class Firework(var x: Float, var y: Float, val color: Color) {
    var alpha = 1f
    var isDone = false
    private val particles = List(20) {
        Offset(Random.nextFloat() * 10f - 5f, Random.nextFloat() * 10f - 5f)
    }
    private var frame = 0

    fun update() {
        frame++
        alpha -= 0.02f
        if (alpha <= 0) isDone = true
    }

    fun draw(scope: DrawScope) {
        particles.forEach { velocity ->
            scope.drawCircle(
                color = color,
                radius = 4f,
                center = Offset(x + velocity.x * frame, y + velocity.y * frame),
                alpha = alpha
            )
        }
    }
}

@Composable
fun getTileDrawableId(name: String): Int {
    val context = LocalContext.current
    return remember(name) {
        context.resources.getIdentifier(name, "drawable", context.packageName)
    }
}

class GameViewModelFactory(private val context: android.content.Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GameViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return GameViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: GameViewModel by viewModels { GameViewModelFactory(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        var keepSplashScreen = true
        splashScreen.setKeepOnScreenCondition { keepSplashScreen }

        lifecycleScope.launch {
            delay(800)
            keepSplashScreen = false
        }

        enableEdgeToEdge()
        hideSystemUI()

        setContent {
            ShisenShoTheme {
                val game = viewModel.game

                LaunchedEffect(game.gameState) {
                    while (isActive && game.gameState == GameState.PLAYING) {
                        delay(1000)
                        game.timeSeconds++
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = game.bgThemeColor
                ) {
                    ShisenShoScreen(game = game, onExit = { finish() })
                }
            }
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { hideSystemUI() }
    }
}

@Composable
fun ShisenShoScreen(game: GameModel, onExit: () -> Unit) {
    val isPaused = game.gameState == GameState.PAUSED
    val view = LocalView.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var matchingTiles by remember { mutableStateOf<Set<Pair<Int, Int>>>(emptySet()) }
    val fireworks = remember { mutableStateListOf<Firework>() }

    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameMillis {
                val iterator = fireworks.iterator()
                while (iterator.hasNext()) {
                    val fw = iterator.next()
                    fw.update()
                    if (fw.isDone) iterator.remove()
                }
            }
        }
    }

    LaunchedEffect(game.gameState) {
        if (game.gameState == GameState.WON) {
            repeat(12) {
                val startX = if (containerSize.width > 0) Random.nextFloat() * containerSize.width else 500f
                val startY = if (containerSize.height > 0) Random.nextFloat() * containerSize.height else 500f
                fireworks.add(
                    Firework(
                        startX,
                        startY,
                        listOf(
                            Color.Yellow,
                            Color.Cyan,
                            Color(0xFFFF69B4),
                            Color.White,
                            Color.Green
                        ).random()
                    )
                )
                delay(300)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(8.dp)
                    .onGloballyPositioned { containerSize = it.size },
                contentAlignment = Alignment.Center
            ) {
                if (containerSize.width > 0) {
                    val widthPx = containerSize.width.toFloat()
                    val heightPx = containerSize.height.toFloat()
                    val widthDp = with(density) { widthPx.toDp() }
                    val heightDp = with(density) { heightPx.toDp() }

                    // Use actual board dimensions to avoid IndexOutOfBounds
                    val boardRows = game.board.size
                    val boardCols = game.board.firstOrNull()?.size ?: 0

                    if (boardRows > 0 && boardCols > 0) {
                        val scaledWidthDp = widthDp * game.boardWidthScale
                        val slotWidthDp = scaledWidthDp / boardCols
                        val hOverlap = 0.96f
                        val tileWidth = slotWidthDp * 1.15f
                        val verticalCompression = 0.92f
                        val slotHeightDp = (heightDp / boardRows) * verticalCompression
                        val vStretch = 1.25f
                        val tileHeight = slotHeightDp * vStretch
                        val totalGridHeight = slotHeightDp * (boardRows - 1) + tileHeight
                        val verticalOffset = (heightDp - totalGridHeight) / 2f
                        val boardOffsetX =
                            if (game.boardWidthScale < 1f) (widthDp - scaledWidthDp) / 2f else 0.dp

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .offset(x = boardOffsetX, y = verticalOffset)
                        ) {
                            for (r in 0 until boardRows) {
                                val row = game.board[r]
                                for (c in 0 until boardCols) {
                                    key(r, c) {
                                        val tile = row[c]
                                        val isMatching = matchingTiles.contains(r to c)
                                        TileView(
                                            tile = tile,
                                            modifier = Modifier
                                                .size(tileWidth, tileHeight)
                                                .offset(
                                                    x = (slotWidthDp * hOverlap * c),
                                                    y = (slotHeightDp * r)
                                                )
                                                .zIndex(
                                                    if (isMatching) 1000f
                                                    else (r * boardCols + c).toFloat()
                                                ),
                                            isPaused = isPaused,
                                            isMatching = isMatching,
                                            onClick = {
                                                val prevSelected = game.selectedTile
                                                game.onTileClick(r, c, view)
                                                if (game.lastPath != null && prevSelected != null) {
                                                    val pair = setOf(prevSelected, r to c)
                                                    coroutineScope.launch {
                                                        matchingTiles = matchingTiles + pair
                                                        delay(500)
                                                        matchingTiles = matchingTiles - pair
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            game.lastPath?.let { path ->
                                val slotWidthPx =
                                    (widthPx * game.boardWidthScale) / boardCols
                                val verticalCompression = 0.92f
                                val slotHeightPx =
                                    (heightPx / boardRows) * verticalCompression
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .zIndex(999f)
                                ) {
                                    val tileWidthPx = with(density) { tileWidth.toPx() }
                                    val tileHeightPx = with(density) { tileHeight.toPx() }
                                    val points = path.map { (pr, pc) ->
                                        Offset(
                                            x = (slotWidthPx * hOverlap * pc) + (tileWidthPx * 0.45f),
                                            y = (slotHeightPx * pr) + (tileHeightPx * 0.45f)
                                        )
                                    }
                                    for (i in 0 until points.size - 1) {
                                        drawLine(
                                            color = Color.Yellow,
                                            start = points[i],
                                            end = points[i + 1],
                                            strokeWidth = 8f,
                                            cap = StrokeCap.Round
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (game.gameState == GameState.ABOUT) {
                    InteractiveAboutDialog(onDismiss = { game.gameState = GameState.PAUSED })
                }
            }

            Column(
                modifier = Modifier
                    .width(120.dp)
                    .fillMaxHeight()
                    .padding(vertical = 12.dp, horizontal = 4.dp)
                    .background(Color(0x66000000), RoundedCornerShape(16.dp))
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MenuPillButton("MENU") { game.gameState = GameState.PAUSED }
                    if (game.showAutocompletePrompt) {
                        MenuPillButton(
                            "FINISH IT!",
                            bgColor = Color(0xFFFFA500)
                        ) { game.startAutocomplete(view) }
                    }
                    val hintAvailable = game.isHintAvailable
                    val hintLabel =
                        if (hintAvailable) "HINT" else "HINT (${game.hintSecondsRemaining})"
                    MenuPillButton(
                        hintLabel,
                        enabled = hintAvailable
                    ) { game.showHint() }
                    MenuPillButton(
                        "SHUFFLE (${game.shufflesRemaining})",
                        enabled = game.canShuffle
                    ) { game.shuffleBoard() }
                    MenuPillButton("UNDO", enabled = game.canUndo) { game.undoLastMove() }
                    MenuPillButton("SETTINGS") { game.gameState = GameState.OPTIONS }
                    MenuPillButton("BOARDS") { game.gameState = GameState.BOARDS }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                TimerPill(game.formatTime())
            }
        }

        if (game.gameState != GameState.PLAYING && game.gameState != GameState.ABOUT) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .zIndex(1500f),
                contentAlignment = Alignment.Center
            ) {
                when (game.gameState) {
                    GameState.PAUSED -> PauseDialog(
                        onNewGame = { game.initializeBoard() },
                        onRetry = { game.retryGame() },
                        onResume = { game.gameState = GameState.PLAYING },
                        onAbout = { game.gameState = GameState.ABOUT },
                        onExit = onExit
                    )

                    GameState.OPTIONS -> OptionsDialog(
                        game = game,
                        onDone = { game.gameState = GameState.PLAYING }
                    )

                    GameState.BOARDS -> BoardSelectionDialog(
                        game = game,
                        onDismiss = { game.gameState = GameState.PLAYING }
                    )

                    GameState.SCORE -> ScoreDialog(game = game)
                    GameState.WON -> WinDialog(game = game)
                    GameState.NO_MOVES -> NoMovesDialog(
                        onShuffle = { game.shuffleBoard() },
                        onNewGame = { game.initializeBoard() },
                        onExit = onExit,
                        canShuffle = game.canShuffle
                    )

                    else -> {}
                }
            }
        }
        Canvas(modifier = Modifier.fillMaxSize().zIndex(3000f)) {
            fireworks.forEach { it.draw(this) }
        }
    }
}

@Composable
fun TileView(
    tile: Tile,
    isPaused: Boolean,
    isMatching: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (tile.isRemoved && !isMatching) return
    val tileImageId = getTileDrawableId(tile.imageName)
    val backImageId = R.drawable.tile_back
    val tileScale by animateFloatAsState(
        targetValue = if (isMatching) 0f else 1f,
        label = "tilePop"
    )
    val driftY by animateDpAsState(
        targetValue = if (isMatching) (-40).dp else 0.dp,
        animationSpec = tween(500),
        label = "tileDrift"
    )
    val alpha by if (isMatching) {
        val infiniteTransition = rememberInfiniteTransition(label = "flash")
        infiniteTransition.animateFloat(
            1f,
            0f,
            infiniteRepeatable(
                tween(250, easing = LinearEasing),
                RepeatMode.Reverse
            ),
            label = "alpha"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }

    Box(
        modifier = modifier
            .offset(y = driftY)
            .scale(tileScale)
            .alpha(alpha)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
    ) {
        val imageId = if (isPaused) backImageId else tileImageId
        if (imageId != 0) {
            Image(
                painter = painterResource(id = imageId),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
            if (!isPaused && (tile.isSelected || tile.isHint)) {
                val highlightColor =
                    if (tile.isSelected) Color(0x6600BFFF) else Color(0x44FFFF00)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 4.dp, bottom = 6.dp)
                        .background(highlightColor, RoundedCornerShape(4.dp))
                        .border(
                            if (tile.isHint) 2.dp else 0.dp,
                            Color.Yellow,
                            RoundedCornerShape(4.dp)
                        )
                )
            }
        }
    }
}

@Composable
fun FullscreenDialogContainer(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .width(500.dp)
            .fillMaxHeight()
            .padding(vertical = 16.dp)
            .background(Color(0xEE111111), RoundedCornerShape(24.dp))
            .border(2.dp, Color(0x44FFFFFF), RoundedCornerShape(24.dp))
            .padding(horizontal = 48.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            content()
        }
    }
}

@Composable
fun BoardSelectionDialog(game: GameModel, onDismiss: () -> Unit) {
    val boardOptions = listOf(
        "EASY" to (5 to 14),
        "NORMAL" to (7 to 16),
        "HARD" to (8 to 17),
        "EXTREME" to (8 to 21)
    )
    FullscreenDialogContainer {
        Text(
            "SELECT BOARD",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Spacer(modifier = Modifier.height(24.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            userScrollEnabled = false
        ) {
            items(boardOptions) { (label, size) ->
                val isCurrent = game.rows == size.first && game.cols == size.second
                val infiniteTransition = rememberInfiniteTransition(label = "borderPulse")
                val animatedThickness by if (isCurrent) {
                    infiniteTransition.animateValue(
                        initialValue = 2.dp,
                        targetValue = 5.dp,
                        typeConverter = Dp.VectorConverter,
                        animationSpec = infiniteRepeatable(
                            tween(1000, easing = FastOutSlowInEasing),
                            RepeatMode.Reverse
                        ),
                        label = "thickness"
                    )
                } else {
                    remember { mutableStateOf(0.dp) }
                }
                Card(
                    modifier = Modifier
                        .height(80.dp)
                        .clickable {
                            game.updateGridSize(size.first, size.second)
                            onDismiss()
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCurrent) Color(0xFF00BFFF) else Color(
                            0x22FFFFFF
                        )
                    ),
                    border = if (isCurrent) BorderStroke(
                        animatedThickness,
                        Color.Yellow
                    ) else null
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                label,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                "${size.first} x ${size.second}",
                                color = Color.LightGray,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PauseDialog(
    onNewGame: () -> Unit,
    onRetry: () -> Unit,
    onResume: () -> Unit,
    onAbout: () -> Unit,
    onExit: () -> Unit
) {
    FullscreenDialogContainer {
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            "MENU",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DialogButton("NEW GAME", onNewGame, modifier = Modifier.weight(1f))
            DialogButton("RETRY GAME", onRetry, modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DialogButton("RESUME", onResume, modifier = Modifier.weight(1f))
            DialogButton("ABOUT", onAbout, modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(16.dp))
        DialogButton("EXIT", onExit, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun OptionsDialog(game: GameModel, onDone: () -> Unit) {
    val backgroundColors = listOf(
        Color(0xFF1B5E20),
        Color(0xFF0D47A1),
        Color(0xFF311B92),
        Color(0xFF000000),
        Color(0xFF3E2723)
    )
    FullscreenDialogContainer {
        Text(
            "SETTINGS",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Spacer(modifier = Modifier.height(20.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Background Theme", color = Color.Gray, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                backgroundColors.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                width = if (game.bgThemeColor == color) 3.dp else 1.dp,
                                color = if (game.bgThemeColor == color) Color.Yellow else Color.White.copy(
                                    alpha = 0.5f
                                ),
                                shape = CircleShape
                            )
                            .clickable { game.bgThemeColor = color }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.width(220.dp)
            ) {
                Text("Board Scale", color = Color.Gray, fontSize = 12.sp)
                Slider(
                    value = game.boardWidthScale,
                    onValueChange = { game.boardWidthScale = it },
                    valueRange = 0.5f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.Yellow,
                        activeTrackColor = Color.Yellow
                    )
                )
            }
            Column(horizontalAlignment = Alignment.Start) {
                Text("Sound Effects", color = Color.Gray, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Switch(
                    checked = game.isSoundEnabled,
                    onCheckedChange = { game.toggleSound(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Yellow,
                        checkedTrackColor = Color(0xFF00BFFF)
                    )
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            DialogButton(
                "SCOREBOARD",
                { game.gameState = GameState.SCORE },
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
            )
            DialogButton(
                "DONE",
                onDone,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
            )
        }
    }
}

@Composable
fun InteractiveAboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val currentVersion = remember {
        try {
            val packageInfo =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
            "v${packageInfo.versionName}"
        } catch (e: Exception) {
            "v1.0.0"
        }
    }
    val gameTitle = remember {
        listOf(
            R.drawable.letter_r,
            R.drawable.letter_e,
            R.drawable.letter_k,
            R.drawable.letter_l,
            R.drawable.letter_u,
            R.drawable.letter_z,
            R.drawable.letter_g,
            R.drawable.letter_a,
            R.drawable.letter_m,
            R.drawable.letter_e,
            R.drawable.letter_s
        )
    }
    val creatorName = remember {
        listOf(
            R.drawable.letter_r,
            R.drawable.letter_i,
            R.drawable.letter_c,
            R.drawable.letter_o,
            R.drawable.letter_l,
            R.drawable.letter_u,
            R.drawable.letter_z,
            R.drawable.letter_i
        )
    }
    val clickedIndices = remember { mutableStateListOf<Int>() }
    var showNamePopup by remember { mutableStateOf(false) }
    var showPhotoPopup by remember { mutableStateOf(false) }

    FullscreenDialogContainer {
        if (!showNamePopup && !showPhotoPopup) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Nikakudori Mahjong",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    currentVersion,
                    color = Color.Gray,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Column(
                        modifier = Modifier.weight(0.5f),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = "Nikakudori Mahjong is a traditional Japanese tile-matching puzzle game.\nConnect identical pairs using a path with no more than two 90-degree turns to clear the board.",
                            color = Color.White,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            textAlign = TextAlign.Start
                        )
                        Spacer(modifier = Modifier.height(28.dp))
                        Text(
                            text = "View project on GitHub",
                            color = Color(0xFF00BFFF),
                            fontSize = 14.sp,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable { uriHandler.openUri("https://github.com/rekluz/Nikakudori-Mahjong") }
                        )
                    }
                    Spacer(modifier = Modifier.width(20.dp))
                    Column(
                        modifier = Modifier.weight(0.5f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(6),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.width(200.dp),
                            userScrollEnabled = false
                        ) {
                            items(12) { index ->
                                if (index < gameTitle.size) {
                                    val tileRes = gameTitle[index]
                                    val isClicked = clickedIndices.contains(index)
                                    Card(
                                        shape = RoundedCornerShape(2.dp),
                                        modifier = Modifier
                                            .aspectRatio(0.61f)
                                            .clickable(enabled = !isClicked) {
                                                clickedIndices.add(index)
                                                if (clickedIndices.size == 11) showNamePopup = true
                                            },
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isClicked) Color.Transparent else Color.White
                                        )
                                    ) {
                                        if (!isClicked) Image(
                                            painter = painterResource(id = tileRes),
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.FillBounds
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.aspectRatio(0.61f))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        DialogButton(
                            text = "DONE",
                            onClick = onDismiss,
                            modifier = Modifier.width(160.dp)
                        )
                    }
                }
            }
        } else if (showNamePopup) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("This game was created by", color = Color.White, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.Center) {
                        creatorName.forEachIndexed { index, tile ->
                            TileImageSmall(tile)
                            if (index == 3) Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(30.dp))
                    Button(
                        onClick = {
                            showNamePopup = false
                            showPhotoPopup = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00BFFF)
                        )
                    ) {
                        Text("Thanks for playing!")
                    }
                }
            }
        } else if (showPhotoPopup) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Hello World!",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(Color.DarkGray)
                            .border(2.dp, Color(0xFF00BFFF), CircleShape)
                            .clickable { uriHandler.openUri("https://github.com/rekluz") }
                            .padding(2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.my_photo),
                            contentDescription = "Rico Luzi",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Rico", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Text("Lead Developer", color = Color.LightGray)
                    Spacer(modifier = Modifier.height(24.dp))
                    DialogButton(
                        text = "DONE",
                        onClick = {
                            showPhotoPopup = false
                            clickedIndices.clear()
                            onDismiss()
                        },
                        modifier = Modifier.width(160.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun TileImageSmall(resId: Int) {
    Box(modifier = Modifier.size(width = 24.dp, height = 38.dp)) {
        Image(
            painter = painterResource(id = resId),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
    }
}

@Composable
fun ScoreDialog(game: GameModel) {
    var selectedSize by remember { mutableStateOf(Triple(game.rows, game.cols, "standard")) }
    val topScores = game.getTopScores(selectedSize.first, selectedSize.second, "standard")
    val sizes = listOf(
        Triple(5, 14, "standard"),
        Triple(7, 16, "standard"),
        Triple(8, 17, "standard"),
        Triple(8, 21, "standard")
    )
    FullscreenDialogContainer {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "HALL OF FAME",
                color = Color.Yellow,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                sizes.forEach { (r, c, mode) ->
                    val isSelected = selectedSize.first == r && selectedSize.second == c
                    Text(
                        text = game.getDifficultyLabel(r, c, mode),
                        color = if (isSelected) Color.Yellow else Color.Gray,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clickable { selectedSize = Triple(r, c, mode) }
                            .padding(4.dp)
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x33FFFFFF))
                    .padding(4.dp)
            ) {
                Text(
                    "RANK",
                    Modifier.weight(0.2f),
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "PLAYER",
                    Modifier.weight(0.5f),
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "TIME",
                    Modifier.weight(0.3f),
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End
                )
            }
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(10) { index ->
                    val score = topScores.getOrNull(index)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (index % 2 == 0) Color.Transparent else Color(0x11FFFFFF)
                            )
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${index + 1}",
                            Modifier.weight(0.2f),
                            color = if (index < 3) Color.Yellow else Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            score?.first ?: "---",
                            Modifier.weight(0.5f),
                            color = Color.White,
                            fontSize = 14.sp
                        )
                        Text(
                            score?.second ?: "--:--",
                            Modifier.weight(0.3f),
                            color = if (score != null) Color.Cyan else Color.DarkGray,
                            textAlign = TextAlign.End,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                DialogButton(
                    "Clear",
                    { game.clearScores(selectedSize.first, selectedSize.second, "standard") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                DialogButton(
                    "Done",
                    { game.gameState = GameState.OPTIONS },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun WinDialog(game: GameModel) {
    var name by remember { mutableStateOf("") }
    FullscreenDialogContainer {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "YOU WON!",
                color = Color.Yellow,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
            Text("Time: ${game.formatTime()}", color = Color.White, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text("ENTER INITIALS", color = Color.Gray, fontSize = 12.sp)
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .padding(8.dp)
                    .background(Color(0x33FFFFFF), RoundedCornerShape(4.dp))
                    .border(1.dp, Color.Yellow, RoundedCornerShape(4.dp))
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = { if (it.length <= 3) name = it.uppercase() },
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        letterSpacing = 4.sp
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(Color.Yellow),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters
                    )
                )
                if (name.isEmpty()) Text(
                    "___",
                    color = Color(0x66FFFFFF),
                    fontSize = 24.sp,
                    letterSpacing = 4.sp
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            DialogButton(
                "Save & View Scores",
                onClick = {
                    game.saveScore(name.ifBlank { "???" }, game.timeSeconds)
                    game.gameState = GameState.SCORE
                }
            )
        }
    }
}

@Composable
fun NoMovesDialog(
    onShuffle: () -> Unit,
    onNewGame: () -> Unit,
    onExit: () -> Unit,
    canShuffle: Boolean
) {
    FullscreenDialogContainer {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "NO MORE MOVES!",
                color = Color.Red,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(24.dp))
            DialogButton(
                text = "Shuffle Board",
                onClick = onShuffle,
                enabled = canShuffle,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            DialogButton(
                text = "Start New Game",
                onClick = onNewGame,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            DialogButton(
                text = "Exit to Menu",
                onClick = onExit,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun TimerPill(time: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .background(Color(0x33FFFFFF))
            .border(1.dp, Color(0x66FFFFFF), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "TIME",
                color = Color.White,
                fontSize = 8.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = time,
                color = Color.Yellow,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

@Composable
fun MenuPillButton(
    label: String,
    enabled: Boolean = true,
    bgColor: Color = Color(0x33FFFFFF),
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.98f else 1f,
        label = "pillScale"
    )
    val finalBgColor = if (!enabled) Color(0x11FFFFFF) else if (isPressed) Color(0x66444444) else bgColor
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(finalBgColor)
            .border(1.dp, Color(0x33FFFFFF), CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (enabled) Color.White else Color.Gray,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun DialogButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.96f else 1f,
        label = "btnScale"
    )
    val buttonColor =
        if (!enabled) Color.DarkGray else if (isPressed) Color(0xFF0099CC) else Color(0xFF00BFFF)

    Box(
        modifier = modifier
            .padding(horizontal = 6.dp)   // External padding for breathing room
            .height(52.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(buttonColor)
            .border(1.dp, Color(0x66FFFFFF), CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) Color.White else Color.Gray,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp) // Internal padding for better visual balance
        )
    }
}