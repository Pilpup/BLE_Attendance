package com.college.feature.attendance.rhythm

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sqrt

@Composable
fun OsuRhythmChallenge(
    modifier: Modifier = Modifier,
    onProgressUpdate: (hitCount: Int, totalObjects: Int) -> Unit = { _, _ -> },
    onComplete: (interactions: List<RhythmInteractionRecord>) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    // Session State
    var gameStartMs by remember { mutableLongStateOf(0L) }
    var currentClockMs by remember { mutableLongStateOf(0L) }
    var comboCounter by remember { mutableIntStateOf(0) }
    var totalScore by remember { mutableIntStateOf(0) }
    var completedObjectCount by remember { mutableIntStateOf(0) }

    // Active Objects Tracking
    val hitObjectStates = remember { mutableStateMapOf<Int, Boolean>() } // id -> isHitOrFinished
    val sliderTrackingStats = remember { mutableStateMapOf<Int, Pair<Int, Int>>() } // id -> (trackedFrames, totalFrames)
    val interactions = remember { mutableStateListOf<RhythmInteractionRecord>() }
    val floatingBursts = remember { mutableStateListOf<JudgementBurst>() }
    val activeTouches = remember { mutableStateListOf<Offset>() }

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

    val objects = AttendanceBeatmap.objects
    val totalObjects = objects.size

    // Game Clock Loop
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        gameStartMs = start
        currentClockMs = start

        while (true) {
            val now = System.currentTimeMillis()
            currentClockMs = now
            val elapsed = now - start

            // Check for expired circles that user completely missed
            for (obj in objects) {
                if (hitObjectStates[obj.id] != true) {
                    val targetTime = obj.targetHitTimeMs(start)
                    val expireDeadline = if (obj.type == RhythmObjectType.SLIDER) {
                        obj.sliderEndTimeMs(start) + 150L
                    } else {
                        targetTime + 360L
                    }

                    if (now > expireDeadline) {
                        hitObjectStates[obj.id] = true
                        comboCounter = 0 // Combo reset on miss
                        completedObjectCount++

                        val trackingRatio = if (obj.type == RhythmObjectType.SLIDER) {
                            val stats = sliderTrackingStats[obj.id] ?: Pair(0, 1)
                            (stats.first.toFloat() / stats.second.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                        } else 0f

                        val record = RhythmInteractionRecord(
                            objectId = obj.id,
                            isSlider = obj.type == RhythmObjectType.SLIDER,
                            comboNumber = obj.comboNumber,
                            timingErrorMs = 380L,
                            judgement = JudgementType.MISS,
                            touchDistancePx = 999f,
                            normalizedRadialOffset = 1.0f,
                            hitInOrder = false,
                            sliderTrackingRatio = trackingRatio,
                            timestampMs = now
                        )
                        interactions.add(record)

                        floatingBursts.add(
                            JudgementBurst(
                                id = now,
                                text = "MISS",
                                colorLong = JudgementType.MISS.colorLong,
                                position = Offset(obj.relX, obj.relY),
                                spawnTimeMs = now
                            )
                        )
                        onProgressUpdate(completedObjectCount, totalObjects)
                    }
                }
            }

            // Prune expired floating bursts (> 700ms old)
            floatingBursts.removeAll { now - it.spawnTimeMs > it.durationMs }

            // Check game completion
            if (elapsed >= AttendanceBeatmap.totalDurationMs + 800L || completedObjectCount >= totalObjects) {
                delay(300L)
                onComplete(interactions.toList())
                break
            }

            delay(16L) // ~60 FPS update tick
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF06140E),
                        Color(0xFF030A07),
                        Color(0xFF091710)
                    )
                )
            )
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val nowMs = System.currentTimeMillis()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()

                        // Update current active touch coordinates
                        activeTouches.clear()
                        for (change in event.changes) {
                            if (change.pressed) {
                                activeTouches.add(change.position)
                            }
                        }

                        // 1. Process Down / Press Events on Hit Circles and Sliders
                        if (event.type == PointerEventType.Press) {
                            for (change in event.changes) {
                                if (change.pressed) {
                                    val touchPos = change.position
                                    val touchX = touchPos.x
                                    val touchY = touchPos.y

                                    // Find eligible hit object (active approach window, not yet hit)
                                    val eligible = objects.filter { obj ->
                                        hitObjectStates[obj.id] != true &&
                                        nowMs >= (obj.spawnOffsetMs + gameStartMs) &&
                                        nowMs <= (obj.targetHitTimeMs(gameStartMs) + 360L)
                                    }.sortedBy { it.targetHitTimeMs(gameStartMs) }

                                    for (obj in eligible) {
                                        val objCenterX = obj.relX * w
                                        val objCenterY = obj.relY * h
                                        val dx = touchX - objCenterX
                                        val dy = touchY - objCenterY
                                        val dist = sqrt(dx * dx + dy * dy)
                                        val hitRadius = with(density) { 56.dp.toPx() } // Generous hit area

                                        if (dist <= hitRadius) {
                                            // HIT REGISTERED!
                                            val targetTime = obj.targetHitTimeMs(gameStartMs)
                                            val deltaTiming = nowMs - targetTime
                                            val absDelta = abs(deltaTiming)

                                            // Judgement based on precision of approach ring sync
                                            val judgement = when {
                                                absDelta <= 110L -> JudgementType.PERFECT
                                                absDelta <= 210L -> JudgementType.GREAT
                                                absDelta <= 340L -> JudgementType.GOOD
                                                else -> JudgementType.MISS
                                            }

                                            // Order check: was previous combo number finished?
                                            val hitInOrder = obj.comboNumber == 1 ||
                                                    objects.any { it.comboNumber == obj.comboNumber - 1 && hitObjectStates[it.id] == true }

                                            if (judgement != JudgementType.MISS) {
                                                comboCounter++
                                                totalScore += judgement.score * comboCounter.coerceAtMost(8)
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            } else {
                                                comboCounter = 0
                                            }

                                            // Mark hit (for circles mark finished immediately; for sliders start tracking)
                                            if (obj.type == RhythmObjectType.CIRCLE) {
                                                hitObjectStates[obj.id] = true
                                                completedObjectCount++
                                                val record = RhythmInteractionRecord(
                                                    objectId = obj.id,
                                                    isSlider = false,
                                                    comboNumber = obj.comboNumber,
                                                    timingErrorMs = deltaTiming,
                                                    judgement = judgement,
                                                    touchDistancePx = dist,
                                                    normalizedRadialOffset = (dist / hitRadius).coerceIn(0f, 1f),
                                                    hitInOrder = hitInOrder,
                                                    sliderTrackingRatio = 1.0f,
                                                    timestampMs = nowMs
                                                )
                                                interactions.add(record)
                                                onProgressUpdate(completedObjectCount, totalObjects)
                                            } else {
                                                // Slider started
                                                sliderTrackingStats[obj.id] = Pair(1, 1)
                                            }

                                            floatingBursts.add(
                                                JudgementBurst(
                                                    id = nowMs,
                                                    text = judgement.label,
                                                    colorLong = judgement.colorLong,
                                                    position = Offset(obj.relX, obj.relY),
                                                    spawnTimeMs = nowMs
                                                )
                                            )
                                            break // Handled this tap
                                        }
                                    }
                                }
                            }
                        }

                        // 2. Continuous Tracking for Active Sliders
                        val activeSliders = objects.filter {
                            it.type == RhythmObjectType.SLIDER &&
                            hitObjectStates[it.id] != true &&
                            nowMs >= it.targetHitTimeMs(gameStartMs) &&
                            nowMs <= it.sliderEndTimeMs(gameStartMs)
                        }

                        for (slider in activeSliders) {
                            val startMs = slider.targetHitTimeMs(gameStartMs)
                            val progress = ((nowMs - startMs).toFloat() / slider.durationMs.toFloat()).coerceIn(0f, 1f)
                            val ballPos = slider.getBezierPoint(progress, w, h)
                            val trackingRadius = with(density) { 64.dp.toPx() }

                            val isTracked = activeTouches.any { touch ->
                                val tdx = touch.x - ballPos.x
                                val tdy = touch.y - ballPos.y
                                sqrt(tdx * tdx + tdy * tdy) <= trackingRadius
                            }

                            val currentStats = sliderTrackingStats[slider.id] ?: Pair(0, 0)
                            val newTracked = if (isTracked) currentStats.first + 1 else currentStats.first
                            val newTotal = currentStats.second + 1
                            sliderTrackingStats[slider.id] = Pair(newTracked, newTotal)

                            // Check if slider reached the end
                            if (progress >= 1.0f) {
                                hitObjectStates[slider.id] = true
                                completedObjectCount++
                                val trackingRatio = (newTracked.toFloat() / newTotal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                                val finalJudgement = when {
                                    trackingRatio >= 0.75f -> JudgementType.PERFECT
                                    trackingRatio >= 0.50f -> JudgementType.GREAT
                                    trackingRatio >= 0.30f -> JudgementType.GOOD
                                    else -> JudgementType.MISS
                                }

                                if (finalJudgement != JudgementType.MISS) {
                                    comboCounter++
                                    totalScore += finalJudgement.score * comboCounter.coerceAtMost(8)
                                } else {
                                    comboCounter = 0
                                }

                                val record = RhythmInteractionRecord(
                                    objectId = slider.id,
                                    isSlider = true,
                                    comboNumber = slider.comboNumber,
                                    timingErrorMs = 0L,
                                    judgement = finalJudgement,
                                    touchDistancePx = 0f,
                                    normalizedRadialOffset = 0f,
                                    hitInOrder = true,
                                    sliderTrackingRatio = trackingRatio,
                                    timestampMs = nowMs
                                )
                                interactions.add(record)
                                onProgressUpdate(completedObjectCount, totalObjects)

                                floatingBursts.add(
                                    JudgementBurst(
                                        id = nowMs,
                                        text = finalJudgement.label,
                                        colorLong = finalJudgement.colorLong,
                                        position = Offset(slider.endRelX, slider.endRelY),
                                        spawnTimeMs = nowMs
                                    )
                                )
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
            val circleRadius = 42.dp.toPx()

            // ── LAYER 1: Ambient Central Spiritual Glow ──
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
            // Draw connecting lines between consecutive unhit objects (like in osu!)
            val visibleObjects = objects.filter {
                hitObjectStates[it.id] != true &&
                nowMs >= (it.spawnOffsetMs + gameStartMs - 200L) &&
                nowMs <= (it.sliderEndTimeMs(gameStartMs) + 300L)
            }.sortedBy { it.id }

            for (i in 0 until visibleObjects.size - 1) {
                val curr = visibleObjects[i]
                val next = visibleObjects[i + 1]
                if (curr.comboNumber < next.comboNumber) {
                    val p1 = Offset(curr.relX * canvasW, curr.relY * canvasH)
                    val p2 = Offset(next.relX * canvasW, next.relY * canvasH)
                    drawLine(
                        color = Color(curr.colorLong).copy(alpha = 0.35f),
                        start = p1,
                        end = p2,
                        strokeWidth = 2.dp.toPx()
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

                // A. Slider Body Track (Thick Translucent Neon Ribbon)
                val trackColor = Color(slider.colorLong)
                drawPath(
                    path = sliderPath,
                    color = trackColor.copy(alpha = 0.18f),
                    style = Stroke(width = 46.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                // B. Slider Track Outer Crisp Neon Border
                drawPath(
                    path = sliderPath,
                    color = trackColor.copy(alpha = 0.75f),
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

                    // Follower Tolerance Ring
                    val followerRadius = 56.dp.toPx()
                    val isBeingTracked = activeTouches.any { touch ->
                        val dx = touch.x - ballPos.x
                        val dy = touch.y - ballPos.y
                        sqrt(dx * dx + dy * dy) <= followerRadius
                    }

                    drawCircle(
                        color = if (isBeingTracked) trackColor.copy(alpha = 0.40f) else Color(0x30F59E0B),
                        radius = followerRadius * (if (isBeingTracked) pulsePhase else 0.95f),
                        center = ballPos,
                        style = Stroke(width = 2.dp.toPx())
                    )

                    // Brilliant Glowing Ball
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
                nowMs >= (it.spawnOffsetMs + gameStartMs) &&
                nowMs <= (it.targetHitTimeMs(gameStartMs) + 350L)
            }

            for (obj in activeCircles) {
                val circleCenter = Offset(obj.relX * canvasW, obj.relY * canvasH)
                val targetTime = obj.targetHitTimeMs(gameStartMs)
                val timeToHit = targetTime - nowMs
                val accentColor = Color(obj.colorLong)

                // A. Base Hit Circle Body
                drawCircle(
                    color = Color(0xDD0B1A13),
                    radius = circleRadius,
                    center = circleCenter
                )
                // B. Crisp Outer Neon Border
                drawCircle(
                    color = accentColor,
                    radius = circleRadius,
                    center = circleCenter,
                    style = Stroke(width = 3.5.dp.toPx())
                )
                // C. Inner Frosted Glow
                drawCircle(
                    color = accentColor.copy(alpha = 0.25f),
                    radius = circleRadius * 0.72f,
                    center = circleCenter
                )

                // D. Combo Sequence Number in Center
                drawContext.canvas.nativeCanvas.apply {
                    numberTextPaint.textSize = with(density) { 24.sp.toPx() }
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
                    val ringAlpha = (1f - (progress * 0.5f)).coerceIn(0.2f, 0.95f)

                    drawCircle(
                        color = accentColor.copy(alpha = ringAlpha),
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
                    burstPaint.color = Color(burst.colorLong).copy(alpha = alpha).hashCode()
                    burstPaint.textSize = with(density) { (22 + (animProgress * 6)).sp.toPx() }
                    drawText(
                        burst.text,
                        burst.position.x * canvasW,
                        (burst.position.y * canvasH) - yOffset,
                        burstPaint
                    )
                }
            }

            // ── LAYER 6: Top HUD (Combo, Score & Sequence Progress) ──
            drawContext.canvas.nativeCanvas.apply {
                hudTextPaint.textSize = with(density) { 16.sp.toPx() }
                // Combo Counter
                val comboText = if (comboCounter > 1) "COMBO ${comboCounter}x" else ""
                drawText(comboText, 24.dp.toPx(), 36.dp.toPx(), hudTextPaint)

                // Real-time Accuracy & Score
                val progressText = "$completedObjectCount / $totalObjects"
                hudTextPaint.textAlign = Paint.Align.RIGHT
                drawText(progressText, canvasW - 24.dp.toPx(), 36.dp.toPx(), hudTextPaint)
                hudTextPaint.textAlign = Paint.Align.LEFT
            }
        }
    }
}
