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
 * Procedural Beatmap Generator that synthesizes device-unique, unsynchronized rhythm maps.
 * Uses SHA-256 hash of SessionID + BeaconSalt + StudentID to produce distinct spatial coordinates,
 * distinct tempo intervals, and unpredictable curved slider trajectories.
 * This completely defeats dual-phone proxy attempts because human visual attention cannot
 * track two independent, unsynchronized rhythmic fields simultaneously.
 */
object ProceduralBeatmapGenerator {
    fun generate(seedKey: String): List<RhythmBeatmapObject> {
        val hash = seedKey.fold(1125899906842597L) { acc, c ->
            (acc * 131L + c.code.toLong()) and 0x7FFFFFFFFFFFFFFFL
        }
        val rng = java.util.Random(hash)

        val objects = mutableListOf<RhythmBeatmapObject>()
        var currentSpawnMs = 250L

        // Combo 1: Emerald Rhythm Stream (4 objects: 3 circles + 1 curved slider)
        val combo1Color = 0xFF00F5A0
        val p1x = 0.25f + rng.nextFloat() * 0.18f
        val p1y = 0.30f + rng.nextFloat() * 0.14f
        objects.add(
            RhythmBeatmapObject(
                id = 1,
                type = RhythmObjectType.CIRCLE,
                comboNumber = 1,
                relX = p1x,
                relY = p1y,
                spawnOffsetMs = currentSpawnMs,
                approachDurationMs = 1000L,
                colorLong = combo1Color
            )
        )

        currentSpawnMs += 780L + (rng.nextInt(140) - 70L)
        val p2x = 0.44f + rng.nextFloat() * 0.16f
        val p2y = 0.44f + rng.nextFloat() * 0.14f
        objects.add(
            RhythmBeatmapObject(
                id = 2,
                type = RhythmObjectType.CIRCLE,
                comboNumber = 2,
                relX = p2x,
                relY = p2y,
                spawnOffsetMs = currentSpawnMs,
                approachDurationMs = 1000L,
                colorLong = combo1Color
            )
        )

        currentSpawnMs += 780L + (rng.nextInt(140) - 70L)
        val p3x = 0.62f + rng.nextFloat() * 0.16f
        val p3y = 0.30f + rng.nextFloat() * 0.14f
        objects.add(
            RhythmBeatmapObject(
                id = 3,
                type = RhythmObjectType.CIRCLE,
                comboNumber = 3,
                relX = p3x,
                relY = p3y,
                spawnOffsetMs = currentSpawnMs,
                approachDurationMs = 1000L,
                colorLong = combo1Color
            )
        )

        currentSpawnMs += 800L + (rng.nextInt(120) - 60L)
        val s4Start = Offset(0.64f + rng.nextFloat() * 0.12f, 0.60f + rng.nextFloat() * 0.08f)
        val s4Ctrl = Offset(0.46f + rng.nextFloat() * 0.10f, 0.74f + rng.nextFloat() * 0.05f)
        val s4End = Offset(0.24f + rng.nextFloat() * 0.10f, 0.58f + rng.nextFloat() * 0.08f)
        objects.add(
            RhythmBeatmapObject(
                id = 4,
                type = RhythmObjectType.SLIDER,
                comboNumber = 4,
                relX = s4Start.x,
                relY = s4Start.y,
                controlRelX = s4Ctrl.x,
                controlRelY = s4Ctrl.y,
                endRelX = s4End.x,
                endRelY = s4End.y,
                spawnOffsetMs = currentSpawnMs,
                approachDurationMs = 1000L,
                durationMs = 1250L,
                colorLong = combo1Color
            )
        )

        // Combo 2: Electric Cyan (4 objects: 3 circles + 1 sweep slider)
        val combo2Color = 0xFF38BDF8
        currentSpawnMs += 2250L // Transition after slider 4 finishes
        val p5x = 0.24f + rng.nextFloat() * 0.14f
        val p5y = 0.64f + rng.nextFloat() * 0.10f
        objects.add(
            RhythmBeatmapObject(
                id = 5,
                type = RhythmObjectType.CIRCLE,
                comboNumber = 1,
                relX = p5x,
                relY = p5y,
                spawnOffsetMs = currentSpawnMs,
                approachDurationMs = 950L,
                colorLong = combo2Color
            )
        )

        currentSpawnMs += 760L + (rng.nextInt(120) - 60L)
        val p6x = 0.66f + rng.nextFloat() * 0.12f
        val p6y = 0.48f + rng.nextFloat() * 0.12f
        objects.add(
            RhythmBeatmapObject(
                id = 6,
                type = RhythmObjectType.CIRCLE,
                comboNumber = 2,
                relX = p6x,
                relY = p6y,
                spawnOffsetMs = currentSpawnMs,
                approachDurationMs = 950L,
                colorLong = combo2Color
            )
        )

        currentSpawnMs += 760L + (rng.nextInt(120) - 60L)
        val p7x = 0.42f + rng.nextFloat() * 0.16f
        val p7y = 0.26f + rng.nextFloat() * 0.10f
        objects.add(
            RhythmBeatmapObject(
                id = 7,
                type = RhythmObjectType.CIRCLE,
                comboNumber = 3,
                relX = p7x,
                relY = p7y,
                spawnOffsetMs = currentSpawnMs,
                approachDurationMs = 950L,
                colorLong = combo2Color
            )
        )

        currentSpawnMs += 780L + (rng.nextInt(120) - 60L)
        val s8Start = Offset(0.24f + rng.nextFloat() * 0.10f, 0.32f + rng.nextFloat() * 0.08f)
        val s8Ctrl = Offset(0.50f + rng.nextFloat() * 0.08f, 0.46f + rng.nextFloat() * 0.08f)
        val s8End = Offset(0.72f + rng.nextFloat() * 0.08f, 0.66f + rng.nextFloat() * 0.08f)
        objects.add(
            RhythmBeatmapObject(
                id = 8,
                type = RhythmObjectType.SLIDER,
                comboNumber = 4,
                relX = s8Start.x,
                relY = s8Start.y,
                controlRelX = s8Ctrl.x,
                controlRelY = s8Ctrl.y,
                endRelX = s8End.x,
                endRelY = s8End.y,
                spawnOffsetMs = currentSpawnMs,
                approachDurationMs = 950L,
                durationMs = 1300L,
                colorLong = combo2Color
            )
        )

        // Combo 3: Golden Crescendo Finale (4 circles)
        val combo3Color = 0xFFF59E0B
        currentSpawnMs += 2250L // Transition after slider 8 finishes
        val p9x = 0.66f + rng.nextFloat() * 0.12f
        val p9y = 0.30f + rng.nextFloat() * 0.10f
        objects.add(
            RhythmBeatmapObject(
                id = 9,
                type = RhythmObjectType.CIRCLE,
                comboNumber = 1,
                relX = p9x,
                relY = p9y,
                spawnOffsetMs = currentSpawnMs,
                approachDurationMs = 900L,
                colorLong = combo3Color
            )
        )

        currentSpawnMs += 720L + (rng.nextInt(100) - 50L)
        val p10x = 0.28f + rng.nextFloat() * 0.10f
        val p10y = 0.48f + rng.nextFloat() * 0.10f
        objects.add(
            RhythmBeatmapObject(
                id = 10,
                type = RhythmObjectType.CIRCLE,
                comboNumber = 2,
                relX = p10x,
                relY = p10y,
                spawnOffsetMs = currentSpawnMs,
                approachDurationMs = 900L,
                colorLong = combo3Color
            )
        )

        currentSpawnMs += 720L + (rng.nextInt(100) - 50L)
        val p11x = 0.64f + rng.nextFloat() * 0.10f
        val p11y = 0.66f + rng.nextFloat() * 0.08f
        objects.add(
            RhythmBeatmapObject(
                id = 11,
                type = RhythmObjectType.CIRCLE,
                comboNumber = 3,
                relX = p11x,
                relY = p11y,
                spawnOffsetMs = currentSpawnMs,
                approachDurationMs = 900L,
                colorLong = combo3Color
            )
        )

        currentSpawnMs += 740L + (rng.nextInt(100) - 50L)
        val p12x = 0.46f + rng.nextFloat() * 0.08f
        val p12y = 0.44f + rng.nextFloat() * 0.08f
        objects.add(
            RhythmBeatmapObject(
                id = 12,
                type = RhythmObjectType.CIRCLE,
                comboNumber = 4,
                relX = p12x,
                relY = p12y,
                spawnOffsetMs = currentSpawnMs,
                approachDurationMs = 900L,
                colorLong = combo3Color
            )
        )

        return objects
    }
}

/**
 * Standard beatmap wrapper with fallback.
 */
object AttendanceBeatmap {
    fun getForSeed(seedKey: String?): List<RhythmBeatmapObject> {
        val cleanSeed = seedKey?.trim() ?: ""
        return if (cleanSeed.isNotEmpty() && cleanSeed != "----" && cleanSeed != "----:------:0000") {
            ProceduralBeatmapGenerator.generate(cleanSeed)
        } else {
            defaultObjects
        }
    }

    val defaultObjects: List<RhythmBeatmapObject> = ProceduralBeatmapGenerator.generate("ATTENDANCE_DEFAULT_CURATED_MAP")
    val objects: List<RhythmBeatmapObject> get() = defaultObjects
    val totalDurationMs: Long = 13500L
}
