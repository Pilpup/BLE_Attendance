package com.college.feature.attendance.rhythm

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * osu!-Style Rhythm & Slider Verification Interactive Canvas.
 * Combines shrinking approach rings, numbered combos, and continuous slider path tracking
 * to capture high-resolution visual-motor phase lock and continuous attention telemetry.
 *
 * Enforces strict visual saccade traps (tight ±220ms hit windows, procedural unsynchronized
 * beatmap per student, and continuous bimanual motor tracking).
 */
@Composable
fun OsuRhythmChallenge(
    modifier: Modifier = Modifier,
    seedKey: String = "",
    onProgressUpdate: (hitCount: Int, totalObjects: Int) -> Unit = { _, _ -> },
    onComplete: (interactions: List<RhythmInteractionRecord>) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    // Generate or fetch device-unique procedural beatmap
    val objects = remember(seedKey) { AttendanceBeatmap.getForSeed(seedKey) }
    val totalObjects = objects.size
    val totalDurationMs = remember(objects) {
        (objects.maxOfOrNull { it.sliderEndTimeMs(0L) } ?: 13500L) + 400L
    }

    // Session State (initialized immediately)
    val gameStartMs = remember { System.currentTimeMillis() }
    var currentClockMs by remember { mutableLongStateOf(gameStartMs) }
    var comboCounter by remember { mutableIntStateOf(0) }
    var totalScore by remember { mutableIntStateOf(0) }
    var successfulHitsCount by remember { mutableIntStateOf(0) }
    var completedObjectCount by remember { mutableIntStateOf(0) }

    // Screen Dimensions for frame-loop calculations
    var currentWidthPx by remember { mutableStateOf(1080f) }
    var currentHeightPx by remember { mutableStateOf(1920f) }

    // Active Tracking Structures
    val hitObjectStates = remember { mutableStateMapOf<Int, Boolean>() } // id -> isCompleted
    val activeSliders = remember { mutableStateListOf<Int>() }           // id of sliders currently in progress
    val sliderFrameStats = remember { mutableStateMapOf<Int, Pair<Int, Int>>() } // id -> (trackedFrames, totalFrames)
    val sliderHeadTimings = remember { mutableStateMapOf<Int, Long>() }
    val sliderHeadRadialOffsets = remember { mutableStateMapOf<Int, Float>() }
    val interactions = remember { mutableStateListOf<RhythmInteractionRecord>() }
    val floatingBursts = remember { mutableStateListOf<JudgementBurst>() }
    val activePointers = remember { mutableStateMapOf<Long, Offset>() }

    // Ambient Pulse Animation
    val pulseTransition = rememberInfiniteTransition(label = "osu_pulse")
    val pulsePhase by pulseTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    // Android Paints for High-Speed Direct Text Drawing on Canvas
    val numberTextPaint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            color = android.graphics.Color.WHITE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
    }
    val hudTextPaint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
            color = android.graphics.Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }
    val burstPaint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
    }

    // Tap Handling Function with Visual Saccade Trap Windows
    fun processTap(touchPos: Offset, w: Float, h: Float) {
        val now = System.currentTimeMillis()
        val hitRadius = with(density) { 62.dp.toPx() }

        // Find active circles or slider heads on screen that have not been completed yet
        val eligible = objects.filter { obj ->
            hitObjectStates[obj.id] != true &&
            obj.id !in activeSliders &&
            now >= (obj.spawnOffsetMs + gameStartMs) &&
            now <= (obj.targetHitTimeMs(gameStartMs) + 220L)
        }.sortedBy { it.targetHitTimeMs(gameStartMs) }

        for (obj in eligible) {
            val targetPos = Offset(obj.relX * w, obj.relY * h)
            val dx = touchPos.x - targetPos.x
            val dy = touchPos.y - targetPos.y
            val dist = sqrt(dx * dx + dy * dy)

            if (dist <= hitRadius) {
                val deltaTiming = now - obj.targetHitTimeMs(gameStartMs)

                // If tapped more than 220ms early, the approach circle is still too far out
                // Ignore the tap so the player must synchronize with the approach circle
                if (deltaTiming < -220L) {
                    continue
                }

                val absDelta = abs(deltaTiming)
                val judgement = when {
                    absDelta <= 80L -> JudgementType.PERFECT   // 300
                    absDelta <= 150L -> JudgementType.GREAT    // 100
                    absDelta <= 220L -> JudgementType.GOOD     // 50
                    else -> JudgementType.MISS
                }

                val hitInOrder = (obj.comboNumber == 1) ||
                        objects.any { it.comboNumber == obj.comboNumber - 1 && hitObjectStates[it.id] == true }

                comboCounter++
                totalScore += judgement.score * comboCounter.coerceAtMost(8)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                if (obj.type == RhythmObjectType.CIRCLE) {
                    hitObjectStates[obj.id] = true
                    successfulHitsCount++
                    completedObjectCount++

                    interactions.add(
                        RhythmInteractionRecord(
                            objectId = obj.id,
                            isSlider = false,
                            comboNumber = obj.comboNumber,
                            timingErrorMs = deltaTiming,
                            judgement = judgement,
                            touchDistancePx = dist,
                            normalizedRadialOffset = (dist / hitRadius).coerceIn(0f, 1f),
                            hitInOrder = hitInOrder,
                            sliderTrackingRatio = 1.0f,
                            timestampMs = now
                        )
                    )
                    onProgressUpdate(successfulHitsCount, totalObjects)
                } else {
                    // Slider head tapped! Activate slider tracking
                    activeSliders.add(obj.id)
                    sliderHeadTimings[obj.id] = deltaTiming
                    sliderHeadRadialOffsets[obj.id] = (dist / hitRadius).coerceIn(0f, 1f)
                }

                floatingBursts.add(
                    JudgementBurst(
                        id = now,
                        text = judgement.label,
                        colorLong = judgement.colorLong,
                        position = Offset(obj.relX, obj.relY),
                        spawnTimeMs = now
                    )
                )
                break // Each tap consumes at most one circle
            }
        }
    }

    // 60 FPS Frame Loop (State Transitions, Sliders, Expirations)
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            currentClockMs = now
            val elapsed = now - gameStartMs

            // 1. Check for expired objects that player missed (Strict ±220ms cutoff)
            for (obj in objects) {
                if (hitObjectStates[obj.id] != true && obj.id !in activeSliders) {
                    val targetTime = obj.targetHitTimeMs(gameStartMs)
                    if (now > targetTime + 220L) {
                        hitObjectStates[obj.id] = true
                        completedObjectCount++
                        comboCounter = 0 // Combo breaks on miss

                        interactions.add(
                            RhythmInteractionRecord(
                                objectId = obj.id,
                                isSlider = obj.type == RhythmObjectType.SLIDER,
                                comboNumber = obj.comboNumber,
                                timingErrorMs = 220L,
                                judgement = JudgementType.MISS,
                                touchDistancePx = 999f,
                                normalizedRadialOffset = 1.0f,
                                hitInOrder = false,
                                sliderTrackingRatio = 0f,
                                timestampMs = now
                            )
                        )

                        floatingBursts.add(
                            JudgementBurst(
                                id = now,
                                text = "MISS",
                                colorLong = JudgementType.MISS.colorLong,
                                position = Offset(obj.relX, obj.relY),
                                spawnTimeMs = now
                            )
                        )
                        onProgressUpdate(successfulHitsCount, totalObjects)
                    }
                }
            }

            // 2. Active Sliders Tracking (Continuous pursuit motor control)
            val rollingSliders = objects.filter {
                it.type == RhythmObjectType.SLIDER &&
                hitObjectStates[it.id] != true &&
                (it.id in activeSliders || now >= it.targetHitTimeMs(gameStartMs))
            }

            for (slider in rollingSliders) {
                if (slider.id !in activeSliders) {
                    activeSliders.add(slider.id)
                }

                val targetTime = slider.targetHitTimeMs(gameStartMs)
                val progress = ((now - targetTime).toFloat() / slider.durationMs.toFloat()).coerceIn(0f, 1f)
                val ballPos = slider.getBezierPoint(progress, currentWidthPx, currentHeightPx)
                val trackingRadius = with(density) { 56.dp.toPx() } // Calibrated to detect divided attention drift

                val isFingerOnBall = activePointers.values.any { touch ->
                    val dx = touch.x - ballPos.x
                    val dy = touch.y - ballPos.y
                    sqrt(dx * dx + dy * dy) <= trackingRadius
                }

                val stats = sliderFrameStats[slider.id] ?: Pair(0, 0)
                val newTracked = if (isFingerOnBall) stats.first + 1 else stats.first
                val newTotal = stats.second + 1
                sliderFrameStats[slider.id] = Pair(newTracked, newTotal)

                // Slider reached the end (guaranteed completion)
                if (progress >= 1.0f || now >= slider.sliderEndTimeMs(gameStartMs)) {
                    hitObjectStates[slider.id] = true
                    activeSliders.remove(slider.id)
                    completedObjectCount++
                    val ratio = (newTracked.toFloat() / newTotal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)

                    val finalJudgement = when {
                        ratio >= 0.70f -> JudgementType.PERFECT
                        ratio >= 0.50f -> JudgementType.GREAT
                        ratio >= 0.30f -> JudgementType.GOOD
                        else -> JudgementType.MISS
                    }

                    if (finalJudgement != JudgementType.MISS) {
                        successfulHitsCount++
                        comboCounter++
                        totalScore += finalJudgement.score * comboCounter.coerceAtMost(8)
                    } else {
                        comboCounter = 0
                    }

                    val headTiming = sliderHeadTimings[slider.id] ?: 120L
                    val headOffset = sliderHeadRadialOffsets[slider.id] ?: 0.25f

                    interactions.add(
                        RhythmInteractionRecord(
                            objectId = slider.id,
                            isSlider = true,
                            comboNumber = slider.comboNumber,
                            timingErrorMs = headTiming,
                            judgement = finalJudgement,
                            touchDistancePx = 0f,
                            normalizedRadialOffset = headOffset,
                            hitInOrder = true,
                            sliderTrackingRatio = ratio,
                            timestampMs = now
                        )
                    )

                    floatingBursts.add(
                        JudgementBurst(
                            id = now,
                            text = finalJudgement.label,
                            colorLong = finalJudgement.colorLong,
                            position = Offset(slider.endRelX, slider.endRelY),
                            spawnTimeMs = now
                        )
                    )
                    onProgressUpdate(successfulHitsCount, totalObjects)
                }
            }

            // 3. Prune expired floating bursts
            floatingBursts.removeAll { now - it.spawnTimeMs > it.durationMs }

            // 4. Session Completion Check
            if (completedObjectCount >= totalObjects || elapsed >= totalDurationMs) {
                // Ensure all objects are finalized in interactions list
                for (obj in objects) {
                    if (hitObjectStates[obj.id] != true) {
                        hitObjectStates[obj.id] = true
                        interactions.add(
                            RhythmInteractionRecord(
                                objectId = obj.id,
                                isSlider = obj.type == RhythmObjectType.SLIDER,
                                comboNumber = obj.comboNumber,
                                timingErrorMs = 220L,
                                judgement = JudgementType.MISS,
                                touchDistancePx = 999f,
                                normalizedRadialOffset = 1.0f,
                                hitInOrder = false,
                                sliderTrackingRatio = 0f,
                                timestampMs = now
                            )
                        )
                    }
                }
                delay(300L)
                onComplete(interactions.toList())
                break
            }

            delay(16L) // steady 60 FPS
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF040A07),
                        Color(0xFF020604),
                        Color(0xFF07120C)
                    )
                )
            )
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        currentWidthPx = w
                        currentHeightPx = h

                        // Maintain active pointer registry
                        activePointers.clear()
                        for (change in event.changes) {
                            if (change.pressed) {
                                activePointers[change.id.value] = change.position
                            }
                        }

                        // Process initial finger contacts
                        for (change in event.changes) {
                            if (change.changedToDown()) {
                                processTap(change.position, w, h)
                            }
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasW = size.width
            val canvasH = size.height
            val nowMs = currentClockMs
            val circleRadius = 38.dp.toPx()

            // ── LAYER 1: Ambient Forest Glow ──
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0x1800F5A0), Color(0x0600F5A0), Color.Transparent),
                    center = center,
                    radius = canvasW * 0.7f
                ),
                radius = canvasW * 0.7f,
                center = center
            )

            // ── LAYER 2: Combo Connecting Trajectory Lines ──
            val visibleObjects = objects.filter {
                hitObjectStates[it.id] != true &&
                nowMs >= (it.spawnOffsetMs + gameStartMs - 300L) &&
                nowMs <= (it.sliderEndTimeMs(gameStartMs) + 300L)
            }.sortedBy { it.id }

            for (i in 0 until visibleObjects.size - 1) {
                val curr = visibleObjects[i]
                val next = visibleObjects[i + 1]
                if (curr.comboNumber < next.comboNumber) {
                    val p1 = Offset(curr.relX * canvasW, curr.relY * canvasH)
                    val p2 = Offset(next.relX * canvasW, next.relY * canvasH)
                    drawLine(
                        color = Color(curr.colorLong).copy(alpha = 0.40f),
                        start = p1,
                        end = p2,
                        strokeWidth = 2.5.dp.toPx()
                    )
                }
            }

            // ── LAYER 3: Sliders (Tracks, End Circles & Rolling Ball) ──
            val visibleSliders = objects.filter {
                it.type == RhythmObjectType.SLIDER &&
                hitObjectStates[it.id] != true &&
                nowMs >= (it.spawnOffsetMs + gameStartMs) &&
                nowMs <= (it.sliderEndTimeMs(gameStartMs) + 200L)
            }

            for (slider in visibleSliders) {
                val startPt = Offset(slider.relX * canvasW, slider.relY * canvasH)
                val ctrlPt = Offset(slider.controlRelX * canvasW, slider.controlRelY * canvasH)
                val endPt = Offset(slider.endRelX * canvasW, slider.endRelY * canvasH)

                val sliderPath = Path().apply {
                    moveTo(startPt.x, startPt.y)
                    quadraticBezierTo(ctrlPt.x, ctrlPt.y, endPt.x, endPt.y)
                }

                val trackColor = Color(slider.colorLong)
                // A. Slider Body Ribbon
                drawPath(
                    path = sliderPath,
                    color = trackColor.copy(alpha = 0.18f),
                    style = Stroke(width = 46.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                // B. Outer Crisp Neon Border
                drawPath(
                    path = sliderPath,
                    color = trackColor.copy(alpha = 0.85f),
                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // C. Slider End Circle
                drawCircle(
                    color = Color(0xDD0C1A14),
                    radius = circleRadius * 0.85f,
                    center = endPt
                )
                drawCircle(
                    color = trackColor.copy(alpha = 0.85f),
                    radius = circleRadius * 0.85f,
                    center = endPt,
                    style = Stroke(width = 3.dp.toPx())
                )
                drawCircle(
                    color = trackColor,
                    radius = 6.dp.toPx(),
                    center = endPt
                )

                // D. Active Rolling Slider Ball & Follower Ring
                val targetTime = slider.targetHitTimeMs(gameStartMs)
                if (nowMs >= targetTime && nowMs <= slider.sliderEndTimeMs(gameStartMs)) {
                    val progress = ((nowMs - targetTime).toFloat() / slider.durationMs.toFloat()).coerceIn(0f, 1f)
                    val ballPos = slider.getBezierPoint(progress, canvasW, canvasH)

                    val followerRadius = 56.dp.toPx()
                    val isBeingTracked = activePointers.values.any { touch ->
                        val dx = touch.x - ballPos.x
                        val dy = touch.y - ballPos.y
                        sqrt(dx * dx + dy * dy) <= followerRadius
                    }

                    drawCircle(
                        color = if (isBeingTracked) trackColor.copy(alpha = 0.50f) else Color(0x35F59E0B),
                        radius = followerRadius * (if (isBeingTracked) pulsePhase else 0.95f),
                        center = ballPos,
                        style = Stroke(width = 2.5.dp.toPx())
                    )

                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color.White, trackColor, Color.Transparent),
                            center = ballPos,
                            radius = circleRadius * 0.8f
                        ),
                        radius = circleRadius * 0.8f,
                        center = ballPos
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 12.dp.toPx(),
                        center = ballPos
                    )
                }
            }

            // ── LAYER 4: Hit Circles & Shrinking Approach Rings ──
            val activeCircles = objects.filter {
                hitObjectStates[it.id] != true &&
                it.id !in activeSliders &&
                nowMs >= (it.spawnOffsetMs + gameStartMs) &&
                nowMs <= (it.targetHitTimeMs(gameStartMs) + 220L)
            }

            for (obj in activeCircles) {
                val circleCenter = Offset(obj.relX * canvasW, obj.relY * canvasH)
                val targetTime = obj.targetHitTimeMs(gameStartMs)
                val timeToHit = targetTime - nowMs
                val accentColor = Color(obj.colorLong)

                // A. Base Hit Circle Body
                drawCircle(
                    color = Color(0xEE08160F),
                    radius = circleRadius,
                    center = circleCenter
                )
                // B. Crisp Outer White Border (osu! style)
                drawCircle(
                    color = Color.White.copy(alpha = 0.90f),
                    radius = circleRadius,
                    center = circleCenter,
                    style = Stroke(width = 4.dp.toPx())
                )
                // C. Inner Colored Neon Ring
                drawCircle(
                    color = accentColor.copy(alpha = 0.40f),
                    radius = circleRadius * 0.78f,
                    center = circleCenter
                )

                // D. Combo Sequence Number in Center
                drawContext.canvas.nativeCanvas.apply {
                    numberTextPaint.textSize = with(density) { 26.sp.toPx() }
                    drawText(
                        "${obj.comboNumber}",
                        circleCenter.x,
                        circleCenter.y + (numberTextPaint.textSize * 0.35f),
                        numberTextPaint
                    )
                }

                // E. Shrinking Approach Ring (The osu! timing cue)
                if (timeToHit > 0L) {
                    val progress = (timeToHit.toFloat() / obj.approachDurationMs.toFloat()).coerceIn(0f, 1f)
                    val approachScale = 1.0f + (progress * 1.6f) // Shrinks from 2.6x to 1.0x
                    val ringAlpha = (1f - (progress * 0.40f)).coerceIn(0.30f, 0.95f)

                    drawCircle(
                        color = Color.White.copy(alpha = ringAlpha),
                        radius = circleRadius * approachScale,
                        center = circleCenter,
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                }
            }

            // ── LAYER 5: Floating Judgement Popups (300, 100, 50, MISS) ──
            for (burst in floatingBursts) {
                val age = nowMs - burst.spawnTimeMs
                val animProgress = (age.toFloat() / burst.durationMs.toFloat()).coerceIn(0f, 1f)
                val yOffset = animProgress * 36.dp.toPx()
                val alpha = (1f - animProgress).coerceIn(0f, 1f)

                drawContext.canvas.nativeCanvas.apply {
                    burstPaint.color = Color(burst.colorLong).copy(alpha = alpha).toArgb()
                    burstPaint.textSize = with(density) { (22 + (animProgress * 6)).sp.toPx() }
                    drawText(
                        burst.text,
                        burst.position.x * canvasW,
                        (burst.position.y * canvasH) - yOffset,
                        burstPaint
                    )
                }
            }

            // ── LAYER 6: Interactive Cursor Glowing Dot (osu! Cursor) ──
            val activeTouch = activePointers.values.firstOrNull()
            if (activeTouch != null) {
                // Outer glowing cursor halo
                drawCircle(
                    color = Color(0x44FF2A55),
                    radius = 26.dp.toPx(),
                    center = activeTouch
                )
                // Inner bright cursor bead
                drawCircle(
                    color = Color(0xFFFF3366),
                    radius = 12.dp.toPx(),
                    center = activeTouch
                )
                drawCircle(
                    color = Color.White,
                    radius = 4.dp.toPx(),
                    center = activeTouch
                )
            }

            // ── LAYER 7: Top HUD (Combo, Score & Sequence Progress) ──
            drawContext.canvas.nativeCanvas.apply {
                hudTextPaint.textSize = with(density) { 16.sp.toPx() }
                // Combo Counter
                val comboText = if (comboCounter > 1) "COMBO ${comboCounter}x" else ""
                drawText(comboText, 24.dp.toPx(), 36.dp.toPx(), hudTextPaint)

                // Real-time Hits & Progress
                val progressText = "$successfulHitsCount / $totalObjects"
                hudTextPaint.textAlign = Paint.Align.RIGHT
                drawText(progressText, canvasW - 24.dp.toPx(), 36.dp.toPx(), hudTextPaint)
                hudTextPaint.textAlign = Paint.Align.LEFT
            }
        }
    }
}
