package com.college.feature.attendance.game

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.college.feature.attendance.telemetry.ExpiredTargetEvent
import com.college.feature.attendance.telemetry.TapEvent
import kotlinx.coroutines.delay
import java.util.Random
import kotlin.math.absoluteValue
import kotlin.math.sin
import kotlin.math.sqrt

data class ReflexTarget(
    val id: Int,
    val relX: Float,
    val relY: Float,
    val isGolden: Boolean = false,
    val isTapped: Boolean = false,
    val spawnTimeMillis: Long = 0L,
    val lifetimeMillis: Long = 1200L
)

// Pre-computed radial gradient color stops for orb glow rendering
private val EmeraldStops = arrayOf(
    0.00f to Color(0xFF6BFFD0),
    0.14f to Color(0xFF00F5A0),
    0.28f to Color(0x8000F5A0),
    0.44f to Color(0x2E00F5A0),
    0.62f to Color(0x0A00F5A0),
    0.80f to Color.Transparent,
    1.00f to Color.Transparent
)

private val SolarStops = arrayOf(
    0.00f to Color(0xFFFFB74D),
    0.14f to Color(0xFFFF9100),
    0.28f to Color(0x80FF9100),
    0.44f to Color(0x2EFF9100),
    0.62f to Color(0x0AFF9100),
    0.80f to Color.Transparent,
    1.00f to Color.Transparent
)

// Generates randomized target position ensuring minimum distance from existing orbs
private fun generateNewTarget(
    id: Int,
    existingTargets: List<ReflexTarget>,
    rng: Random
): ReflexTarget {
    var bestX = 0.5f
    var bestY = 0.5f
    var maxDistToNearest = -1f
    val minDistance = 0.30f
    val minDistanceSq = minDistance * minDistance

    for (attempt in 0 until 120) {
        val candidateX = 0.08f + (rng.nextFloat() * 0.84f)
        val candidateY = 0.08f + (rng.nextFloat() * 0.84f)

        if (existingTargets.isEmpty()) {
            bestX = candidateX
            bestY = candidateY
            break
        }

        var nearestDistSq = Float.MAX_VALUE
        for (i in existingTargets.indices) {
            val p = existingTargets[i]
            val dx = candidateX - p.relX
            val dy = candidateY - p.relY
            val d2 = dx * dx + dy * dy
            if (d2 < nearestDistSq) {
                nearestDistSq = d2
            }
        }

        if (nearestDistSq >= minDistanceSq) {
            bestX = candidateX
            bestY = candidateY
            break
        }

        if (nearestDistSq > maxDistToNearest) {
            maxDistToNearest = nearestDistSq
            bestX = candidateX
            bestY = candidateY
        }
    }

    val isGolden = (rng.nextFloat() < 0.12f)
    return ReflexTarget(
        id = id,
        relX = bestX,
        relY = bestY,
        isGolden = isGolden,
        isTapped = false,
        spawnTimeMillis = System.currentTimeMillis(),
        lifetimeMillis = 1200L
    )
}

// Interactive multi-orb reflex challenge with decay timers and continuous replenishment
@Composable
fun OrbPoppingGame(
    seedKey: String,
    onProgress: (hitCount: Int, totalTrials: Int) -> Unit,
    onComplete: (taps: List<TapEvent>, expired: List<ExpiredTargetEvent>, totalGoal: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    val rng = remember(seedKey) {
        val seed = seedKey.hashCode().toLong().absoluteValue
        Random(seed)
    }

    val targetsGoal = 18
    var targetsHit by remember { mutableIntStateOf(0) }
    var totalTargetsSpawned by remember { mutableIntStateOf(0) }
    var activeTargets by remember { mutableStateOf<List<ReflexTarget>>(emptyList()) }
    var currentTimeMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isGameOver by remember { mutableStateOf(false) }

    val recordedTaps = remember { mutableStateListOf<TapEvent>() }
    val recordedExpired = remember { mutableStateListOf<ExpiredTargetEvent>() }

    val spiritTransition = rememberInfiniteTransition(label = "spirit_anim")
    val spiritPhase by spiritTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(3400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spirit_phase"
    )

    LaunchedEffect(seedKey) {
        targetsHit = 0
        isGameOver = false
        recordedTaps.clear()
        recordedExpired.clear()

        // Spawn initial wave of targets with staggered entrance delays
        val initial = mutableListOf<ReflexTarget>()
        val now = System.currentTimeMillis()
        for (i in 0 until 6) {
            val t = generateNewTarget(id = i, existingTargets = initial, rng = rng)
            initial.add(t.copy(spawnTimeMillis = now + (i * 80L), lifetimeMillis = 1200L))
        }
        activeTargets = initial
        totalTargetsSpawned = initial.size
        onProgress(targetsHit, totalTargetsSpawned)

        // Main game loop tracking expiration, replenishment, and completion
        while (!isGameOver) {
            val nowTime = System.currentTimeMillis()
            currentTimeMillis = nowTime

            // Record targets that exceeded their lifetime without a tap
            val newlyExpired = activeTargets.filter { target ->
                !target.isTapped && (nowTime - target.spawnTimeMillis >= target.lifetimeMillis) &&
                        recordedExpired.none { it.targetId == target.id }
            }
            for (e in newlyExpired) {
                recordedExpired.add(
                    ExpiredTargetEvent(
                        targetId = e.id,
                        spawnTimeMillis = e.spawnTimeMillis,
                        expiredTimeMillis = nowTime,
                        targetRelX = e.relX,
                        targetRelY = e.relY
                    )
                )
            }

            // Count currently active living targets
            val livingCount = activeTargets.count { target ->
                !target.isTapped && (nowTime - target.spawnTimeMillis < target.lifetimeMillis)
            }

            // Replenish targets when living count drops below threshold
            if (livingCount < 3 && totalTargetsSpawned < targetsGoal) {
                val needed = (6 - livingCount).coerceAtMost(targetsGoal - totalTargetsSpawned)
                if (needed > 0) {
                    val living = activeTargets.filter { target ->
                        !target.isTapped && (nowTime - target.spawnTimeMillis < target.lifetimeMillis)
                    }.toMutableList()
                    val newOrbs = mutableListOf<ReflexTarget>()
                    for (k in 0 until needed) {
                        val newOrb = generateNewTarget(
                            id = totalTargetsSpawned + k,
                            existingTargets = living + newOrbs,
                            rng = rng
                        ).copy(
                            spawnTimeMillis = nowTime + (k * 80L),
                            lifetimeMillis = 1200L
                        )
                        newOrbs.add(newOrb)
                    }
                    activeTargets = activeTargets + newOrbs
                    totalTargetsSpawned += newOrbs.size
                    onProgress(targetsHit, totalTargetsSpawned)
                }
            }

            // Complete the game once all targets have spawned and resolved
            if (totalTargetsSpawned >= targetsGoal && livingCount == 0) {
                activeTargets = emptyList()
                isGameOver = true
                onComplete(recordedTaps.toList(), recordedExpired.toList(), targetsGoal)
                break
            }

            delay(16L)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val targetSize = 120.dp
        val availW = (maxWidth - targetSize - 16.dp).coerceAtLeast(0.dp)
        val availH = (maxHeight - targetSize - 16.dp).coerceAtLeast(0.dp)

        // Ambient background glow
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        Color(0x1400F5A0),
                        Color(0x0600F5A0),
                        Color.Transparent
                    ),
                    center = center,
                    radius = size.width * 0.65f
                ),
                radius = size.width * 0.65f,
                center = center
            )
        }

        // Active target layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            activeTargets.forEach { target ->
                val targetAge = currentTimeMillis - target.spawnTimeMillis
                val isExpired = targetAge >= target.lifetimeMillis

                if (!target.isTapped && !isExpired && targetAge >= 0L) {
                    val fadeDuration = 220f
                    val spawnProgress = (targetAge.toFloat() / fadeDuration).coerceIn(0f, 1f)
                    val despawnProgress = ((target.lifetimeMillis - targetAge).toFloat() / fadeDuration).coerceIn(0f, 1f)
                    val rawFade = spawnProgress * despawnProgress
                    val envelopeAlpha = rawFade * rawFade * (3f - 2f * rawFade)
                    val envelopeScale = 0.72f + (0.28f * envelopeAlpha)

                    val xDp = availW * target.relX
                    val yDp = availH * target.relY

                    // Target pulse animation
                    val pulseNorm = (sin((spiritPhase * 1.5f + target.id * 1.57f).toDouble()).toFloat() + 1f) / 2f
                    val targetPulseScale = (0.94f + (0.08f * pulseNorm)) * envelopeScale
                    val targetPulseAlpha = 0.60f + (0.40f * pulseNorm)

                    Box(
                        modifier = Modifier
                            .offset(x = xDp, y = yDp)
                            .size(targetSize)
                            .pointerInput(target.id) {
                                detectTapGestures { offset ->
                                    if (target.isTapped) return@detectTapGestures
                                    val nowTapTime = System.currentTimeMillis()
                                    if (nowTapTime - target.spawnTimeMillis >= target.lifetimeMillis) return@detectTapGestures

                                    val targetSizePx = size.width.toFloat()
                                    val centerX = targetSizePx / 2f
                                    val centerY = targetSizePx / 2f
                                    val dx = offset.x - centerX
                                    val dy = offset.y - centerY
                                    val radius = (targetSizePx / 2f).coerceAtLeast(1f)
                                    val dist = sqrt(dx * dx + dy * dy)

                                    if (dist <= radius) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val latency = nowTapTime - target.spawnTimeMillis
                                        val normDist = (dist / radius).coerceIn(0f, 1f)

                                        // Record tap telemetry event
                                        recordedTaps.add(
                                            TapEvent(
                                                targetId = target.id,
                                                tapTimeMillis = nowTapTime,
                                                spawnTimeMillis = target.spawnTimeMillis,
                                                latencyMs = latency,
                                                targetRelX = target.relX,
                                                targetRelY = target.relY,
                                                touchOffsetX = dx,
                                                touchOffsetY = dy,
                                                normalizedRadialOffset = normDist
                                            )
                                        )

                                        // Update hit state and trigger replenishment check
                                        activeTargets = activeTargets.map {
                                            if (it.id == target.id) it.copy(isTapped = true) else it
                                        }
                                        targetsHit++
                                        onProgress(targetsHit, totalTargetsSpawned)

                                        val livingNow = activeTargets.count { t ->
                                            !t.isTapped && (nowTapTime - t.spawnTimeMillis < t.lifetimeMillis)
                                        }
                                        if (livingNow < 3 && totalTargetsSpawned < targetsGoal) {
                                            val needed = (6 - livingNow).coerceAtMost(targetsGoal - totalTargetsSpawned)
                                            if (needed > 0) {
                                                val living = activeTargets.filter { t ->
                                                    !t.isTapped && (nowTapTime - t.spawnTimeMillis < t.lifetimeMillis)
                                                }.toMutableList()
                                                val newOrbs = mutableListOf<ReflexTarget>()
                                                for (k in 0 until needed) {
                                                    val newOrb = generateNewTarget(
                                                        id = totalTargetsSpawned + k,
                                                        existingTargets = living + newOrbs,
                                                        rng = rng
                                                    ).copy(
                                                        spawnTimeMillis = nowTapTime + (k * 80L),
                                                        lifetimeMillis = 1200L
                                                    )
                                                    newOrbs.add(newOrb)
                                                }
                                                activeTargets = activeTargets + newOrbs
                                                totalTargetsSpawned += newOrbs.size
                                                onProgress(targetsHit, totalTargetsSpawned)
                                            }
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(
                            modifier = Modifier
                                .size(targetSize)
                                .graphicsLayer {
                                    scaleX = targetPulseScale
                                    scaleY = targetPulseScale
                                    alpha = envelopeAlpha
                                }
                        ) {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val maxRadius = size.width / 2f
                            val stops = if (target.isGolden) SolarStops else EmeraldStops

                            drawCircle(
                                brush = Brush.radialGradient(
                                    colorStops = stops,
                                    center = center,
                                    radius = maxRadius
                                ),
                                radius = maxRadius,
                                center = center,
                                alpha = targetPulseAlpha
                            )
                        }
                    }
                }
            }
        }
    }
}
