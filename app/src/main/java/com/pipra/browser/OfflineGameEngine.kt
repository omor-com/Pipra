package com.pipra.browser

import kotlin.math.abs
import kotlin.random.Random

enum class ObstacleKind {
    SPIKY_BURR,
    TALL_TWIG,
    SWOOPING_BEETLE
}

enum class PickupKind {
    SUGAR_DROP,
    LEAF_SHIELD
}

data class GameObstacle(
    val xNorm: Float,
    val widthDp: Float,
    val heightDp: Float,
    val bottomElevationDp: Float,
    val kind: ObstacleKind,
    val wingPhase: Float = 0f
)

data class GamePickup(
    val xNorm: Float,
    val elevationDp: Float,
    val kind: PickupKind,
    val bobPhase: Float = 0f
)

data class GameParticle(
    val xNorm: Float,
    val yDp: Float,
    val vxNorm: Float,
    val vyDp: Float,
    val life: Float,
    val maxLife: Float,
    val sizeDp: Float,
    val kind: Int // 0 = dust, 1 = sugar sparkle, 2 = shield leaf, 3 = crash
)

data class FloatingCallout(
    val text: String,
    val xNorm: Float,
    val yDp: Float,
    val life: Float,
    val isAccent: Boolean = true
)

enum class GameEvent {
    JUMP,
    DOUBLE_JUMP,
    DIVE,
    SUGAR_COLLECTED,
    SHIELD_COLLECTED,
    SHIELD_USED,
    MILESTONE,
    CRASH
}

enum class AntSkin(
    val key: String,
    val label: String,
    val bodyHex: Long,
    val accentHex: Long
) {
    CLASSIC("classic", "Classic Blue", 0xFF1E293B, 0xFF3B82F6),
    SOLAR_AMBER("amber", "Amber Solar", 0xFF78350F, 0xFFF59E0B),
    EMERALD_LEAF("emerald", "Emerald Shield", 0xFF064E3B, 0xFF10B981),
    CRIMSON_EMBER("crimson", "Crimson Ember", 0xFF7F1D1D, 0xFFF43F5E);

    companion object {
        fun fromKey(raw: String?): AntSkin =
            entries.firstOrNull { it.key.equals(raw, ignoreCase = true) } ?: CLASSIC
    }
}

/**
 * Pure Kotlin state & physics engine for Pipra Ant Dash.
 * Decoupled from Compose/Canvas so physics, pickups, and shield mechanics stay deterministic and unit-testable.
 */
class OfflineGameEngine(
    initialBest: Int,
    private val random: Random = Random.Default
) {
    var hasStarted: Boolean = false
        private set
    var isPaused: Boolean = false
    var isGameOver: Boolean = false
        private set

    var bestScore: Int = initialBest
        private set
    var score: Int = 0
        private set
    var bonusScore: Int = 0
        private set
    var distanceAcc: Float = 0f
        private set
    var newBestAchieved: Boolean = false
        private set

    var playerY: Float = 0f
        private set
    var velocityY: Float = 0f
        private set
    var jumpsUsed: Int = 0
        private set
    var legPhase: Float = 0f
        private set

    var hasShield: Boolean = false
        private set
    var invulnSeconds: Float = 0f
        private set
    var nextMilestone: Int = 100
        private set
    var sugarCollectedCount: Int = 0
        private set

    private var obstacleCooldown: Float = 0.85f
    private var pickupCooldown: Float = 2.4f

    val obstacles = mutableListOf<GameObstacle>()
    val pickups = mutableListOf<GamePickup>()
    val particles = mutableListOf<GameParticle>()
    val callouts = mutableListOf<FloatingCallout>()

    val speedMultiplier: Float
        get() = (1.0f + score * 0.0014f).coerceAtMost(2.25f)

    val worldSpeedNorm: Float
        get() = 0.43f * speedMultiplier

    fun startOrJump(): List<GameEvent> {
        if (isGameOver || isPaused) return emptyList()
        if (!hasStarted) {
            hasStarted = true
            return emptyList()
        }
        if (jumpsUsed < 2) {
            val isDouble = jumpsUsed == 1
            velocityY = if (!isDouble) 670f else 590f
            jumpsUsed++
            spawnBurst(
                xNorm = 0.17f,
                yDp = playerY + 4f,
                count = if (isDouble) 8 else 6,
                kind = if (isDouble) 1 else 0
            )
            return listOf(if (isDouble) GameEvent.DOUBLE_JUMP else GameEvent.JUMP)
        }
        return emptyList()
    }

    fun fastDive(): List<GameEvent> {
        if (!hasStarted || isPaused || isGameOver) return emptyList()
        if (playerY > 1f) {
            velocityY = minOf(velocityY, -920f)
            return listOf(GameEvent.DIVE)
        }
        return emptyList()
    }

    fun restart() {
        obstacles.clear()
        pickups.clear()
        particles.clear()
        callouts.clear()
        hasStarted = true
        isPaused = false
        isGameOver = false
        score = 0
        bonusScore = 0
        distanceAcc = 0f
        newBestAchieved = false
        playerY = 0f
        velocityY = 0f
        jumpsUsed = 0
        hasShield = false
        invulnSeconds = 0f
        nextMilestone = 100
        sugarCollectedCount = 0
        obstacleCooldown = 0.85f
        pickupCooldown = 2.4f
    }

    fun step(rawDt: Float, onNewBest: (Int) -> Unit = {}): List<GameEvent> {
        if (!hasStarted || isPaused || isGameOver) return emptyList()
        val dt = rawDt.coerceIn(0f, 0.05f)
        val events = mutableListOf<GameEvent>()

        if (invulnSeconds > 0f) {
            invulnSeconds = (invulnSeconds - dt).coerceAtLeast(0f)
        }

        val speed = worldSpeedNorm
        legPhase += dt * (13f * speedMultiplier)

        // Vertical jump / gravity integration
        val wasAirborne = playerY > 0.5f
        if (playerY > 0f || velocityY != 0f) {
            velocityY -= 1820f * dt
            playerY += velocityY * dt
            if (playerY <= 0f) {
                playerY = 0f
                velocityY = 0f
                jumpsUsed = 0
                if (wasAirborne) {
                    spawnBurst(0.17f, 2f, count = 4, kind = 0)
                }
            }
        }

        // Scroll & animate obstacles
        val obsIter = obstacles.listIterator()
        while (obsIter.hasNext()) {
            val ob = obsIter.next()
            val nx = ob.xNorm - speed * dt
            if (nx < -0.15f) {
                obsIter.remove()
            } else {
                obsIter.set(ob.copy(xNorm = nx, wingPhase = ob.wingPhase + dt * 14f))
            }
        }

        // Scroll pickups
        val pickIter = pickups.listIterator()
        while (pickIter.hasNext()) {
            val p = pickIter.next()
            val nx = p.xNorm - speed * dt
            if (nx < -0.15f) {
                pickIter.remove()
            } else {
                pickIter.set(p.copy(xNorm = nx, bobPhase = p.bobPhase + dt * 5f))
            }
        }

        // Update particles
        val partIter = particles.listIterator()
        while (partIter.hasNext()) {
            val pt = partIter.next()
            val nextLife = pt.life - dt
            if (nextLife <= 0f) {
                partIter.remove()
            } else {
                partIter.set(
                    pt.copy(
                        xNorm = pt.xNorm + pt.vxNorm * dt,
                        yDp = (pt.yDp + pt.vyDp * dt).coerceAtLeast(0f),
                        vyDp = pt.vyDp - 360f * dt,
                        life = nextLife
                    )
                )
            }
        }

        // Update callouts
        val callIter = callouts.listIterator()
        while (callIter.hasNext()) {
            val c = callIter.next()
            val nextLife = c.life - dt
            if (nextLife <= 0f) {
                callIter.remove()
            } else {
                callIter.set(c.copy(yDp = c.yDp + 28f * dt, life = nextLife))
            }
        }

        // Spawn obstacles
        obstacleCooldown -= dt
        if (obstacleCooldown <= 0f) {
            val roll = random.nextFloat()
            val kind = when {
                score >= 80 && roll < 0.28f -> ObstacleKind.SWOOPING_BEETLE
                roll < 0.62f -> ObstacleKind.SPIKY_BURR
                else -> ObstacleKind.TALL_TWIG
            }
            val (w, h, elev) = when (kind) {
                ObstacleKind.SPIKY_BURR -> Triple(
                    random.nextInt(24, 34).toFloat(),
                    random.nextInt(26, 38).toFloat(),
                    0f
                )
                ObstacleKind.TALL_TWIG -> Triple(
                    random.nextInt(18, 26).toFloat(),
                    random.nextInt(40, 56).toFloat(),
                    0f
                )
                ObstacleKind.SWOOPING_BEETLE -> Triple(
                    30f,
                    22f,
                    if (random.nextBoolean()) 48f else 76f
                )
            }
            obstacles.add(
                GameObstacle(
                    xNorm = 1.08f,
                    widthDp = w,
                    heightDp = h,
                    bottomElevationDp = elev,
                    kind = kind
                )
            )
            val baseGap = (1.30f / speedMultiplier).coerceAtLeast(0.62f)
            obstacleCooldown = baseGap + random.nextFloat() * 0.48f
        }

        // Spawn collectibles (sugar crystals or occasional leaf shield)
        pickupCooldown -= dt
        if (pickupCooldown <= 0f) {
            val spawnShield = !hasShield && random.nextFloat() < 0.18f
            val elev = if (random.nextFloat() < 0.45f) 18f else 74f
            pickups.add(
                GamePickup(
                    xNorm = 1.12f,
                    elevationDp = elev,
                    kind = if (spawnShield) PickupKind.LEAF_SHIELD else PickupKind.SUGAR_DROP
                )
            )
            pickupCooldown = 1.9f + random.nextFloat() * 1.6f
        }

        // Distance + score tracking
        distanceAcc += dt * 42f
        val newScore = distanceAcc.toInt() + bonusScore
        if (newScore != score) {
            score = newScore
            if (score >= nextMilestone) {
                callouts.add(FloatingCallout("${nextMilestone}m!", 0.22f, playerY + 44f, life = 1.1f))
                nextMilestone += 100
                events.add(GameEvent.MILESTONE)
            }
            if (score > bestScore) {
                bestScore = score
                newBestAchieved = true
                onNewBest(score)
            }
        }

        // Pickup collisions
        val antCenterX = 0.17f
        val antBottom = playerY
        val antTop = playerY + 24f
        val pIter = pickups.iterator()
        while (pIter.hasNext()) {
            val p = pIter.next()
            val closeX = abs(p.xNorm - antCenterX) < 0.045f
            val closeY = antTop >= p.elevationDp - 10f && antBottom <= p.elevationDp + 24f
            if (closeX && closeY) {
                pIter.remove()
                when (p.kind) {
                    PickupKind.SUGAR_DROP -> {
                        bonusScore += 25
                        sugarCollectedCount++
                        score = distanceAcc.toInt() + bonusScore
                        if (score > bestScore) {
                            bestScore = score
                            newBestAchieved = true
                            onNewBest(score)
                        }
                        spawnBurst(antCenterX, p.elevationDp + 6f, count = 10, kind = 1)
                        callouts.add(FloatingCallout("+25", antCenterX + 0.04f, p.elevationDp + 22f, life = 0.9f))
                        events.add(GameEvent.SUGAR_COLLECTED)
                    }
                    PickupKind.LEAF_SHIELD -> {
                        hasShield = true
                        spawnBurst(antCenterX, p.elevationDp + 6f, count = 10, kind = 2)
                        callouts.add(FloatingCallout("SHIELD!", antCenterX + 0.04f, p.elevationDp + 24f, life = 1.0f))
                        events.add(GameEvent.SHIELD_COLLECTED)
                    }
                }
            }
        }

        // Obstacle collisions
        if (invulnSeconds <= 0f) {
            val playerLeft = 0.148f
            val playerRight = 0.194f
            val pBot = playerY + 3f
            val pTop = playerY + 21f
            val hitIter = obstacles.iterator()
            while (hitIter.hasNext()) {
                val ob = hitIter.next()
                val halfW = 0.022f
                val overlapX = playerRight > (ob.xNorm - halfW) && playerLeft < (ob.xNorm + halfW)
                if (overlapX) {
                    val obBot = ob.bottomElevationDp + 2f
                    val obTop = ob.bottomElevationDp + ob.heightDp - 2f
                    if (pTop > obBot && pBot < obTop) {
                        if (hasShield) {
                            hasShield = false
                            invulnSeconds = 1.15f
                            hitIter.remove()
                            spawnBurst(antCenterX, playerY + 12f, count = 12, kind = 2)
                            callouts.add(FloatingCallout("SAVED!", antCenterX + 0.04f, playerY + 34f, life = 0.95f, isAccent = false))
                            events.add(GameEvent.SHIELD_USED)
                        } else {
                            isGameOver = true
                            spawnBurst(antCenterX, playerY + 12f, count = 16, kind = 3)
                            events.add(GameEvent.CRASH)
                            break
                        }
                    }
                }
            }
        }

        return events
    }

    private fun spawnBurst(xNorm: Float, yDp: Float, count: Int, kind: Int) {
        for (i in 0 until count) {
            particles.add(
                GameParticle(
                    xNorm = xNorm + (random.nextFloat() - 0.5f) * 0.02f,
                    yDp = yDp,
                    vxNorm = (random.nextFloat() - 0.5f) * 0.28f,
                    vyDp = 70f + random.nextFloat() * 140f,
                    life = 0.38f + random.nextFloat() * 0.25f,
                    maxLife = 0.63f,
                    sizeDp = 2.5f + random.nextFloat() * 3f,
                    kind = kind
                )
            )
        }
    }
}
