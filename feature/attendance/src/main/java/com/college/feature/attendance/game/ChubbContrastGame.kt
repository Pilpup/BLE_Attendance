package com.college.feature.attendance.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

/**
 * Game #5: Chubb Contrast Anomaly
 * Direct implementation of the Chubb & Sperling lateral contrast illusion.
 * Two identical gray target dots (#888888) appear inside differing context surround textures.
 * Rule: Swipe or tap towards the dot that appears SHARPER / BRIGHTER.
 *
 * Lateral inhibition operates pre-attentively in foveal vision (<400ms).
 * In peripheral vision, spatial averaging collapses the illusion into a flat, ambiguous wash.
 */
@Composable
fun ChubbContrastGame(
    seedKey: String,
    onProgress: (hitCount: Int, totalTrials: Int) -> Unit,
    onComplete: (interactions: List<GameInteractionRecord>) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    data class ContrastTrial(val brighterOnLeft: Boolean)
    val trials = remember(seedKey) {
        val seed = seedKey.hashCode().toLong().absoluteValue
        val rng = Random(seed)
        val list = mutableListOf<ContrastTrial>()
        var lastLeft = rng.nextBoolean()
        for (i in 0 until 6) {
            val left = if (i % 2 == 1) !lastLeft else rng.nextBoolean()
            lastLeft = left
            list.add(ContrastTrial(brighterOnLeft = left))
        }
        list
    }

    var currentRound by remember { mutableIntStateOf(0) }
    var roundSpawnTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val interactions = remember { mutableStateListOf<GameInteractionRecord>() }
    var isTransitioning by remember { mutableStateOf(false) }

    fun processSelection(selectedLeft: Boolean) {
        if (isTransitioning || currentRound >= trials.size) return
        isTransitioning = true

        val trial = trials[currentRound]
        val now = System.currentTimeMillis()
        val latency = now - roundSpawnTimestamp
        val isCorrect = (selectedLeft == trial.brighterOnLeft)

        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        interactions.add(
            GameInteractionRecord(
                trialIndex = currentRound,
                latencyMs = latency,
                isCorrect = isCorrect,
                stimulusKey = "CHUBB_BRIGHT_${if (trial.brighterOnLeft) "LEFT" else "RIGHT"}"
            )
        )

        val hits = interactions.count { it.isCorrect }
        onProgress(hits, trials.size)

        scope.launch {
            delay(150L)
            if (currentRound + 1 < trials.size) {
                currentRound++
                roundSpawnTimestamp = System.currentTimeMillis()
                isTransitioning = false
            } else {
                onComplete(interactions.toList())
            }
        }
    }

    // 1600ms timeout fallback
    LaunchedEffect(currentRound) {
        roundSpawnTimestamp = System.currentTimeMillis()
        delay(1600L)
        if (!isTransitioning && currentRound < trials.size) {
            interactions.add(
                GameInteractionRecord(
                    trialIndex = currentRound,
                    latencyMs = 1600L,
                    isCorrect = false,
                    stimulusKey = "CHUBB_TIMEOUT"
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
                    listOf(Color(0xFF040A07), Color(0xFF020604), Color(0xFF06140D))
                )
            )
            .pointerInput(currentRound) {
                var totalDragX = 0f
                detectDragGestures(
                    onDragStart = { totalDragX = 0f },
                    onDrag = { _, dragAmount -> totalDragX += dragAmount.x },
                    onDragEnd = {
                        if (totalDragX < -60f) {
                            processSelection(selectedLeft = true)
                        } else if (totalDragX > 60f) {
                            processSelection(selectedLeft = false)
                        }
                    }
                )
            }
            .pointerInput(currentRound) {
                detectTapGestures { tapOffset ->
                    val w = size.width.toFloat()
                    if (tapOffset.x < w / 2f) {
                        processSelection(selectedLeft = true)
                    } else {
                        processSelection(selectedLeft = false)
                    }
                }
            }
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header HUD
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "TRIAL ${currentRound + 1} OF ${trials.size}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = TsodingOrbRenderer.CyanPulse
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "SWIPE TOWARDS THE SHARPER / BRIGHTER DOT",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = Color.White
                )
            }

            // Dual Contrast Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val cardW = w * 0.42f
                    val cardH = cardW * 1.3f
                    val cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx())

                    val leftCardTopLeft = Offset(w * 0.05f, (h - cardH) / 2f)
                    val rightCardTopLeft = Offset(w * 0.53f, (h - cardH) / 2f)

                    val leftCenter = Offset(leftCardTopLeft.x + cardW / 2f, leftCardTopLeft.y + cardH / 2f)
                    val rightCenter = Offset(rightCardTopLeft.x + cardW / 2f, rightCardTopLeft.y + cardH / 2f)

                    if (currentRound < trials.size) {
                        val trial = trials[currentRound]
                        // The brighter illusion dot is on low-contrast context; faded dot is on high-contrast context
                        val leftIsBrighter = trial.brighterOnLeft

                        // 1. Render Left Card Context
                        drawRoundRect(
                            color = Color(0xFF101C16),
                            topLeft = leftCardTopLeft,
                            size = Size(cardW, cardH),
                            cornerRadius = cornerRadius
                        )

                        // 2. Render Right Card Context
                        drawRoundRect(
                            color = Color(0xFF101C16),
                            topLeft = rightCardTopLeft,
                            size = Size(cardW, cardH),
                            cornerRadius = cornerRadius
                        )

                        // Draw High-Contrast Striping Texture on the side that suppresses contrast
                        val highContrastTopLeft = if (leftIsBrighter) rightCardTopLeft else leftCardTopLeft
                        val stripeCount = 12
                        val stripeH = cardH / stripeCount
                        for (s in 0 until stripeCount step 2) {
                            drawRect(
                                color = Color(0xFF40584C),
                                topLeft = Offset(highContrastTopLeft.x, highContrastTopLeft.y + s * stripeH),
                                size = Size(cardW, stripeH)
                            )
                        }

                        // Draw Low-Contrast Soft Gradient on the illusory brighter side
                        val lowContrastCenter = if (leftIsBrighter) leftCenter else rightCenter
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(Color(0xFF182A22), Color(0xFF0F1A14)),
                                center = lowContrastCenter,
                                radius = cardW * 0.5f
                            ),
                            radius = cardW * 0.48f,
                            center = lowContrastCenter
                        )

                        // Outer card borders
                        drawRoundRect(
                            color = Color(0x3300F5A0),
                            topLeft = leftCardTopLeft,
                            size = Size(cardW, cardH),
                            cornerRadius = cornerRadius,
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                        drawRoundRect(
                            color = Color(0x3300F5A0),
                            topLeft = rightCardTopLeft,
                            size = Size(cardW, cardH),
                            cornerRadius = cornerRadius,
                            style = Stroke(width = 1.5.dp.toPx())
                        )

                        // 3. Render Center Target Dots: EXACT SAME PHYSICAL RGB COLOR (#888888)
                        val targetDotRadius = with(density) { 22.dp.toPx() }
                        val identicalTargetColor = Color(0xFF888888)

                        // Left Dot
                        drawCircle(
                            color = identicalTargetColor,
                            radius = targetDotRadius,
                            center = leftCenter
                        )
                        drawCircle(
                            color = Color(0x22FFFFFF),
                            radius = targetDotRadius,
                            center = leftCenter,
                            style = Stroke(width = 1.5.dp.toPx())
                        )

                        // Right Dot
                        drawCircle(
                            color = identicalTargetColor,
                            radius = targetDotRadius,
                            center = rightCenter
                        )
                        drawCircle(
                            color = Color(0x22FFFFFF),
                            radius = targetDotRadius,
                            center = rightCenter,
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }
            }

            // Bottom Direction Guides
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "◀ LEFT",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = TsodingOrbRenderer.EmeraldGreen
                )
                Text(
                    text = "RIGHT ▶",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = TsodingOrbRenderer.CyanPulse
                )
            }
        }
    }
}
