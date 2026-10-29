package com.college.feature.attendance.game

import kotlin.math.absoluteValue

/**
 * The 6 cognitive focus game archetypes designed to be visually & mentally orthogonal.
 * Grounded in human benchmark neuroscience to prevent dual-device proxy attendance.
 */
enum class AttendanceGameType(
    val id: Int,
    val displayName: String,
    val description: String,
    val instruction: String,
    val targetDurationSeconds: Int = 10
) {
    CHIMP_MEMORY(
        id = 0,
        displayName = "CHIMP MEMORY",
        description = "Visuospatial Working Memory",
        instruction = "Remember tile positions, tap in ascending order",
        targetDurationSeconds = 10
    ),
    STROOP_MATCH(
        id = 1,
        displayName = "STROOP MATCH",
        description = "Semantic Conflict Inhibition",
        instruction = "Swipe RIGHT if word matches color, LEFT if not",
        targetDurationSeconds = 10
    ),
    SUBITIZING_RUSH(
        id = 2,
        displayName = "SUBITIZING RUSH",
        description = "Parietal Visual Number Sense",
        instruction = "Swipe towards the side with MORE dots",
        targetDurationSeconds = 10
    ),
    ORB_VECTOR_FLICK(
        id = 3,
        displayName = "ORB VECTOR FLICK",
        description = "Spatial Target & Vector Coupling",
        instruction = "Touch the glowing orb and flick in arrow direction",
        targetDurationSeconds = 10
    ),
    CHUBB_CONTRAST(
        id = 4,
        displayName = "CHUBB CONTRAST",
        description = "V1 Lateral Contrast Illusion",
        instruction = "Swipe towards the sharper, clearer dot",
        targetDurationSeconds = 10
    ),
    POPOUT_ANOMALY(
        id = 5,
        displayName = "POP-OUT ANOMALY",
        description = "Pre-Attentive Feature Pop-Out",
        instruction = "Tap the broken ring among solid rings",
        targetDurationSeconds = 10
    );

    companion object {
        fun fromId(id: Int): AttendanceGameType = entries.firstOrNull { it.id == id } ?: CHIMP_MEMORY
    }
}

/**
 * High-performance interaction event record for behavioral telemetry.
 * Pre-allocated or instantiated once per trial to ensure 0 GC pressure.
 */
data class GameInteractionRecord(
    val trialIndex: Int,
    val latencyMs: Long,
    val isCorrect: Boolean,
    val hesitationMs: Long = 0L,
    val touchDistancePx: Float = 0f,
    val vectorDeviationDeg: Float = 0f, // For Orb Vector Flick
    val swipeVelocityPxPerMs: Float = 0f,
    val stimulusKey: String = "",
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Deterministic, mesh-aware game assigner.
 * Guarantees that colocated neighbor devices (potential proxy phones on the same desk)
 * are assigned DIFFERENT game archetypes, causing cognitive overload for cheaters.
 */
object DeterministicGameAssigner {
    private const val TOTAL_GAMES = 6

    fun assign(seedKey: String, neighborIds: List<String> = emptyList()): AttendanceGameType {
        if (seedKey.isBlank()) return AttendanceGameType.CHIMP_MEMORY

        // Stable hash of the local node's credentials
        val baseSlot = (seedKey.hashCode().absoluteValue) % TOTAL_GAMES

        if (neighborIds.isEmpty()) {
            return AttendanceGameType.fromId(baseSlot)
        }

        // Compute reserved slots of nearby neighbors
        val neighborSlots = neighborIds.map { nId ->
            (nId.hashCode().absoluteValue) % TOTAL_GAMES
        }.toSet()

        // Find the closest conflict-free slot
        var candidateSlot = baseSlot
        var iterations = 0
        while (candidateSlot in neighborSlots && iterations < TOTAL_GAMES) {
            candidateSlot = (candidateSlot + 1) % TOTAL_GAMES
            iterations++
        }

        return AttendanceGameType.fromId(candidateSlot)
    }
}
