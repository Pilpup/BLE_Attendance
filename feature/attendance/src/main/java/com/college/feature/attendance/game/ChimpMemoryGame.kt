package com.college.feature.attendance.game

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.graphics.nativeCanvas
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
 * Game #1: Chimp Memory Sequence (~10 Seconds Session)
 * Round 1: 3 numbers (1, 2, 3) on 3x3 grid
 * Round 2: 4 numbers (1, 2, 3, 4) on 3x3 grid
 * Total targets: 7. Saccadic eye shifts between phones wipe out the iconic visual buffer.
 */
@Composable
fun ChimpMemoryGame(
    seedKey: String,
    onProgress: (hitCount: Int, totalTrials: Int) -> Unit,
    onComplete: (interactions: List<GameInteractionRecord>) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 2 successive rounds: Round 1 has 3 numbers, Round 2 has 4 numbers (Total 7)
    val roundConfigs = remember(seedKey) {
        val seed = seedKey.hashCode().toLong().absoluteValue
        val rng = Random(seed)

        fun pickCells(count: Int): List<Int> {
            val pool = (0 until 9).toMutableList()
            val res = mutableListOf<Int>()
            for (i in 0 until count) {
                res.add(pool.removeAt(rng.nextInt(pool.size)))
            }
            return res
        }

        listOf(pickCells(3), pickCells(4))
    }

    val totalTargets = 7 // 3 + 4
    var currentRoundIndex by remember { mutableIntStateOf(0) } // 0 or 1
    val targetCells = roundConfigs[currentRoundIndex]
    val roundTargetCount = targetCells.size

    // Phases: 0 = Memorize, 1 = Tap Blank Tiles, 2 = Complete
    var gamePhase by remember { mutableIntStateOf(0) }
    var nextExpectedNumber by remember { mutableIntStateOf(1) } // 1..roundTargetCount
    val tappedCells = remember { mutableStateListOf<Int>() }
    val cellErrorStates = remember { mutableStateListOf<Int>() }
    val interactions = remember { mutableStateListOf<GameInteractionRecord>() }

    var recallStartTimestamp by remember { mutableLongStateOf(0L) }
    var lastTapTimestamp by remember { mutableLongStateOf(0L) }

    // Pre-allocated Android Paints
    val numberPaint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            color = android.graphics.Color.WHITE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
    }
    val blankTileTextPaint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            color = android.graphics.Color.argb(120, 255, 255, 255)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    // Memorization countdown per round
    LaunchedEffect(currentRoundIndex) {
        gamePhase = 0
        tappedCells.clear()
        cellErrorStates.clear()
        nextExpectedNumber = 1
        val memorizeDuration = if (currentRoundIndex == 0) 900L else 1100L
        delay(memorizeDuration)
        recallStartTimestamp = System.currentTimeMillis()
        lastTapTimestamp = recallStartTimestamp
        gamePhase = 1 // Switch to recall phase
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF040A07), Color(0xFF020604), Color(0xFF06140D))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header instruction
            Text(
                text = "ROUND ${currentRoundIndex + 1} OF 2",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = TsodingOrbRenderer.CyanPulse
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = when (gamePhase) {
                    0 -> "MEMORIZE 1 ➔ $roundTargetCount"
                    1 -> "TAP BLANK TILES: $nextExpectedNumber OF $roundTargetCount"
                    else -> "COMPLETE"
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace,
                color = when (gamePhase) {
                    0 -> TsodingOrbRenderer.SolarOrange
                    1 -> TsodingOrbRenderer.EmeraldGreen
                    else -> Color.White
                }
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 3x3 Canvas Grid
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(gamePhase, currentRoundIndex) {
                        if (gamePhase != 1) return@pointerInput

                        detectTapGestures { tapOffset ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val cols = 3
                            val rows = 3
                            val paddingPx = with(density) { 12.dp.toPx() }
                            val cellW = (w - (paddingPx * (cols + 1))) / cols
                            val cellH = (h - (paddingPx * (rows + 1))) / rows

                            // Identify tapped cell
                            var tappedIndex = -1
                            for (r in 0 until rows) {
                                for (c in 0 until cols) {
                                    val left = paddingPx + c * (cellW + paddingPx)
                                    val top = paddingPx + r * (cellH + paddingPx)
                                    val right = left + cellW
                                    val bottom = top + cellH

                                    if (tapOffset.x in left..right && tapOffset.y in top..bottom) {
                                        tappedIndex = r * cols + c
                                        break
                                    }
                                }
                                if (tappedIndex != -1) break
                            }

                            if (tappedIndex == -1 || tappedIndex in tappedCells) return@detectTapGestures

                            val now = System.currentTimeMillis()
                            val latency = now - recallStartTimestamp
                            val hesitation = now - lastTapTimestamp
                            lastTapTimestamp = now

                            val expectedCell = targetCells[nextExpectedNumber - 1]
                            val isCorrect = (tappedIndex == expectedCell)

                            if (isCorrect) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                tappedCells.add(tappedIndex)
                                interactions.add(
                                    GameInteractionRecord(
                                        trialIndex = interactions.size,
                                        latencyMs = latency,
                                        isCorrect = true,
                                        hesitationMs = hesitation,
                                        stimulusKey = "CHIMP_R${currentRoundIndex + 1}_NUM_$nextExpectedNumber"
                                    )
                                )
                                val totalHits = interactions.count { it.isCorrect }
                                onProgress(totalHits, totalTargets)

                                if (nextExpectedNumber == roundTargetCount) {
                                    // Round passed!
                                    if (currentRoundIndex + 1 < roundConfigs.size) {
                                        scope.launch {
                                            delay(250L)
                                            currentRoundIndex++
                                        }
                                    } else {
                                        gamePhase = 2
                                        onComplete(interactions.toList())
                                    }
                                } else {
                                    nextExpectedNumber++
                                }
                            } else {
                                // Wrong cell tapped
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                cellErrorStates.add(tappedIndex)
                                interactions.add(
                                    GameInteractionRecord(
                                        trialIndex = interactions.size,
                                        latencyMs = latency,
                                        isCorrect = false,
                                        hesitationMs = hesitation,
                                        stimulusKey = "CHIMP_ERR_R${currentRoundIndex + 1}_NUM_$nextExpectedNumber"
                                    )
                                )
                                // If error in round 1, advance to round 2 to keep full ~10s test running
                                if (currentRoundIndex + 1 < roundConfigs.size) {
                                    scope.launch {
                                        delay(350L)
                                        currentRoundIndex++
                                    }
                                } else {
                                    gamePhase = 2
                                    onComplete(interactions.toList())
                                }
                            }
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val cols = 3
                    val rows = 3
                    val paddingPx = 12.dp.toPx()
                    val cellW = (w - (paddingPx * (cols + 1))) / cols
                    val cellH = (h - (paddingPx * (rows + 1))) / rows
                    val cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx())

                    numberPaint.textSize = 34.sp.toPx()
                    blankTileTextPaint.textSize = 28.sp.toPx()

                    for (r in 0 until rows) {
                        for (c in 0 until cols) {
                            val cellIndex = r * cols + c
                            val left = paddingPx + c * (cellW + paddingPx)
                            val top = paddingPx + r * (cellH + paddingPx)
                            val topLeft = Offset(left, top)
                            val cellSize = Size(cellW, cellH)
                            val center = Offset(left + cellW / 2f, top + cellH / 2f)

                            val isTarget = cellIndex in targetCells
                            val numberVal = if (isTarget) targetCells.indexOf(cellIndex) + 1 else 0
                            val isTapped = cellIndex in tappedCells
                            val isError = cellIndex in cellErrorStates

                            when {
                                isError -> {
                                    drawRoundRect(
                                        color = Color(0x33FF1744),
                                        topLeft = topLeft,
                                        size = cellSize,
                                        cornerRadius = cornerRadius
                                    )
                                    drawRoundRect(
                                        color = TsodingOrbRenderer.CrimsonAlert,
                                        topLeft = topLeft,
                                        size = cellSize,
                                        cornerRadius = cornerRadius,
                                        style = Stroke(width = 3.dp.toPx())
                                    )
                                }

                                isTapped -> {
                                    drawRoundRect(
                                        color = Color(0x3300F5A0),
                                        topLeft = topLeft,
                                        size = cellSize,
                                        cornerRadius = cornerRadius
                                    )
                                    drawRoundRect(
                                        color = TsodingOrbRenderer.EmeraldGreen,
                                        topLeft = topLeft,
                                        size = cellSize,
                                        cornerRadius = cornerRadius,
                                        style = Stroke(width = 2.5.dp.toPx())
                                    )
                                    drawContext.canvas.nativeCanvas.drawText(
                                        "$numberVal",
                                        center.x,
                                        center.y + (numberPaint.textSize * 0.35f),
                                        numberPaint
                                    )
                                }

                                gamePhase == 0 && isTarget -> {
                                    TsodingOrbRenderer.drawGlowingOrb(
                                        drawScope = this,
                                        center = center,
                                        radius = cellW * 0.42f,
                                        baseColor = TsodingOrbRenderer.CyanPulse,
                                        pulseAlpha = 0.85f
                                    )
                                    drawRoundRect(
                                        color = Color(0x2200E5FF),
                                        topLeft = topLeft,
                                        size = cellSize,
                                        cornerRadius = cornerRadius
                                    )
                                    drawRoundRect(
                                        color = TsodingOrbRenderer.CyanPulse,
                                        topLeft = topLeft,
                                        size = cellSize,
                                        cornerRadius = cornerRadius,
                                        style = Stroke(width = 2.dp.toPx())
                                    )
                                    drawContext.canvas.nativeCanvas.drawText(
                                        "$numberVal",
                                        center.x,
                                        center.y + (numberPaint.textSize * 0.35f),
                                        numberPaint
                                    )
                                }

                                gamePhase == 1 && isTarget -> {
                                    drawRoundRect(
                                        color = Color(0x22FFFFFF),
                                        topLeft = topLeft,
                                        size = cellSize,
                                        cornerRadius = cornerRadius
                                    )
                                    drawRoundRect(
                                        color = Color(0x66FFFFFF),
                                        topLeft = topLeft,
                                        size = cellSize,
                                        cornerRadius = cornerRadius,
                                        style = Stroke(width = 2.dp.toPx())
                                    )
                                    drawContext.canvas.nativeCanvas.drawText(
                                        "?",
                                        center.x,
                                        center.y + (blankTileTextPaint.textSize * 0.35f),
                                        blankTileTextPaint
                                    )
                                }

                                else -> {
                                    drawRoundRect(
                                        color = Color(0x0CFFFFFF),
                                        topLeft = topLeft,
                                        size = cellSize,
                                        cornerRadius = cornerRadius
                                    )
                                    drawRoundRect(
                                        color = Color(0x15FFFFFF),
                                        topLeft = topLeft,
                                        size = cellSize,
                                        cornerRadius = cornerRadius,
                                        style = Stroke(width = 1.dp.toPx())
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
