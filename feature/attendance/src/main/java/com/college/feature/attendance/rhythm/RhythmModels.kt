package com.college.feature.attendance.rhythm

import androidx.compose.ui.geometry.Offset
import kotlin.math.pow

enum class RhythmObjectType {
    CIRCLE,
    SLIDER
}

enum class JudgementType(val score: Int, val label: String, val colorLong: Long) {
    PERFECT(300, "300", 0xFF00F5A0), // Emerald
    GREAT(100, "100", 0xFF38BDF8),   // Cyan
    GOOD(50, "50", 0xFFF59E0B),      // Amber
    MISS(0, "MISS", 0xFFEF4444)      // Crimson Red
}

/**
 * Definition of a single hit object (Circle or Slider) in an osu!-style sequence.
 */
data class RhythmBeatmapObject(
    val id: Int,
    val type: RhythmObjectType,
    val comboNumber: Int,
    val relX: Float,
    val relY: Float,
    val controlRelX: Float = 0.5f, // Quadratic bezier control point for slider
    val controlRelY: Float = 0.5f,
    val endRelX: Float = 0.5f,     // Slider end point
    val endRelY: Float = 0.5f,
    val spawnOffsetMs: Long,
    val approachDurationMs: Long = 950L,
    val durationMs: Long = 1300L,  // Duration of ball rolling along slider track
    val colorLong: Long = 0xFF00F5A0
) {
    fun targetHitTimeMs(gameStartMs: Long): Long = gameStartMs + spawnOffsetMs + approachDurationMs
    fun sliderEndTimeMs(gameStartMs: Long): Long = targetHitTimeMs(gameStartMs) + durationMs

    /**
     * Calculates quadratic bezier position for slider ball at parameter progress [0.0 .. 1.0].
     */
    fun getBezierPoint(progress: Float, widthPx: Float, heightPx: Float): Offset {
        val t = progress.coerceIn(0f, 1f)
        val oneMinusT = 1f - t

        val p0x = relX * widthPx
        val p0y = relY * heightPx
        val p1x = controlRelX * widthPx
        val p1y = controlRelY * heightPx
        val p2x = endRelX * widthPx
        val p2y = endRelY * heightPx

        val x = (oneMinusT * oneMinusT * p0x) + (2f * oneMinusT * t * p1x) + (t * t * p2x)
        val y = (oneMinusT * oneMinusT * p0y) + (2f * oneMinusT * t * p1y) + (t * t * p2y)
        return Offset(x, y)
    }
}

/**
 * Animated floating score popup when an object is hit or missed.
 */
data class JudgementBurst(
    val id: Long,
    val text: String,
    val colorLong: Long,
    val position: Offset,
    val spawnTimeMs: Long,
    val durationMs: Long = 650L
)

/**
 * Telemetry collected on each interaction.
 */
data class RhythmInteractionRecord(
    val objectId: Int,
    val isSlider: Boolean,
    val comboNumber: Int,
    val timingErrorMs: Long, // actual - target (negative = early, positive = late)
    val judgement: JudgementType,
    val touchDistancePx: Float,
    val normalizedRadialOffset: Float,
    val hitInOrder: Boolean,
    val sliderTrackingRatio: Float = 1.0f, // 1.0 = followed throughout
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Curated 12-second attendance rhythm map.
 * Fast, fluid, elegant, and engaging for all students.
 */
object AttendanceBeatmap {
    val objects: List<RhythmBeatmapObject> = listOf(
        // ── Combo 1: Emerald Rhythm Stream (Warmup Phase) ──
        RhythmBeatmapObject(
            id = 1,
            type = RhythmObjectType.CIRCLE,
            comboNumber = 1,
            relX = 0.28f,
            relY = 0.32f,
            spawnOffsetMs = 250L,
            approachDurationMs = 950L,
            colorLong = 0xFF00F5A0
        ),
        RhythmBeatmapObject(
            id = 2,
            type = RhythmObjectType.CIRCLE,
            comboNumber = 2,
            relX = 0.50f,
            relY = 0.44f,
            spawnOffsetMs = 800L,
            approachDurationMs = 950L,
            colorLong = 0xFF00F5A0
        ),
        RhythmBeatmapObject(
            id = 3,
            type = RhythmObjectType.CIRCLE,
            comboNumber = 3,
            relX = 0.72f,
            relY = 0.30f,
            spawnOffsetMs = 1350L,
            approachDurationMs = 950L,
            colorLong = 0xFF00F5A0
        ),
        // Arc Slider 4: Sweeping continuous curve from bottom-right up through center
        RhythmBeatmapObject(
            id = 4,
            type = RhythmObjectType.SLIDER,
            comboNumber = 4,
            relX = 0.72f,
            relY = 0.65f,
            controlRelX = 0.48f,
            controlRelY = 0.82f,
            endRelX = 0.25f,
            endRelY = 0.62f,
            spawnOffsetMs = 2000L,
            approachDurationMs = 950L,
            durationMs = 1300L,
            colorLong = 0xFF00F5A0
        ),

        // ── Combo 2: Electric Cyan Triangle & Diagonal Slider ──
        RhythmBeatmapObject(
            id = 5,
            type = RhythmObjectType.CIRCLE,
            comboNumber = 1,
            relX = 0.30f,
            relY = 0.68f,
            spawnOffsetMs = 4300L,
            approachDurationMs = 900L,
            colorLong = 0xFF38BDF8
        ),
        RhythmBeatmapObject(
            id = 6,
            type = RhythmObjectType.CIRCLE,
            comboNumber = 2,
            relX = 0.70f,
            relY = 0.52f,
            spawnOffsetMs = 4850L,
            approachDurationMs = 900L,
            colorLong = 0xFF38BDF8
        ),
        RhythmBeatmapObject(
            id = 7,
            type = RhythmObjectType.CIRCLE,
            comboNumber = 3,
            relX = 0.45f,
            relY = 0.24f,
            spawnOffsetMs = 5400L,
            approachDurationMs = 900L,
            colorLong = 0xFF38BDF8
        ),
        // Sweep Slider 4: Gliding S-curve from top-left to bottom-right
        RhythmBeatmapObject(
            id = 8,
            type = RhythmObjectType.SLIDER,
            comboNumber = 4,
            relX = 0.25f,
            relY = 0.32f,
            controlRelX = 0.52f,
            controlRelY = 0.42f,
            endRelX = 0.75f,
            endRelY = 0.70f,
            spawnOffsetMs = 6050L,
            approachDurationMs = 900L,
            durationMs = 1400L,
            colorLong = 0xFF38BDF8
        ),

        // ── Combo 3: Golden Crescendo Finale ──
        RhythmBeatmapObject(
            id = 9,
            type = RhythmObjectType.CIRCLE,
            comboNumber = 1,
            relX = 0.68f,
            relY = 0.30f,
            spawnOffsetMs = 8400L,
            approachDurationMs = 850L,
            colorLong = 0xFFF59E0B
        ),
        RhythmBeatmapObject(
            id = 10,
            type = RhythmObjectType.CIRCLE,
            comboNumber = 2,
            relX = 0.32f,
            relY = 0.46f,
            spawnOffsetMs = 8900L,
            approachDurationMs = 850L,
            colorLong = 0xFFF59E0B
        ),
        RhythmBeatmapObject(
            id = 11,
            type = RhythmObjectType.CIRCLE,
            comboNumber = 3,
            relX = 0.65f,
            relY = 0.68f,
            spawnOffsetMs = 9400L,
            approachDurationMs = 850L,
            colorLong = 0xFFF59E0B
        ),
        // Grand Center Star Finale
        RhythmBeatmapObject(
            id = 12,
            type = RhythmObjectType.CIRCLE,
            comboNumber = 4,
            relX = 0.50f,
            relY = 0.45f,
            spawnOffsetMs = 9950L,
            approachDurationMs = 850L,
            colorLong = 0xFFF59E0B
        )
    )

    val totalDurationMs: Long = 11800L
}
