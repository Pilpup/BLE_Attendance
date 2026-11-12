package com.college.feature.attendance.game

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
    // instant (teacher countdown beacon). The grid stays empty until then.
    startDelayMs: Long = 0L,
    onFinished: (score: Int) -> Unit = {},
) {
    val rng = remember(seed) { Random(seed) }

    var board by remember { mutableLongStateOf(0L) }          // 64-bit board (nibbles)
    var shownMask by remember { mutableIntStateOf(0) }        // 16-bit mask: untapped number cells
    var tappedMask by remember { mutableIntStateOf(0) }       // 16-bit mask: correctly tapped cells (green)
    var wrongCell by remember { mutableIntStateOf(-1) }       // the mistaken cell (red, round over)
    var nextExpected by remember { mutableIntStateOf(1) }
    var count by remember { mutableIntStateOf(5) }
    var round by remember { mutableIntStateOf(1) }
    var score by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(true) }
    var preStartLeftMs by remember { mutableLongStateOf(startDelayMs) }

    // Round lifecycle: view window shows the numbers, tap window hides them.
    // roundOver = the student is done for THIS round (completed all taps, made
    // one mistake, or the window ran out) — no further clicks until the next
    // round's fixed slot. Mistakes never advance the sequence: brute-forcing
    // by tapping every cell is dead. Tapped cells reveal their number (green
    // = correct, red = the mistaken one) only after being pressed.
    var phaseShow by remember { mutableStateOf(true) }
    var roundOver by remember { mutableStateOf(false) }
    var viewEndAtMs by remember { mutableLongStateOf(0L) }
    var roundEndAtMs by remember { mutableLongStateOf(0L) }

    // Animated dots for the pre-start countdown ("Starting in 7...")
    val dotTransition = rememberInfiniteTransition(label = "start_dots")
    val dotPhase by dotTransition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "start_dots_phase"
    )
    val startDots = ".".repeat(((dotPhase.toInt() % 3) + 1))

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
        tappedMask = 0
        wrongCell = -1
        phaseShow = true
        roundOver = false
        nextExpected = 1
        // Fixed schedule: rounds start at their cumulative slot, never early,
        // never late — the class stays in lockstep through all 4 rounds.
        viewEndAtMs = roundStart + viewWindowMs(n)
        roundEndAtMs = roundStart + viewWindowMs(n) + tapWindowMs(n)
    }

    LaunchedEffect(seed, startDelayMs) {
        // Pre-start hold: the visible countdown ticks down before round 1
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

    // Board centered vertically on the screen; the pre-start countdown sits in
    // a fixed slot ABOVE the board so the grid never shifts when it ends.
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp),
            contentAlignment = Alignment.Center
        ) {
            if (preStartLeftMs > 0L) {
                Text(
                    text = "Starting in ${(preStartLeftMs + 999) / 1000}$startDots",
                    color = Color(0xFF93C5FD),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
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
                            val isTapped = (tappedMask and (1 shl idx)) != 0
                            val isWrong = wrongCell == idx
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .background(
                                        when {
                                            !hasNumber -> Color(0xFF0B1220)
                                            isWrong -> Color(0xFF7F1D1D)              // mistaken cell red
                                            isTapped -> Color(0xFF166534)             // correct tap green
                                            phaseShow && active -> Color(0xFF172554)  // number shown (view)
                                            active && !phaseShow -> Color(0xFF243447) // hidden number cell
                                            else -> Color(0xFF111827)
                                        }
                                    )
                                    .border(1.dp, Color(0xFF334155))
                                    .then(
                                        if (running && !phaseShow && !roundOver && active) {
                                            Modifier.clickable {
                                                if (value == nextExpected) {
                                                    score += 1 // every correct tap scores
                                                    tappedMask = tappedMask or (1 shl idx) // green reveal
                                                    shownMask = shownMask and (1 shl idx).inv() // bit clear
                                                    nextExpected += 1
                                                    if (nextExpected > count) {
                                                        // Round complete.
                                                        roundOver = true
                                                    }
                                                } else {
                                                    // One mistake ends the round: the mistaken
                                                    // cell turns red and reveals its number —
                                                    // no more clicks until the next round.
                                                    wrongCell = idx
                                                    roundOver = true
                                                }
                                            }
                                        } else Modifier
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (hasNumber && (phaseShow || isTapped || isWrong)) {
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
        }
    }
}
