package com.pipra.browser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pipra.browser.ui.theme.LocalPipraColors
import com.pipra.browser.ui.theme.PipraColors
import com.pipra.browser.ui.theme.PipraPalette
import com.pipra.browser.ui.theme.PipraTheme
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Upgraded 100% offline "Pipra Ant Dash" arcade runner.
 * Features parallax day/night skies, custom obstacle art, collectible sugar crystals,
 * protective leaf shields, fast-dive controls, haptic feedback, and persistent high score.
 */
class OfflineGameActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val store = Store(this)
        setContent {
            PipraTheme(themeMode = store.themeMode) {
                PipraAntDashScreen(
                    initialBest = store.bestGameScore,
                    onBestUpdated = { score -> store.bestGameScore = score },
                    onExit = { finish() }
                )
            }
        }
    }
}

@Composable
private fun PipraAntDashScreen(
    initialBest: Int,
    onBestUpdated: (Int) -> Unit,
    onExit: () -> Unit
) {
    val palette = LocalPipraColors.current
    val haptic = LocalHapticFeedback.current
    val engine = remember(initialBest) { OfflineGameEngine(initialBest) }
    var frameTick by remember { mutableIntStateOf(0) }

    fun handleEvents(events: List<GameEvent>) {
        for (ev in events) {
            when (ev) {
                GameEvent.JUMP, GameEvent.DOUBLE_JUMP, GameEvent.SUGAR_COLLECTED, GameEvent.SHIELD_COLLECTED ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                GameEvent.SHIELD_USED, GameEvent.CRASH ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                else -> Unit
            }
        }
    }

    LaunchedEffect(engine) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { now ->
                if (lastNanos == 0L) {
                    lastNanos = now
                    return@withFrameNanos
                }
                val dt = (now - lastNanos) / 1_000_000_000f
                lastNanos = now
                if (engine.hasStarted && !engine.isPaused && !engine.isGameOver) {
                    val ev = engine.step(dt, onNewBest = onBestUpdated)
                    handleEvents(ev)
                    frameTick++
                }
            }
        }
    }

    // Smooth subtle pulse on score milestones
    val isMilestoneActive = engine.callouts.any { it.text.endsWith("m!") }
    val scoreScale by animateFloatAsState(targetValue = if (isMilestoneActive) 1.12f else 1f, label = "scorePulse")
    val skyNightBlend = ((engine.score / 300) % 2 == 1)
    val effectiveNight = if (palette.isDark) !skyNightBlend else skyNightBlend

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = PipraColors.MatteBlack,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Top bar with Back, Title, Best, Score, Pause, Restart
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onExit, modifier = Modifier.testTag("game_exit_button")) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Exit Game",
                        tint = PipraColors.SilverPrimary
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Pipra Ant Dash",
                        style = MaterialTheme.typography.titleMedium,
                        color = PipraColors.SilverPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Best: ${engine.bestScore} · Sugar: ${engine.sugarCollectedCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = PipraColors.ElectricBlueLight
                    )
                }
                Text(
                    text = engine.score.toString().padStart(5, '0'),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = PipraColors.SilverPrimary,
                    modifier = Modifier
                        .scale(scoreScale)
                        .padding(end = 6.dp)
                        .testTag("game_score_text")
                )
                IconButton(
                    onClick = {
                        if (engine.hasStarted && !engine.isGameOver) {
                            engine.isPaused = !engine.isPaused
                            frameTick++
                        }
                    },
                    modifier = Modifier.testTag("game_pause_button")
                ) {
                    Icon(
                        imageVector = if (engine.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (engine.isPaused) "Resume" else "Pause",
                        tint = PipraColors.SilverPrimary
                    )
                }
                IconButton(
                    onClick = {
                        engine.restart()
                        frameTick++
                    },
                    modifier = Modifier.testTag("game_restart_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Restart",
                        tint = PipraColors.SilverPrimary
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Status HUD Strip: Jump pips, Shield, Speed
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = PipraColors.Charcoal,
                    border = BorderStroke(1.dp, PipraColors.SilverBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text("Jumps", style = MaterialTheme.typography.labelMedium, color = PipraColors.SilverMuted)
                        val remainingJumps = (2 - engine.jumpsUsed).coerceIn(0, 2)
                        repeat(2) { idx ->
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (idx < remainingJumps) PipraColors.ElectricBlue else PipraColors.SilverBorder
                                    )
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (engine.hasShield) PipraColors.Success.copy(alpha = 0.16f) else PipraColors.Charcoal,
                    border = BorderStroke(1.dp, if (engine.hasShield) PipraColors.Success else PipraColors.SilverBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Leaf Shield Status",
                            tint = if (engine.hasShield) PipraColors.Success else PipraColors.SilverMuted,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (engine.hasShield) "Shield Ready" else "No Shield",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (engine.hasShield) PipraColors.Success else PipraColors.SilverMuted
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = PipraColors.Charcoal,
                    border = BorderStroke(1.dp, PipraColors.SilverBorder)
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1fx", engine.speedMultiplier),
                        style = MaterialTheme.typography.labelMedium,
                        color = PipraColors.ElectricBlueLight,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Game Canvas Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(PipraColors.Charcoal)
                    .pointerInput(engine) {
                        detectTapGestures(onTap = {
                            handleEvents(engine.startOrJump())
                            frameTick++
                        })
                    }
                    .pointerInput(engine) {
                        var dragAccum = 0f
                        detectVerticalDragGestures(
                            onDragStart = { dragAccum = 0f },
                            onVerticalDrag = { _, dragAmount ->
                                dragAccum += dragAmount
                                if (dragAccum > 28f) {
                                    handleEvents(engine.fastDive())
                                    dragAccum = 0f
                                    frameTick++
                                }
                            }
                        )
                    }
                    .testTag("game_canvas")
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    @Suppress("UNUSED_EXPRESSION")
                    frameTick
                    drawGameWorld(
                        engine = engine,
                        palette = palette,
                        nightSky = effectiveNight
                    )
                }

                // Floating callout chips rendered cleanly with Compose Text on top of Canvas
                engine.callouts.forEach { callout ->
                    val alpha = (callout.life / 1.0f).coerceIn(0f, 1f)
                    Surface(
                        color = (if (callout.isAccent) PipraColors.ElectricBlue else PipraColors.Success).copy(alpha = 0.9f * alpha),
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 18.dp)
                    ) {
                        Text(
                            text = callout.text,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = alpha),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                if (!engine.hasStarted && !engine.isGameOver) {
                    GameOverlayCard(
                        title = "Ready to Dash?",
                        subtitle = "Double-jump over thorns & beetles, dive fast with ↓, and collect blue sugar drops & leaf shields.",
                        primaryLabel = "Start Run",
                        onPrimary = {
                            handleEvents(engine.startOrJump())
                            frameTick++
                        },
                        secondaryLabel = "Back",
                        onSecondary = onExit
                    )
                } else if (engine.isPaused && !engine.isGameOver) {
                    GameOverlayCard(
                        title = "Game Paused",
                        subtitle = "Score: ${engine.score} · Best: ${engine.bestScore}",
                        primaryLabel = "Resume",
                        onPrimary = {
                            engine.isPaused = false
                            frameTick++
                        },
                        secondaryLabel = "Exit",
                        onSecondary = onExit
                    )
                } else if (engine.isGameOver) {
                    GameOverlayCard(
                        title = if (engine.newBestAchieved) "New High Score!" else "Game Over",
                        subtitle = "Score: ${engine.score} · Sugar: ${engine.sugarCollectedCount} · Best: ${engine.bestScore}",
                        primaryLabel = "Play Again",
                        onPrimary = {
                            engine.restart()
                            frameTick++
                        },
                        secondaryLabel = "Back to Browser",
                        onSecondary = onExit
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Controls bar (Dive + Jump with generous >=48dp touch targets)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        handleEvents(engine.fastDive())
                        frameTick++
                    },
                    border = BorderStroke(1.dp, PipraColors.SilverBorder),
                    modifier = Modifier
                        .height(50.dp)
                        .testTag("game_dive_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Fast Dive",
                        tint = PipraColors.SilverPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Dive", color = PipraColors.SilverPrimary)
                }

                Button(
                    onClick = {
                        handleEvents(engine.startOrJump())
                        frameTick++
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("game_jump_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (!engine.hasStarted) "Start / Jump" else "Jump (2x)")
                }
            }
        }
    }
}

private fun DrawScope.drawGameWorld(
    engine: OfflineGameEngine,
    palette: PipraPalette,
    nightSky: Boolean
) {
    val groundY = size.height * 0.77f
    val densityPx = density

    // 1. Sky gradient (Day vs Starry Night)
    val skyTop = if (nightSky) Color(0xFF0A1124) else Color(0xFFDCEBFF)
    val skyBottom = if (nightSky) Color(0xFF17223B) else palette.surface
    drawRect(
        brush = Brush.verticalGradient(colors = listOf(skyTop, skyBottom), startY = 0f, endY = groundY),
        size = Size(size.width, groundY)
    )

    // 2. Celestial body + Stars / Clouds
    if (nightSky) {
        // Moon glow
        drawCircle(
            color = Color(0xFFE2E8F0).copy(alpha = 0.14f),
            radius = 34f * densityPx,
            center = Offset(size.width * 0.84f, size.height * 0.18f)
        )
        drawCircle(
            color = Color(0xFFF8FAFC),
            radius = 15f * densityPx,
            center = Offset(size.width * 0.84f, size.height * 0.18f)
        )
        // Twinkling parallax stars
        val starOffset = (engine.distanceAcc * 2.5f * densityPx)
        for (i in 0 until 14) {
            val sx = (((i * 97f + 35f) * densityPx - starOffset) % size.width + size.width) % size.width
            val sy = (0.08f + (i % 5) * 0.09f) * groundY
            val twinkle = 0.4f + 0.6f * abs(sin(engine.legPhase * 0.2f + i))
            drawCircle(
                color = Color.White.copy(alpha = twinkle * 0.8f),
                radius = (1.2f + (i % 2) * 0.9f) * densityPx,
                center = Offset(sx, sy)
            )
        }
    } else {
        // Warm sun + drifting clouds
        drawCircle(
            color = Color(0xFFFBBF24).copy(alpha = 0.25f),
            radius = 32f * densityPx,
            center = Offset(size.width * 0.84f, size.height * 0.19f)
        )
        drawCircle(
            color = Color(0xFFF59E0B),
            radius = 16f * densityPx,
            center = Offset(size.width * 0.84f, size.height * 0.19f)
        )
        val cloudShift = engine.distanceAcc * 4f * densityPx
        for (c in 0 until 3) {
            val cx = (((c * 175f + 70f) * densityPx - cloudShift) % (size.width + 120f * densityPx) + (size.width + 120f * densityPx)) % (size.width + 120f * densityPx) - 60f * densityPx
            val cy = (0.17f + c * 0.11f) * groundY
            val cloudColor = Color.White.copy(alpha = 0.85f)
            drawCircle(cloudColor, 14f * densityPx, Offset(cx, cy))
            drawCircle(cloudColor, 18f * densityPx, Offset(cx + 16f * densityPx, cy - 4f * densityPx))
            drawCircle(cloudColor, 13f * densityPx, Offset(cx + 32f * densityPx, cy))
        }
    }

    // 3. Parallax distant rolling hills
    val hillColor = if (nightSky) Color(0xFF1F2D4A) else Color(0xFFBFD7FA)
    val hillShift = engine.distanceAcc * 5.5f * densityPx
    val hillPath = Path().apply {
        moveTo(0f, groundY)
        val step = 18f * densityPx
        var x = 0f
        while (x <= size.width + step) {
            val worldX = x + hillShift
            val h = (18f + 12f * sin(worldX * 0.0055f) + 7f * cos(worldX * 0.012f)) * densityPx
            lineTo(x, groundY - h)
            x += step
        }
        lineTo(size.width, groundY)
        close()
    }
    drawPath(hillPath, color = hillColor)

    // 4. Ground plane + scrolling terrain details
    drawRect(
        color = palette.cardSurface,
        topLeft = Offset(0f, groundY),
        size = Size(size.width, size.height - groundY)
    )
    drawLine(
        color = palette.accent.copy(alpha = 0.45f),
        start = Offset(0f, groundY),
        end = Offset(size.width, groundY),
        strokeWidth = 3f * densityPx
    )

    val stride = 64f * densityPx
    val groundOffset = (engine.distanceAcc * 15f * densityPx) % stride
    var tx = -groundOffset
    while (tx < size.width) {
        drawLine(
            color = palette.border.copy(alpha = 0.85f),
            start = Offset(tx, groundY + 11f * densityPx),
            end = Offset(tx + 18f * densityPx, groundY + 11f * densityPx),
            strokeWidth = 2f * densityPx,
            cap = StrokeCap.Round
        )
        drawCircle(
            color = palette.border.copy(alpha = 0.55f),
            radius = 2.2f * densityPx,
            center = Offset(tx + 34f * densityPx, groundY + 23f * densityPx)
        )
        tx += stride
    }

    // 5. Collectibles (Sugar drops & Leaf shields)
    for (p in engine.pickups) {
        val cx = p.xNorm * size.width
        val bob = sin(p.bobPhase) * 4f * densityPx
        val cy = groundY - p.elevationDp * densityPx + bob
        when (p.kind) {
            PickupKind.SUGAR_DROP -> {
                drawCircle(
                    color = palette.accent.copy(alpha = 0.22f),
                    radius = 15f * densityPx,
                    center = Offset(cx, cy)
                )
                val diamond = Path().apply {
                    moveTo(cx, cy - 10f * densityPx)
                    lineTo(cx + 8f * densityPx, cy)
                    lineTo(cx, cy + 10f * densityPx)
                    lineTo(cx - 8f * densityPx, cy)
                    close()
                }
                drawPath(diamond, color = palette.accent)
                drawCircle(
                    color = Color.White.copy(alpha = 0.8f),
                    radius = 2.2f * densityPx,
                    center = Offset(cx - 2f * densityPx, cy - 3f * densityPx)
                )
            }
            PickupKind.LEAF_SHIELD -> {
                drawCircle(
                    color = palette.success.copy(alpha = 0.24f),
                    radius = 16f * densityPx,
                    center = Offset(cx, cy)
                )
                drawCircle(
                    color = palette.success,
                    radius = 10f * densityPx,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.6f * densityPx)
                )
                drawLine(
                    color = palette.success,
                    start = Offset(cx - 4f * densityPx, cy + 3f * densityPx),
                    end = Offset(cx + 4f * densityPx, cy - 4f * densityPx),
                    strokeWidth = 2.4f * densityPx,
                    cap = StrokeCap.Round
                )
            }
        }
    }

    // 6. Obstacles (custom vector silhouettes instead of plain boxes)
    for (ob in engine.obstacles) {
        val obW = ob.widthDp * densityPx
        val obH = ob.heightDp * densityPx
        val cx = ob.xNorm * size.width
        val bottom = groundY - ob.bottomElevationDp * densityPx
        when (ob.kind) {
            ObstacleKind.SPIKY_BURR -> {
                val r = minOf(obW, obH) * 0.42f
                val cy = bottom - r - 2f * densityPx
                for (a in 0 until 8) {
                    val ang = a * (PI / 4.0)
                    drawLine(
                        color = palette.danger,
                        start = Offset(cx, cy),
                        end = Offset(
                            cx + (cos(ang) * (r + 5f * densityPx)).toFloat(),
                            cy + (sin(ang) * (r + 5f * densityPx)).toFloat()
                        ),
                        strokeWidth = 3f * densityPx,
                        cap = StrokeCap.Round
                    )
                }
                drawCircle(palette.danger, radius = r, center = Offset(cx, cy))
                drawCircle(Color.White.copy(alpha = 0.35f), radius = r * 0.32f, center = Offset(cx - r * 0.28f, cy - r * 0.28f))
            }
            ObstacleKind.TALL_TWIG -> {
                // Forked cactus / bramble post
                drawRoundRect(
                    color = palette.danger,
                    topLeft = Offset(cx - obW * 0.26f, bottom - obH),
                    size = Size(obW * 0.52f, obH),
                    cornerRadius = CornerRadius(6f * densityPx, 6f * densityPx)
                )
                drawRoundRect(
                    color = palette.danger,
                    topLeft = Offset(cx - obW * 0.52f, bottom - obH * 0.68f),
                    size = Size(obW * 0.32f, obH * 0.22f),
                    cornerRadius = CornerRadius(4f * densityPx, 4f * densityPx)
                )
                drawRoundRect(
                    color = palette.danger,
                    topLeft = Offset(cx + obW * 0.20f, bottom - obH * 0.52f),
                    size = Size(obW * 0.32f, obH * 0.20f),
                    cornerRadius = CornerRadius(4f * densityPx, 4f * densityPx)
                )
            }
            ObstacleKind.SWOOPING_BEETLE -> {
                val cy = bottom - obH * 0.5f
                val wingFlap = sin(ob.wingPhase) * 8f * densityPx
                // Flapping translucent wings
                drawOval(
                    color = Color.White.copy(alpha = 0.72f),
                    topLeft = Offset(cx - 4f * densityPx, cy - 13f * densityPx - wingFlap * 0.4f),
                    size = Size(14f * densityPx, 8f * densityPx)
                )
                // Beetle shell
                drawOval(
                    color = palette.warning,
                    topLeft = Offset(cx - obW * 0.45f, cy - obH * 0.38f),
                    size = Size(obW * 0.9f, obH * 0.76f)
                )
                drawCircle(
                    color = Color(0xFF111827),
                    radius = 3f * densityPx,
                    center = Offset(cx - obW * 0.30f, cy - 2f * densityPx)
                )
            }
        }
    }

    // 7. Particles (dust, sparkles, shield shatter, crash)
    for (pt in engine.particles) {
        val alpha = (pt.life / pt.maxLife).coerceIn(0f, 1f)
        val c = when (pt.kind) {
            1 -> palette.accent.copy(alpha = alpha)
            2 -> palette.success.copy(alpha = alpha)
            3 -> palette.danger.copy(alpha = alpha)
            else -> palette.textMuted.copy(alpha = alpha * 0.65f)
        }
        drawCircle(
            color = c,
            radius = pt.sizeDp * densityPx,
            center = Offset(pt.xNorm * size.width, groundY - pt.yDp * densityPx)
        )
    }

    // 8. Pipra Ant Runner (blinks cleanly during post-shield invulnerability)
    val visibleThisFrame = engine.invulnSeconds <= 0f || ((engine.invulnSeconds * 14f).toInt() % 2 == 0)
    if (visibleThisFrame) {
        val antX = size.width * 0.17f
        val antBottomY = groundY - (engine.playerY * densityPx)
        if (engine.hasShield) {
            drawCircle(
                color = palette.success.copy(alpha = 0.20f),
                radius = 28f * densityPx,
                center = Offset(antX, antBottomY - 13f * densityPx)
            )
            drawCircle(
                color = palette.success.copy(alpha = 0.75f),
                radius = 28f * densityPx,
                center = Offset(antX, antBottomY - 13f * densityPx),
                style = Stroke(width = 2.2f * densityPx)
            )
        }
        val tiltDegrees = (-engine.velocityY * 0.025f).coerceIn(-18f, 18f)
        rotate(degrees = tiltDegrees, pivot = Offset(antX, antBottomY - 12f * densityPx)) {
            drawPipraAnt(
                centerX = antX,
                bottomY = antBottomY,
                densityPx = densityPx,
                legPhase = engine.legPhase,
                airborne = engine.playerY > 1f,
                bodyColor = if (nightSky) Color(0xFFF8FAFC) else palette.textPrimary,
                accentColor = palette.accent
            )
        }
    }
}

@Composable
private fun GameOverlayCard(
    title: String,
    subtitle: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = PipraColors.Charcoal),
            border = BorderStroke(1.dp, PipraColors.SilverBorder),
            modifier = Modifier.padding(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = PipraColors.SilverPrimary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PipraColors.ElectricBlueLight
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onPrimary,
                        colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue),
                        modifier = Modifier.height(48.dp)
                    ) { Text(primaryLabel) }
                    OutlinedButton(
                        onClick = onSecondary,
                        border = BorderStroke(1.dp, PipraColors.SilverBorder),
                        modifier = Modifier.height(48.dp)
                    ) { Text(secondaryLabel, color = PipraColors.SilverPrimary) }
                }
            }
        }
    }
}

private fun DrawScope.drawPipraAnt(
    centerX: Float,
    bottomY: Float,
    densityPx: Float,
    legPhase: Float,
    airborne: Boolean,
    bodyColor: Color,
    accentColor: Color
) {
    val bodyY = bottomY - 12f * densityPx
    val stride = if (airborne) 0f else sin(legPhase) * 5.5f * densityPx

    for (i in -1..1) {
        val baseOff = i * 8f * densityPx
        val swing = if (i % 2 == 0) stride else -stride
        drawLine(
            color = bodyColor,
            start = Offset(centerX + baseOff, bodyY + 2f * densityPx),
            end = Offset(centerX + baseOff + swing - 3f * densityPx, bottomY),
            strokeWidth = 2.4f * densityPx,
            cap = StrokeCap.Round
        )
    }

    drawOval(
        color = bodyColor,
        topLeft = Offset(centerX - 18f * densityPx, bodyY - 8f * densityPx),
        size = Size(14f * densityPx, 11f * densityPx)
    )
    drawCircle(
        color = bodyColor,
        radius = 5.2f * densityPx,
        center = Offset(centerX, bodyY - 2f * densityPx)
    )
    drawCircle(
        color = bodyColor,
        radius = 6f * densityPx,
        center = Offset(centerX + 11f * densityPx, bodyY - 5f * densityPx)
    )
    drawCircle(
        color = accentColor,
        radius = 2.1f * densityPx,
        center = Offset(centerX + 13.5f * densityPx, bodyY - 6f * densityPx)
    )
    drawLine(
        color = bodyColor,
        start = Offset(centerX + 14f * densityPx, bodyY - 10f * densityPx),
        end = Offset(centerX + 20f * densityPx, bodyY - 16f * densityPx),
        strokeWidth = 2f * densityPx,
        cap = StrokeCap.Round
    )
}
