package com.college.feature.attendance.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.geometry.Size
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
import kotlin.math.absoluteValue
import kotlin.math.sqrt

/**
 * Game #6: Pop-Out Anomaly (Landolt-C Feature Search)
 * 8 glowing rings appear on screen; 7 are closed solid rings, exactly 1 has a broken notch.
 * Rule: Tap the broken ring.
 *
 * Parallel visual pop-out occurs pre-attentively in <400ms under unbroken gaze.
 * In peripheral vision, the notch is invisible, forcing dual-phone cheaters into a slow serial scan.
 */
@Composable
fun PopOutAnomalyGame(
    seedKey: String,
    onProgress: (hitCount: Int, totalTrials: Int) -> Unit,
    onComplete: (interactions: List<GameInteractionRecord>) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    data class RingItem(val relX: Float, val relY: Float, val isBroken: Boolean, val gapStartAngle: Float)
    data class PopOutTrial(val rings: List<RingItem>, val brokenIndex: Int)

    val trials = remember(seedKey) {
        val seed = seedKey.hashCode().toLong().absoluteValue
        val rng = Random(seed)
        val list = mutableListOf<PopOutTrial>()

        // Fixed comfortable non-overlapping screen positions
        val gridPositions = listOf(
            0.25f to 0.25f, 0.50f to 0.22f, 0.75f to 0.25f,
            0.30f to 0.48f,                 0.70f to 0.48f,
            0.25f to 0.72f, 0.50f to 0.75f, 0.75f to 0.72f
        )

        for (t in 0 until 5) {
            val brokenIdx = rng.nextInt(gridPositions.size)
            val ringList = gridPositions.mapIndexed { idx, pos ->
                val jitterX = (rng.nextFloat() * 0.06f - 0.03f)
                val jitterY = (rng.nextFloat() * 0.06f - 0.03f)
                val isBroken = (idx == brokenIdx)
                val gapAngle = (rng.nextInt(4) * 90f) + 15f
                RingItem(
                    relX = (pos.first + jitterX).coerceIn(0.15f, 0.85f),
                    relY = (pos.second + jitterY).coerceIn(0.15f, 0.85f),
                    isBroken = isBroken,
                    gapStartAngle = gapAngle
                )
            }
            list.add(PopOutTrial(ringList, brokenIdx))
        }
        list
    }

    var currentRound by remember { mutableIntStateOf(0) }
    var roundSpawnTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val interactions = remember { mutableStateListOf<GameInteractionRecord>() }
    var isTransitioning by remember { mutableStateOf(false) }

    fun processTap(tapOffset: Offset, w: Float, h: Float) {
        if (isTransitioning || currentRound >= trials.size) return
        val trial = trials[currentRound]
        val hitRadius = with(density) { 48.dp.toPx() }

        // Find which ring was tapped
        var hitRingIndex = -1
        var hitDist = 999f
        for (i in trial.rings.indices) {
            val ring = trial.rings[i]
            val ringCenter = Offset(ring.relX * w, ring.relY * h)
            val dx = tapOffset.x - ringCenter.x
            val dy = tapOffset.y - ringCenter.y
            val d = sqrt(dx * dx + dy * dy)
            if (d <= hitRadius && d < hitDist) {
                hitDist = d
                hitRingIndex = i
            }
        }

        if (hitRingIndex == -1) return // Tapped empty space

        isTransitioning = true
        val isCorrect = (hitRingIndex == trial.brokenIndex)
        val now = System.currentTimeMillis()
        val latency = now - roundSpawnTimestamp

        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        interactions.add(
            GameInteractionRecord(
                trialIndex = currentRound,
                latencyMs = latency,
                isCorrect = isCorrect,
                touchDistancePx = hitDist,
                stimulusKey = "POPOUT_ROUND_$currentRound"
            )
        )

        val hits = interactions.count { it.isCorrect }
        onProgress(hits, trials.size)

        scope.launch {
            delay(120L)
            if (currentRound + 1 < trials.size) {
                currentRound++
                roundSpawnTimestamp = System.currentTimeMillis()
                isTransitioning = false
            } else {
                onComplete(interactions.toList())
            }
        }
    }

    // 1400ms timeout per trial
    LaunchedEffect(currentRound) {
        roundSpawnTimestamp = System.currentTimeMillis()
        delay(1400L)
        if (!isTransitioning && currentRound < trials.size) {
            interactions.add(
                GameInteractionRecord(
                    trialIndex = currentRound,
                    latencyMs = 1400L,
                    isCorrect = false,
                    stimulusKey = "POPOUT_TIMEOUT"
                )
            )
            val hits = interactions.count { it.isCorrect }
            onProgress(hits, trials.size)

            if (currentRound + 1 < trials.size) {
                currentRound++
            } else {
                onComplete(interactions.toList())
            }
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "TARGET ${currentRound + 1} OF ${trials.size}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = TsodingOrbRenderer.CyanPulse
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "TAP THE BROKEN RING (WITH NOTCH)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = Color.White
                )
            }

            // Interactive Search Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(currentRound) {
                        detectTapGestures { tapOffset ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            processTap(tapOffset, w, h)
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val ringRadius = with(density) { 26.dp.toPx() }
                    val strokeW = 4.dp.toPx()

                    if (currentRound < trials.size) {
                        val trial = trials[currentRound]

                        for (ring in trial.rings) {
                            val center = Offset(ring.relX * w, ring.relY * h)

                            // Soft radial ambient glow
                            drawCircle(
                                brush = Brush.radialGradient(
                                    listOf(Color(0x1F00F5A0), Color.Transparent),
                                    center = center,
                                    radius = ringRadius * 1.8f
                                ),
                                radius = ringRadius * 1.8f,
                                center = center
                            )

                            if (ring.isBroken) {
                                // Broken Ring (Landolt-C): Arc of 300° leaving a 60° notch
                                val arcSize = Size(ringRadius * 2f, ringRadius * 2f)
                                val arcTopLeft = Offset(center.x - ringRadius, center.y - ringRadius)
                                drawArc(
                                    color = TsodingOrbRenderer.EmeraldGreen,
                                    startAngle = ring.gapStartAngle + 60f,
                                    sweepAngle = 300f,
                                    useCenter = false,
                                    topLeft = arcTopLeft,
                                    size = arcSize,
                                    style = Stroke(width = strokeW, cap = StrokeCap.Round)
                                )
                                // Subtle center hint
                                drawCircle(
                                    color = Color(0x3300F5A0),
                                    radius = 4.dp.toPx(),
                                    center = center
                                )
                            } else {
                                // Solid Closed Ring
                                drawCircle(
                                    color = TsodingOrbRenderer.EmeraldGreen,
                                    radius = ringRadius,
                                    center = center,
                                    style = Stroke(width = strokeW)
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Status
            Text(
                text = "FEATURE POP-OUT VERIFICATION",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = Color(0x66FFFFFF),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}
