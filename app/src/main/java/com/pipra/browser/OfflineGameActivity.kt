package com.pipra.browser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pipra.browser.ui.theme.LocalPipraColors
import com.pipra.browser.ui.theme.PipraColors
import com.pipra.browser.ui.theme.PipraTheme
import kotlin.math.sin
import kotlin.random.Random

private data class RunnerObstacle(
    val xNorm: Float,
    val widthDp: Float,
    val heightDp: Float,
    val isAerial: Boolean
)

/**
 * Original 100% offline "Pipra Ant Dash" endless runner game.
 * Uses local physics, persistent best score in Store, and adapts to Pipra Light/Dark themes.
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
    var bestScore by remember { mutableIntStateOf(initialBest) }
    var score by remember { mutableIntStateOf(0) }
    var distanceAcc by remember { mutableFloatStateOf(0f) }
    var isPaused by remember { mutableStateOf(false) }
    var isGameOver by remember { mutableStateOf(false) }
    var newBestAchieved by remember { mutableStateOf(false) }

    // Normalized vertical physics (y = 0 on ground, positive upwards in dp-equivalent units)
    var playerY by remember { mutableFloatStateOf(0f) }
    var velocityY by remember { mutableFloatStateOf(0f) }
    var jumpsUsed by remember { mutableIntStateOf(0) }
    var legPhase by remember { mutableFloatStateOf(0f) }
    var spawnCooldown by remember { mutableFloatStateOf(0.9f) }
    val obstacles = remember { mutableStateListOf<RunnerObstacle>() }

    fun jump() {
        if (isGameOver || isPaused) return
        if (jumpsUsed < 2) {
            velocityY = if (jumpsUsed == 0) 640f else 560f
            jumpsUsed++
        }
    }

    fun restart() {
        obstacles.clear()
        playerY = 0f
        velocityY = 0f
        jumpsUsed = 0
        score = 0
        distanceAcc = 0f
        spawnCooldown = 0.8f
        isPaused = false
        isGameOver = false
        newBestAchieved = false
    }

    LaunchedEffect(isPaused, isGameOver) {
        var lastNanos = 0L
        while (!isPaused && !isGameOver) {
            withFrameNanos { now ->
                if (lastNanos == 0L) {
                    lastNanos = now
                    return@withFrameNanos
                }
                val dt = ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                lastNanos = now

                // Speed scales gently with score
                val worldSpeedNorm = (0.42f + (score * 0.00055f)).coerceAtMost(0.95f)
                legPhase += dt * (12f + score * 0.02f)

                // Gravity & player jump integration
                if (playerY > 0f || velocityY != 0f) {
                    velocityY -= 1750f * dt
                    playerY += velocityY * dt
                    if (playerY <= 0f) {
                        playerY = 0f
                        velocityY = 0f
                        jumpsUsed = 0
                    }
                }

                // Move & cull obstacles
                val iter = obstacles.listIterator()
                while (iter.hasNext()) {
                    val ob = iter.next()
                    val nx = ob.xNorm - worldSpeedNorm * dt
                    if (nx < -0.15f) {
                        iter.remove()
                    } else {
                        iter.set(ob.copy(xNorm = nx))
                    }
                }

                // Spawn new obstacle
                spawnCooldown -= dt
                if (spawnCooldown <= 0f) {
                    val aerial = score >= 120 && Random.nextFloat() < 0.25f
                    obstacles.add(
                        RunnerObstacle(
                            xNorm = 1.08f,
                            widthDp = if (aerial) 30f else Random.nextInt(22, 34).toFloat(),
                            heightDp = if (aerial) 24f else Random.nextInt(32, 54).toFloat(),
                            isAerial = aerial
                        )
                    )
                    val baseGap = (1.35f - (score * 0.0007f)).coerceAtLeast(0.68f)
                    spawnCooldown = baseGap + Random.nextFloat() * 0.55f
                }

                // Update score
                distanceAcc += dt * 40f
                val newScore = distanceAcc.toInt()
                if (newScore != score) {
                    score = newScore
                    if (newScore > bestScore) {
                        bestScore = newScore
                        newBestAchieved = true
                        onBestUpdated(newScore)
                    }
                }

                // Simple AABB collision (player anchor xNorm = 0.16f, width = 34dp, height = 26dp)
                val playerLeftNorm = 0.145f
                val playerRightNorm = 0.195f
                val playerBottom = playerY
                val playerTop = playerY + 24f

                for (ob in obstacles) {
                    val obHalf = 0.025f
                    val overlapX = playerRightNorm > (ob.xNorm - obHalf) && playerLeftNorm < (ob.xNorm + obHalf)
                    if (overlapX) {
                        val obBottom = if (ob.isAerial) 48f else 0f
                        val obTop = obBottom + ob.heightDp
                        if (playerTop > obBottom + 4f && playerBottom < obTop - 4f) {
                            isGameOver = true
                            break
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = PipraColors.MatteBlack,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
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
                        text = "Best: $bestScore",
                        style = MaterialTheme.typography.bodySmall,
                        color = PipraColors.ElectricBlueLight
                    )
                }
                Text(
                    text = score.toString().padStart(5, '0'),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = PipraColors.SilverPrimary,
                    modifier = Modifier.padding(end = 8.dp).testTag("game_score_text")
                )
                IconButton(
                    onClick = { if (!isGameOver) isPaused = !isPaused },
                    modifier = Modifier.testTag("game_pause_button")
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isPaused) "Resume" else "Pause",
                        tint = PipraColors.SilverPrimary
                    )
                }
                IconButton(
                    onClick = { restart() },
                    modifier = Modifier.testTag("game_restart_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Restart",
                        tint = PipraColors.SilverPrimary
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(PipraColors.Charcoal, RoundedCornerShape(18.dp))
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { jump() })
                    }
                    .testTag("game_canvas")
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val groundY = size.height * 0.76f
                    val densityPx = density

                    // Subtle sky arc
                    drawCircle(
                        color = palette.accent.copy(alpha = 0.08f),
                        radius = size.minDimension * 0.24f,
                        center = Offset(size.width * 0.82f, size.height * 0.22f)
                    )

                    // Ground surface
                    drawRect(
                        color = palette.cardSurface,
                        topLeft = Offset(0f, groundY),
                        size = Size(size.width, size.height - groundY)
                    )
                    drawLine(
                        color = palette.border,
                        start = Offset(0f, groundY),
                        end = Offset(size.width, groundY),
                        strokeWidth = 3f
                    )

                    // Scrolling ground ticks
                    val stride = 70f * densityPx
                    val offset = (distanceAcc * 14f * densityPx) % stride
                    var tx = -offset
                    while (tx < size.width) {
                        drawLine(
                            color = palette.border.copy(alpha = 0.7f),
                            start = Offset(tx, groundY + 12f * densityPx),
                            end = Offset(tx + 18f * densityPx, groundY + 12f * densityPx),
                            strokeWidth = 2f * densityPx
                        )
                        tx += stride
                    }

                    // Draw obstacles
                    for (ob in obstacles) {
                        val obW = ob.widthDp * densityPx
                        val obH = ob.heightDp * densityPx
                        val cx = ob.xNorm * size.width
                        val bottom = groundY - (if (ob.isAerial) 48f * densityPx else 0f)
                        drawRoundRect(
                            color = if (ob.isAerial) palette.warning else palette.danger,
                            topLeft = Offset(cx - obW / 2f, bottom - obH),
                            size = Size(obW, obH),
                            cornerRadius = CornerRadius(6f * densityPx, 6f * densityPx)
                        )
                    }

                    // Draw Pipra Ant runner
                    val antX = size.width * 0.17f
                    val antBottomY = groundY - (playerY * densityPx)
                    drawPipraAnt(
                        centerX = antX,
                        bottomY = antBottomY,
                        densityPx = densityPx,
                        legPhase = legPhase,
                        airborne = playerY > 1f,
                        bodyColor = palette.textPrimary,
                        accentColor = palette.accent
                    )
                }

                if (isPaused && !isGameOver) {
                    GameOverlayCard(
                        title = "Game Paused",
                        subtitle = "Score: $score · Best: $bestScore",
                        primaryLabel = "Resume",
                        onPrimary = { isPaused = false },
                        secondaryLabel = "Exit",
                        onSecondary = onExit
                    )
                }

                if (isGameOver) {
                    GameOverlayCard(
                        title = if (newBestAchieved) "New High Score!" else "Game Over",
                        subtitle = "Score: $score · Best: $bestScore",
                        primaryLabel = "Play Again",
                        onPrimary = { restart() },
                        secondaryLabel = "Back to Browser",
                        onSecondary = onExit
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Tap canvas or press Jump (double jump supported)",
                    style = MaterialTheme.typography.bodySmall,
                    color = PipraColors.SilverMuted,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { jump() },
                    colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue),
                    modifier = Modifier.testTag("game_jump_button")
                ) {
                    Text("Jump")
                }
            }
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
            shape = RoundedCornerShape(16.dp),
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
                        colors = ButtonDefaults.buttonColors(containerColor = PipraColors.ElectricBlue)
                    ) { Text(primaryLabel) }
                    OutlinedButton(
                        onClick = onSecondary,
                        border = BorderStroke(1.dp, PipraColors.SilverBorder)
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
    val stride = if (airborne) 0f else sin(legPhase) * 5f * densityPx

    // Legs (3 visible side pairs)
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

    // Abdomen (left)
    drawOval(
        color = bodyColor,
        topLeft = Offset(centerX - 18f * densityPx, bodyY - 8f * densityPx),
        size = Size(14f * densityPx, 11f * densityPx)
    )
    // Thorax (middle)
    drawCircle(
        color = bodyColor,
        radius = 5.2f * densityPx,
        center = Offset(centerX, bodyY - 2f * densityPx)
    )
    // Head (right)
    drawCircle(
        color = bodyColor,
        radius = 6f * densityPx,
        center = Offset(centerX + 11f * densityPx, bodyY - 5f * densityPx)
    )
    // Eye
    drawCircle(
        color = accentColor,
        radius = 2f * densityPx,
        center = Offset(centerX + 13.5f * densityPx, bodyY - 6f * densityPx)
    )
    // Antennae
    drawLine(
        color = bodyColor,
        start = Offset(centerX + 14f * densityPx, bodyY - 10f * densityPx),
        end = Offset(centerX + 20f * densityPx, bodyY - 16f * densityPx),
        strokeWidth = 2f * densityPx,
        cap = StrokeCap.Round
    )
}
