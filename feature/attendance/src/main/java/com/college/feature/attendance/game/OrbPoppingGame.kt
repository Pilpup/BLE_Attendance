package com.college.feature.attendance.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.withTransform
import com.college.feature.attendance.telemetry.ExpiredTargetEvent
import com.college.feature.attendance.telemetry.TapEvent
import java.util.Random
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Immutable
data class ReflexTarget(
    val id: Int,
    val relX: Float,
    val relY: Float,
    val isGolden: Boolean = false,
    val isTapped: Boolean = false,
    val spawnTimeMillis: Long = 0L,
    val lifetimeMillis: Long = 900L
)

// Pre-computed radial gradient color stops for orb glow rendering
private val PurpleStops = arrayOf(
    0.00f to Color(0xFFC4B5FD),
    0.14f to Color(0xFF8B5CF6),
    0.28f to Color(0x808B5CF6),
    0.44f to Color(0x2E8B5CF6),
    0.62f to Color(0x0A8B5CF6),
    0.80f to Color.Transparent,
    1.00f to Color.Transparent
)

private val SolarStops = arrayOf(
    0.00f to Color(0xFFFFD180),
    0.14f to Color(0xFFFF7A00),
    0.28f to Color(0x80FF7A00),
    0.44f to Color(0x2EFF7A00),
    0.62f to Color(0x0AFF7A00),
    0.80f to Color.Transparent,
    1.00f to Color.Transparent
)

private val AmbientGlowColors = listOf(
    Color(0x187033FF),
    Color(0x067033FF),
    Color.Transparent
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

    val isGolden = (rng.nextFloat() < 0.55f)
    return ReflexTarget(
        id = id,
        relX = bestX,
        relY = bestY,
        isGolden = isGolden,
        isTapped = false,
        spawnTimeMillis = System.currentTimeMillis(),
        lifetimeMillis = 900L
    )
}

// Replenishes active targets when count drops below threshold
private fun replenishTargets(
    currentActive: List<ReflexTarget>,
    totalSpawned: Int,
    targetsGoal: Int,
    nowTime: Long,
    rng: Random
): Pair<List<ReflexTarget>, Int>? {
    val living = currentActive.filter { target ->
        !target.isTapped && (nowTime - target.spawnTimeMillis < target.lifetimeMillis)
    }.toMutableList()

    if (living.size >= 3 || totalSpawned >= targetsGoal) {
        return null
    }

    val needed = (6 - living.size).coerceAtMost(targetsGoal - totalSpawned)
    if (needed <= 0) return null

    val newOrbs = mutableListOf<ReflexTarget>()
    for (k in 0 until needed) {
        val newOrb = generateNewTarget(
            id = totalSpawned + k,
            existingTargets = living + newOrbs,
            rng = rng
        ).copy(
            spawnTimeMillis = nowTime + (k * 70L),
            lifetimeMillis = 900L
        )
        newOrbs.add(newOrb)
    }

    return (currentActive + newOrbs) to (totalSpawned + newOrbs.size)
}

// K1 ("Kaze" #1, draw-only pipeline): all-orbs-in-one immutable frame snapshot
// consumed by ONE Canvas. The render state is a flat FloatArray + revision
// counter — the draw block reads the revision, so per-frame updates land in
// the DRAW phase only: no per-orb composition nodes, no re-layout, no
// graphicsLayer churn, and zero per-frame Brush allocations (both gradients
// are built once and re-centered per orb via canvas translate+scale, which
// moves the shader matrix with the circle).
//
// Game semantics preserved exactly from the superseded per-orb pipeline:
// spawn math, staggered spawn delays, expiry events + duplicate guard,
// tap telemetry fields (touchOffsetX/Y, normalizedRadialOffset), the
// haptic fire, and the onProgress/onComplete call order are all byte-for-byte
// the same formulas. The loop + taps + draw all run on the main thread, so
// the plain buffers need no synchronization.
private class FrameBuffer(val slots: Int) {
    val floats = FloatArray(6 * slots) // [id, cx, cy, alpha, scale, golden] per slot
    var slotCount by mutableIntStateOf(0)
        private set
    var revision by mutableLongStateOf(0L)
        private set

    fun setSlot(index: Int, id: Int, cx: Float, cy: Float, alpha: Float, scale: Float, golden: Boolean) {
        val base = index * 6
        floats[base] = id.toFloat()
        floats[base + 1] = cx
        floats[base + 2] = cy
        floats[base + 3] = alpha
        floats[base + 4] = scale
        floats[base + 5] = if (golden) 1f else 0f
    }

    // Read-published pair for the draw block: read revision FIRST (registers
    // the state read that triggers draw-phase invalidation), then count.
    fun publish(count: Int) {
        slotCount = count
        revision++
    }

    fun clear() = publish(0)
}

// Game-logic state. Plain mutable holder on the main thread — the per-frame
// recomposition that used to justify State<> wrappers no longer exists.
private class GameCore {
    var targets: List<ReflexTarget> = emptyList()
    var totalSpawned = 0
    var targetsHit = 0
    var isGameOver = false
    var canvasWidthPx = 0
    var canvasHeightPx = 0
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
    val density = LocalDensity.current

    val core = remember(seedKey) { GameCore() }
    val frame = remember(seedKey) {
        FrameBuffer(GameTelemetryEngine.DEFAULT_TARGETS_GOAL)
    }

    val rng = remember(seedKey) {
        val seed = seedKey.hashCode().toLong().absoluteValue
        Random(seed)
    }

    val targetsGoal = GameTelemetryEngine.DEFAULT_TARGETS_GOAL

    val recordedTaps = remember { mutableStateListOf<TapEvent>() }
    val recordedExpired = remember { mutableStateListOf<ExpiredTargetEvent>() }

    // Fixed sprite metrics in px, resolved once (the old pipeline re-derived
    // these in dp inside BoxWithConstraints every recomposition)
    val targetPx = remember(density) { with(density) { 150.dp.toPx() } }
    val padPx = remember(density) { with(density) { 12.dp.toPx() } }
    val marginPx = remember(density) { with(density) { 16.dp.toPx() } }

    // Spirit motion driver: the old spiritPhase infinite transition ramped
    // 0..2π over 3400ms linearly. Same ramp, derived from the frame nanos.
    val spiritPeriodNanos = 3400L * 1_000_000L

    fun spiritPhaseNow(): Float {
        val nanos = System.nanoTime() % spiritPeriodNanos
        return ((nanos.toFloat() / spiritPeriodNanos.toFloat()) * (2 * Math.PI).toFloat())
    }

    // The two orb brushes + the ambient glow brush are built once. The glow
    // radius depends on canvas size, so its key is the measured size: rebuilt
    // only when the canvas is actually resized, never per frame.
    var sizeKey by remember { mutableLongStateOf(0L) }
    val orbRadius = targetPx / 2f
    val purpleBrush = remember(targetPx) {
        Brush.radialGradient(
            colorStops = PurpleStops,
            center = Offset.Zero,
            radius = orbRadius
        )
    }
    val solarBrush = remember(targetPx) {
        Brush.radialGradient(
            colorStops = SolarStops,
            center = Offset.Zero,
            radius = orbRadius
        )
    }
    val ambientBrush = remember(sizeKey) {
        val w = core.canvasWidthPx.toFloat()
        val h = core.canvasHeightPx.toFloat()
        if (w <= 0f) {
            // First composition runs before onSizeChanged — a zero-radius
            // RadialGradient shader throws at draw time. Null defers the
            // glow to the post-layout rebuild (crash found on S23: the
            // first frame's draw beat the scheduled recomposition).
            null
        } else {
            val center = Offset(w / 2f, h / 2f)
            val glowRadius = w * 0.65f
            Brush.radialGradient(
                colors = AmbientGlowColors,
                center = center,
                radius = glowRadius
            )
        }
    }

    // Drift is applied in dp exactly as the superseded per-orb pipeline did
    // (drift × dp-scale), then clamped in px space — the raw sin/cos outputs
    // are 12 dp amplitudes, matching `(sin * 12f).dp` in the old code.
    val dpPx = remember(density) { with(density) { 1.dp.toPx() } }
    fun writeFrame(spiritPhase: Float) {
        val w = core.canvasWidthPx.toFloat()
        val h = core.canvasHeightPx.toFloat()
        val availW = (w - targetPx - marginPx).coerceAtLeast(0f)
        val availH = (h - targetPx - marginPx).coerceAtLeast(0f)
        val nowMs = System.currentTimeMillis()
        var slot = 0
        for (target in core.targets) {
            if (target.isTapped) continue
            val age = nowMs - target.spawnTimeMillis
            // Exactly the old graphicsLayer guards: not-yet-spawned and
            // expired orbs render invisible
            if (age < 0L || age >= target.lifetimeMillis) continue

            val driftX = (sin((spiritPhase * 2f + target.id * 1.4f).toDouble()).toFloat() * 12f) * dpPx
            val driftY = (cos((spiritPhase * 2f + target.id * 1.9f).toDouble()).toFloat() * 12f) * dpPx
            val xDp = (target.relX * availW + driftX).coerceIn(0f, availW)
            val yDp = (target.relY * availH + driftY).coerceIn(0f, availH)
            val centerX = padPx + xDp + orbRadius
            val centerY = padPx + yDp + orbRadius

            val fadeDuration = 160f
            val spawnProgress = (age.toFloat() / fadeDuration).coerceIn(0f, 1f)
            val despawnProgress = ((target.lifetimeMillis - age).toFloat() / fadeDuration).coerceIn(0f, 1f)
            val rawFade = spawnProgress * despawnProgress
            val envelopeAlpha = rawFade * rawFade * (3f - 2f * rawFade)
            val envelopeScale = 0.72f + (0.28f * envelopeAlpha)
            val pulseNorm = (sin((spiritPhase * 1.5f + target.id * 1.57f).toDouble()).toFloat() + 1f) / 2f
            val targetPulseScale = (0.94f + (0.08f * pulseNorm)) * envelopeScale
            val alpha = envelopeAlpha * (0.60f + (0.40f * pulseNorm))

            frame.setSlot(slot++, target.id, centerX, centerY, alpha, targetPulseScale, target.isGolden)
        }
        frame.publish(slot)
    }

    // Tap hit-testing against the last published frame geometry. Iterate
    // reversed so the topmost (most recently spawned) orb wins an overlap,
    // reproducing old z-order dispatch. Telemetry fields are reproduced
    // from the target record exactly.
    fun onTapOrb(tapX: Float, tapY: Float) {
        if (core.isGameOver) return
        val n = frame.slotCount
        var hitSlot = -1
        for (i in n - 1 downTo 0) {
            val base = i * 6
            val id = frame.floats[base].toInt()
            val cx = frame.floats[base + 1]
            val cy = frame.floats[base + 2]
            val dx = tapX - cx
            val dy = tapY - cy
            val dist = sqrt(dx * dx + dy * dy)
            if (dist <= orbRadius) {
                hitSlot = i
                break
            }
        }
        if (hitSlot < 0) return

        val base = hitSlot * 6
        val id = frame.floats[base].toInt()
        val cx = frame.floats[base + 1]
        val cy = frame.floats[base + 2]
        val target = core.targets.firstOrNull { it.id == id } ?: return
        if (target.isTapped) return
        val nowTapTime = System.currentTimeMillis()
        val targetAge = nowTapTime - target.spawnTimeMillis
        if (targetAge < 0L || targetAge >= target.lifetimeMillis) return

        val dx = tapX - cx
        val dy = tapY - cy
        val dist = sqrt(dx * dx + dy * dy)
        val normDist = (dist / orbRadius).coerceIn(0f, 1f)

        haptic.performHapticFeedback(HapticFeedbackType.LongPress)

        // Record tap telemetry event — identical field semantics
        recordedTaps.add(
            TapEvent(
                targetId = target.id,
                tapTimeMillis = nowTapTime,
                spawnTimeMillis = target.spawnTimeMillis,
                targetRelX = target.relX,
                targetRelY = target.relY,
                touchOffsetX = dx,
                touchOffsetY = dy,
                normalizedRadialOffset = normDist
            )
        )

        // Update hit state and trigger wave replenishment — same data flow
        val updatedTargets = core.targets.map {
            if (it.id == target.id) it.copy(isTapped = true) else it
        }
        core.targets = updatedTargets
        core.targetsHit++
        onProgress(core.targetsHit, core.totalSpawned)
        writeFrame(spiritPhaseNow())

        replenishTargets(updatedTargets, core.totalSpawned, targetsGoal, nowTapTime, rng)?.let { (newTargets, newSpawned) ->
            core.targets = newTargets
            core.totalSpawned = newSpawned
            onProgress(core.targetsHit, core.totalSpawned)
            writeFrame(spiritPhaseNow())
        }
    }

    LaunchedEffect(seedKey) {
        core.targetsHit = 0
        core.isGameOver = false
        recordedTaps.clear()
        recordedExpired.clear()

        // Spawn initial wave of targets with staggered entrance delays
        val initial = mutableListOf<ReflexTarget>()
        val now = System.currentTimeMillis()
        for (i in 0 until 6) {
            val t = generateNewTarget(id = i, existingTargets = initial, rng = rng)
            initial.add(t.copy(spawnTimeMillis = now + (i * 70L), lifetimeMillis = 900L))
        }
        core.targets = initial
        core.totalSpawned = initial.size
        onProgress(core.targetsHit, core.totalSpawned)

        // Main game loop tracking expiration, replenishment, and completion.
        // R11 (review §2.2) pacing kept: withFrameNanos aligns to real frame
        // production. K1: the per-tick work is now one frame-buffer rewrite
        // + one revision bump — the entire draw cost is a single canvas pass.
        while (!core.isGameOver) {
            val nowTime = System.currentTimeMillis()

            // Record targets that exceeded their lifetime without a tap
            val newlyExpired = core.targets.filter { target ->
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
            val livingCount = core.targets.count { target ->
                !target.isTapped && (nowTime - target.spawnTimeMillis < target.lifetimeMillis)
            }

            // Replenish targets when living count drops below threshold
            replenishTargets(core.targets, core.totalSpawned, targetsGoal, nowTime, rng)?.let { (newTargets, newSpawned) ->
                core.targets = newTargets
                core.totalSpawned = newSpawned
                onProgress(core.targetsHit, core.totalSpawned)
            }

            // Complete the game once all targets have spawned and resolved
            if (core.totalSpawned >= targetsGoal && livingCount == 0) {
                core.targets = emptyList()
                core.isGameOver = true
                frame.clear()
                onComplete(recordedTaps.toList(), recordedExpired.toList(), targetsGoal)
                break
            }

            writeFrame(spiritPhaseNow())
            withFrameNanos { }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                core.canvasWidthPx = it.width
                core.canvasHeightPx = it.height
                sizeKey = it.width.toLong() * 65536L + it.height.toLong()
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(core) {
                    detectTapGestures { offset ->
                        onTapOrb(offset.x, offset.y)
                    }
                }
        ) {
            // Ambient background glow — first draw op, same z-order as when
            // it was a separate Canvas underneath the orbs. Skipped on the
            // very first frame when the size-keyed brush isn't built yet.
            if (ambientBrush != null) {
                drawCircle(
                    brush = ambientBrush,
                    radius = size.width * 0.65f,
                    center = Offset(size.width / 2f, size.height / 2f)
                )
            }

            // revision is read (not used numerically) so the frame-buffer
            // bump registers as a draw-phase state read; count drives the loop
            val rev = frame.revision
            val count = frame.slotCount
            if (rev < 0L) return@Canvas

            // One transform + one circle per orb: translate puts the origin
            // at the orb CENTER, scale pulses the radius around it. (A
            // translate-then-pivot-at-origin order miscentered the visual
            // circle up-left by R·(1−scale) and made hit-tests miss — found
            // by screenshot A/B against the pre-K1 build on the S23.)
            for (i in 0 until count) {
                val base = i * 6
                val cx = frame.floats[base + 1]
                val cy = frame.floats[base + 2]
                val alpha = frame.floats[base + 3]
                val scale = frame.floats[base + 4]
                val brush = if (frame.floats[base + 5] > 0f) solarBrush else purpleBrush

                withTransform({
                    translate(cx, cy)
                    scale(scale, scale, pivot = Offset.Zero)
                }) {
                    drawCircle(
                        brush = brush,
                        radius = orbRadius,
                        center = Offset.Zero,
                        alpha = alpha
                    )
                }
            }
        }
    }
}
