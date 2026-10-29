package com.college.feature.attendance.game

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import java.util.Random
import kotlin.math.absoluteValue

/**
 * Game #1: Chimp Memory Sequence
 * Displays 3 numbers (1, 2, 3) on a 3x3 grid for 900ms.
 * Numbers then turn into blank frosted tiles.
 * Student must tap tiles in order: 1 ➔ 2 ➔ 3.
 *
 * Saccadic gaze shift between phones instantly wipes out the iconic visual buffer.
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

    // Deterministically pick 3 distinct cells on 3x3 grid using seedKey
    val targetCells = remember(seedKey) {
        val seed = seedKey.hashCode().toLong().absoluteValue
        val rng = Random(seed)
        val cells = (0 until 9).toMutableList()
        val picked = mutableListOf<Int>()
        for (i in 0 until 3) {
            val idx = rng.nextInt(cells.size)
            picked.add(cells.removeAt(idx))
        }
        picked // List of 3 cell indices (0..8) representing 1, 2, 3
    }

    // Game Phases: 0 = Memorize, 1 = Tap Blank Tiles, 2 = Complete
    var gamePhase by remember { mutableIntStateOf(0) }
    var nextExpectedNumber by remember { mutableIntStateOf(1) } // 1, 2, 3
    val tappedCells = remember { mutableStateListOf<Int>() }
    val cellErrorStates = remember { mutableStateListOf<Int>() } // cells that failed
    val interactions = remember { mutableStateListOf<GameInteractionRecord>() }

    var recallStartTimestamp by remember { mutableLongStateOf(0L) }
    var lastTapTimestamp by remember { mutableLongStateOf(0L) }

    // Text paints pre-allocated to avoid allocation in draw loop
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

    // Phase transition controller
    LaunchedEffect(Unit) {
        // Step 1: Memorization phase for 900ms
        delay(900L)
        recallStartTimestamp = System.currentTimeMillis()
        lastTapTimestamp = recallStartTimestamp
        gamePhase = 1 // Switch to blank tiles
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
                text = when (gamePhase) {
                    0 -> "MEMORIZE 1 ➔ 2 ➔ 3"
                    1 -> "TAP BLANK TILES: $nextExpectedNumber OF 3"
                    else -> "COMPLETE"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace,
                color = when (gamePhase) {
                    0 -> TsodingOrbRenderer.CyanPulse
                    1 -> TsodingOrbRenderer.EmeraldGreen
                    else -> Color.White
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 3x3 Canvas Grid
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(gamePhase) {
                        if (gamePhase != 1) return@pointerInput

                        detectTapGestures { tapOffset ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val cols = 3
                            val rows = 3
                            val paddingPx = with(density) { 12.dp.toPx() }
                            val cellW = (w - (paddingPx * (cols + 1))) / cols
                            val cellH = (h - (paddingPx * (rows + 1))) / rows

                            // Determine which cell was tapped
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
                                        trialIndex = nextExpectedNumber - 1,
                                        latencyMs = latency,
                                        isCorrect = true,
                                        hesitationMs = hesitation,
                                        stimulusKey = "CHIMP_NUM_$nextExpectedNumber"
                                    )
                                )
                                onProgress(nextExpectedNumber, 3)

                                if (nextExpectedNumber == 3) {
                                    gamePhase = 2
                                    onComplete(interactions.toList())
                                } else {
                                    nextExpectedNumber++
                                }
                            } else {
                                // Wrong cell tapped!
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                cellErrorStates.add(tappedIndex)
                                interactions.add(
                                    GameInteractionRecord(
                                        trialIndex = nextExpectedNumber - 1,
                                        latencyMs = latency,
                                        isCorrect = false,
                                        hesitationMs = hesitation,
                                        stimulusKey = "CHIMP_ERR_NUM_$nextExpectedNumber"
                                    )
                                )
                                // Complete game with failure to prevent endless loop
                                gamePhase = 2
                                onComplete(interactions.toList())
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
                                    // Error flash red
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
                                    // Solved cell - solid glowing emerald
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
                                    // Memorize phase - show vibrant glowing numbers
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
                                    // Recall phase - blank frosted tile with subtle border
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
                                    // Empty background cell
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
