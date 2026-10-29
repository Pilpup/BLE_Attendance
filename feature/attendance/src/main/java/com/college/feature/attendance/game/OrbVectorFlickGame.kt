package com.college.feature.attendance.game

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Game #4: Orb Vector Flick
 * High-tech Tsoding glowing orbs spawn with internal directional arrows.
 * The player must touch the orb and flick/slide in the direction of the arrow.
 *
 * Prevents dual-phone cheating: Gaze-shifting prevents the brain from holding two
 * opposing spatial-vector motor programs in queue simultaneously.
 */
@Composable
fun OrbVectorFlickGame(
    seedKey: String,
    onProgress: (hitCount: Int, totalTrials: Int) -> Unit,
    onComplete: (interactions: List<GameInteractionRecord>) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 4 cardinal directions: 0 = RIGHT, PI/2 = DOWN, PI = LEFT, -PI/2 = UP
    data class OrbTarget(
        val relX: Float,
        val relY: Float,
        val angleRad: Float,
        val directionLabel: String
    )

    val targets = remember(seedKey) {
        val seed = seedKey.hashCode().toLong().absoluteValue
        val rng = Random(seed)
        val directions = listOf(
            0f to "RIGHT",
            (PI / 2.0).toFloat() to "DOWN",
            PI.toFloat() to "LEFT",
            (-PI / 2.0).toFloat() to "UP"
        )
        val list = mutableListOf<OrbTarget>()

        // Fixed comfortable spawn sectors to guarantee ergonomic one-thumb reach
        val positions = listOf(
            0.50f to 0.40f,
            0.30f to 0.60f,
            0.70f to 0.50f
        )

        for (i in 0 until 3) {
            val dir = directions[rng.nextInt(directions.size)]
            val pos = positions[i % positions.size]
            list.add(
                OrbTarget(
                    relX = pos.first + (rng.nextFloat() * 0.10f - 0.05f),
                    relY = pos.second + (rng.nextFloat() * 0.10f - 0.05f),
                    angleRad = dir.first,
                    directionLabel = dir.second
                )
            )
        }
        list
    }

    var currentTargetIndex by remember { mutableIntStateOf(0) }
    var spawnTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val interactions = remember { mutableStateListOf<GameInteractionRecord>() }
    var isTransitioning by remember { mutableStateOf(false) }

    // Touch and drag tracking
    var isTouchingCurrentOrb by remember { mutableStateOf(false) }
    var activeTouchOffset by remember { mutableStateOf<Offset?>(null) }
    var dragStartOffset by remember { mutableStateOf<Offset?>(null) }

    // Ambient pulse
    val pulseTransition = rememberInfiniteTransition(label = "orb_pulse")
    val pulsePhase by pulseTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    fun completeTrial(isCorrect: Boolean, angleDeviationDeg: Float, latency: Long) {
        if (isTransitioning || currentTargetIndex >= targets.size) return
        isTransitioning = true

        val target = targets[currentTargetIndex]
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)

        interactions.add(
            GameInteractionRecord(
                trialIndex = currentTargetIndex,
                latencyMs = latency,
                isCorrect = isCorrect,
                vectorDeviationDeg = angleDeviationDeg,
                stimulusKey = "ORB_FLICK_${target.directionLabel}"
            )
        )

        val hits = interactions.count { it.isCorrect }
        onProgress(hits, targets.size)

        scope.launch {
            delay(120L)
            isTouchingCurrentOrb = false
            activeTouchOffset = null
            dragStartOffset = null

            if (currentTargetIndex + 1 < targets.size) {
                currentTargetIndex++
                spawnTimestamp = System.currentTimeMillis()
                isTransitioning = false
            } else {
                onComplete(interactions.toList())
            }
        }
    }

    // 1300ms timeout per orb
    LaunchedEffect(currentTargetIndex) {
        spawnTimestamp = System.currentTimeMillis()
        delay(1300L)
        if (!isTransitioning && currentTargetIndex < targets.size) {
            completeTrial(isCorrect = false, angleDeviationDeg = 180f, latency = 1300L)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF040A07), Color(0xFF020604), Color(0xFF07140E))
                )
            )
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header HUD
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "TARGET ${currentTargetIndex + 1} OF ${targets.size}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = TsodingOrbRenderer.CyanPulse
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "TOUCH ORB & FLICK IN ARROW DIRECTION",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Interactive Canvas Viewport
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(currentTargetIndex) {
                        val orbRadiusPx = with(density) { 54.dp.toPx() }
                        val minFlickDistPx = with(density) { 36.dp.toPx() }

                        detectDragGestures(
                            onDragStart = { startPos ->
                                if (currentTargetIndex >= targets.size || isTransitioning) return@detectDragGestures
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                val curr = targets[currentTargetIndex]
                                val orbCenter = Offset(curr.relX * w, curr.relY * h)

                                val dx = startPos.x - orbCenter.x
                                val dy = startPos.y - orbCenter.y
                                val dist = sqrt(dx * dx + dy * dy)

                                if (dist <= orbRadiusPx * 1.5f) {
                                    isTouchingCurrentOrb = true
                                    dragStartOffset = startPos
                                    activeTouchOffset = startPos
                                }
                            },
                            onDrag = { change, _ ->
                                if (isTouchingCurrentOrb) {
                                    activeTouchOffset = change.position
                                    val start = dragStartOffset ?: return@detectDragGestures
                                    val currPos = change.position

                                    val dx = currPos.x - start.x
                                    val dy = currPos.y - start.y
                                    val dist = sqrt(dx * dx + dy * dy)

                                    if (dist >= minFlickDistPx) {
                                        // Calculate angle of user's flick
                                        val flickAngleRad = atan2(dy, dx)
                                        val target = targets[currentTargetIndex]
                                        var angleDiff = abs(flickAngleRad - target.angleRad)
                                        if (angleDiff > PI) angleDiff = (2 * PI - angleDiff).toFloat()

                                        val angleDiffDeg = (angleDiff * 180.0 / PI).toFloat()
                                        val now = System.currentTimeMillis()
                                        val latency = now - spawnTimestamp

                                        // Correct if within ±45 degrees of target arrow
                                        val isCorrect = angleDiffDeg <= 45f
                                        completeTrial(isCorrect, angleDiffDeg, latency)
                                    }
                                }
                            },
                            onDragEnd = {
                                isTouchingCurrentOrb = false
                                activeTouchOffset = null
                                dragStartOffset = null
                            },
                            onDragCancel = {
                                isTouchingCurrentOrb = false
                                activeTouchOffset = null
                                dragStartOffset = null
                            }
                        )
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val orbRadius = with(density) { 52.dp.toPx() }

                    if (currentTargetIndex < targets.size) {
                        val curr = targets[currentTargetIndex]
                        val orbCenter = Offset(curr.relX * w, curr.relY * h)

                        // 1. Draw Tsoding Glowing Orb with Musializer gradient falloff
                        TsodingOrbRenderer.drawGlowingOrb(
                            drawScope = this,
                            center = orbCenter,
                            radius = orbRadius * pulsePhase,
                            baseColor = if (isTouchingCurrentOrb) TsodingOrbRenderer.SolarOrange else TsodingOrbRenderer.EmeraldGreen,
                            pulseAlpha = 1.0f
                        )

                        // 2. Draw Internal Directional Arrow Chevron
                        TsodingOrbRenderer.drawDirectionalArrow(
                            drawScope = this,
                            center = orbCenter,
                            sizePx = orbRadius * 0.95f,
                            angleRad = curr.angleRad,
                            arrowColor = Color.White,
                            strokeWidthPx = 7.dp.toPx()
                        )

                        // 3. Draw Touch Vector Beam if finger is dragging
                        val touch = activeTouchOffset
                        if (isTouchingCurrentOrb && touch != null) {
                            drawLine(
                                color = TsodingOrbRenderer.CyanPulse,
                                start = orbCenter,
                                end = touch,
                                strokeWidth = 5.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                            drawCircle(
                                color = TsodingOrbRenderer.PureWhite,
                                radius = 9.dp.toPx(),
                                center = touch
                            )
                        }

                        // 4. Shrinking Timing Approach Ring
                        val elapsed = System.currentTimeMillis() - spawnTimestamp
                        val progress = (elapsed / 1300f).coerceIn(0f, 1f)
                        val approachScale = 2.4f - (progress * 1.4f) // Shrinks from 2.4x to 1.0x
                        drawCircle(
                            color = Color.White.copy(alpha = (1f - progress).coerceIn(0.2f, 0.8f)),
                            radius = orbRadius * approachScale,
                            center = orbCenter,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
            }
        }
    }
}
