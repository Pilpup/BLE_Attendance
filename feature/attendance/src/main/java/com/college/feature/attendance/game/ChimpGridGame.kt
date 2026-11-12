package com.college.feature.attendance.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.random.Random

// 4x4 grid packed in one 64-bit Long: 16 cells * 4 bits (nibble) = 64 bits.
// Nibble value 0 = empty, 1..15 = number shown in that cell.
private const val GRID = 4
private const val CELLS = 16
private const val MASK4 = 0xFL

// Hard per-round schedule (view + tap, fixed for EVERY student — round
// boundaries are synchronized class-wide, so no mid-game desync exists):
//   r1: 1.9 s view + 2.5 s tap | r2: 2.3 + 3.5 | r3: 3.3 + 4.5 | r4: 4.0 + 5.5
// Total 27.5 s exactly.
private const val GRID_R1_VIEW = 1900L
private const val GRID_R1_TAP = 2500L
private const val GRID_R2_VIEW = 2300L
private const val GRID_R2_TAP = 3500L
private const val GRID_R3_VIEW = 3300L
private const val GRID_R3_TAP = 4500L
private const val GRID_R4_VIEW = 4000L
private const val GRID_R4_TAP = 5500L

// Last 800 ms of every tap window is the REVEAL: remaining numbers light up
// green, wrong taps show red. Students who finish early see their reveal from
// the moment they complete; everyone's next round starts at the same instant.
private const val REVEAL_MS = 800L

private inline fun nibble(board: Long, idx: Int): Int = ((board ushr (idx * 4)) and MASK4).toInt()
private inline fun withNibble(board: Long, idx: Int, value: Int): Long {
    val shift = idx * 4
    return (board and (MASK4 shl shift).inv()) or ((value.toLong() and MASK4) shl shift)
}

private fun countForRound(round: Int): Int = when (round) {
    1 -> 5
    2 -> 6
    3 -> 7
    else -> 8
}

private fun viewWindowMs(n: Int): Long = when (n) {
    5 -> GRID_R1_VIEW
    6 -> GRID_R2_VIEW
    7 -> GRID_R3_VIEW
    else -> GRID_R4_VIEW
}

private fun tapWindowMs(n: Int): Long = when (n) {
    5 -> GRID_R1_TAP
    6 -> GRID_R2_TAP
    7 -> GRID_R3_TAP
    else -> GRID_R4_TAP
}

@Composable
fun ChimpGridGame(
    modifier: Modifier = Modifier,
    seed: Int = 0,
    // Synchronized start: all students in the room begin round 1 at the same
    // instant (teacher countdown beacon). The grid stays empty and the
    // countdown shows under the rules until then.
    startDelayMs: Long = 0L,
    onFinished: (score: Int) -> Unit = {},
) {
    val rng = remember(seed) { Random(seed) }

    var board by remember { mutableLongStateOf(0L) }          // 64-bit board (nibbles)
    var shownMask by remember { mutableIntStateOf(0) }        // 16-bit mask: untapped number cells
    var nextExpected by remember { mutableIntStateOf(1) }
    var count by remember { mutableIntStateOf(5) }
    var round by remember { mutableIntStateOf(1) }
    var score by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(true) }
    var preStartLeftMs by remember { mutableLongStateOf(startDelayMs) }

    // Per-round phase state (driven by the fixed-schedule loop below)
    var phaseShow by remember { mutableStateOf(true) }        // true = view window
    var revealPhase by remember { mutableStateOf(false) }     // true = reveal slot (last 800 ms)
    var roundCompleted by remember { mutableStateOf(false) }  // all n tapped -> immediate reveal
    var wrongCells by remember { mutableStateOf(setOf<Int>()) }
    var flashCell by remember { mutableIntStateOf(-1) }       // transient red flash on wrong tap
    var flashUntilMs by remember { mutableLongStateOf(0L) }
    var viewEndAtMs by remember { mutableLongStateOf(0L) }
    var revealStartAtMs by remember { mutableLongStateOf(0L) }
    var roundEndAtMs by remember { mutableLongStateOf(0L) }

    fun newRound(r: Int, roundStart: Long) {
        round = r
        val n = countForRound(r)
        count = n
        // place 1..n into random distinct cells (bit ops on 64-bit board)
        val cells = IntArray(CELLS) { it }
        for (i in 0 until CELLS) { // Fisher-Yates shuffle
            val j = i + rng.nextInt(CELLS - i)
            val tmp = cells[i]; cells[i] = cells[j]; cells[j] = tmp
        }
        var b = 0L
        var mask = 0
        for (v in 1..n) {
            val idx = cells[v - 1]
            b = withNibble(b, idx, v)
            mask = mask or (1 shl idx)
        }
        board = b
        shownMask = mask
        phaseShow = true
        revealPhase = false
        roundCompleted = false
        wrongCells = emptySet()
        nextExpected = 1
        // Fixed schedule: rounds start at their cumulative slot, never early,
        // never late — the class stays in lockstep through all 4 rounds.
        viewEndAtMs = roundStart + viewWindowMs(n)
        revealStartAtMs = roundStart + viewWindowMs(n) + tapWindowMs(n) - REVEAL_MS
        roundEndAtMs = roundStart + viewWindowMs(n) + tapWindowMs(n)
    }

    LaunchedEffect(seed, startDelayMs) {
        // Pre-start hold: tick the visible countdown down before round 1 begins
        while (preStartLeftMs > 0L) {
            val step = minOf(200L, preStartLeftMs)
            delay(step)
            preStartLeftMs -= step
        }
        newRound(1, System.currentTimeMillis())
        while (running) {
            val now = System.currentTimeMillis()
            if (phaseShow && now >= viewEndAtMs) {
                phaseShow = false // hide numbers, recall phase
            }
            if (!phaseShow && !revealPhase && now >= revealStartAtMs) {
                revealPhase = true
            }
            if (flashCell >= 0 && now >= flashUntilMs) {
                flashCell = -1
            }
            if (now >= roundEndAtMs) {
                if (round >= 4) {
                    running = false
                    onFinished(score)
                    break
                }
                // Next round starts at its fixed slot regardless of loop jitter
                newRound(round + 1, roundEndAtMs)
            }
            delay(30)
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            for (row in 0 until GRID) {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    for (col in 0 until GRID) {
                        val idx = row * GRID + col
                        val value = nibble(board, idx) // read 4-bit nibble from 64-bit board
                        val hasNumber = value != 0
                        val active = hasNumber && ((shownMask and (1 shl idx)) != 0)
                        val revealNow = roundCompleted || revealPhase
                        val reveal = phaseShow || revealNow || !active // view / reveal / already-tapped
                        val isWrong = idx in wrongCells
                        val isFlash = flashCell == idx
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .background(
                                    when {
                                        !hasNumber -> Color(0xFF0B1220)
                                        isFlash -> Color(0xFF7F1D1D)              // wrong tap flash
                                        revealNow && isWrong -> Color(0xFF7F1D1D) // wrong press shown red
                                        revealNow && hasNumber -> Color(0xFF166534) // correct positions green
                                        phaseShow && active -> Color(0xFF172554)  // number revealed (view)
                                        active && !phaseShow -> Color(0xFF243447) // hidden active cell
                                        else -> Color(0xFF111827)
                                    }
                                )
                                .border(1.dp, Color(0xFF334155))
                                .then(
                                    if (running && !phaseShow && !revealNow && active) {
                                        Modifier.clickable {
                                            if (value == nextExpected) {
                                                score += 1 // every correct tap scores
                                                shownMask = shownMask and (1 shl idx).inv() // bit clear
                                                nextExpected += 1
                                                if (nextExpected > count) {
                                                    // Round complete: completion bonus, reveal
                                                    // plays until the round's fixed end.
                                                    score += count - 3
                                                    roundCompleted = true
                                                }
                                            } else {
                                                // Wrong press: red flash, no leak of the
                                                // cell's number, round keeps running.
                                                wrongCells = wrongCells + idx
                                                flashCell = idx
                                                flashUntilMs = System.currentTimeMillis() + 250L
                                            }
                                        }
                                    } else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (hasNumber && reveal && !isFlash) {
                                Text(
                                    text = value.toString(),
                                    color = Color.White,
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = "Memorize numbers. Tap 1 → N in order.",
            color = Color(0xFFCBD5E1),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 14.dp)
        )
        if (preStartLeftMs > 0L) {
            // Synchronized start countdown — one line under the rules, never a
            // full-screen takeover. All phones flip to round 1 together.
            Text(
                text = "Everyone starts together in ${(preStartLeftMs + 999) / 1000}…",
                color = Color(0xFF93C5FD),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
