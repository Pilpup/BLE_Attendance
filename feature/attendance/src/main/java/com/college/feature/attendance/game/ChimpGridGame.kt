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

// Dynamic view window: scales with count.
private fun viewWindowMs(n: Int): Long = (400L + n * 450L)

@Composable
fun ChimpGridGame(
    modifier: Modifier = Modifier,
    seed: Int = 0,
    durationMs: Long = 25_000L,
    onFinished: (score: Int) -> Unit = {},
) {
    val rng = remember(seed) { Random(seed) }

    var board by remember { mutableLongStateOf(0L) }          // 64-bit board (nibbles)
    var shownMask by remember { mutableIntStateOf(0) }        // 16-bit mask: visible number cells
    var phaseShow by remember { mutableStateOf(true) }        // true=view window, false=recall
    var nextExpected by remember { mutableIntStateOf(1) }
    var count by remember { mutableIntStateOf(5) }
    var round by remember { mutableIntStateOf(1) }
    var score by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(true) }
    var hideAtMs by remember { mutableLongStateOf(0L) }
    var tapUntilMs by remember { mutableLongStateOf(0L) }
    var timeUp by remember { mutableStateOf(false) }

    fun newRound(r: Int, now: Long) {
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
        nextExpected = 1
        hideAtMs = now + viewWindowMs(n)
        tapUntilMs = hideAtMs + 6000L
    }

    LaunchedEffect(seed) {
        val endAt = System.currentTimeMillis() + durationMs
        var r = 1
        newRound(r, System.currentTimeMillis())
        while (running) {
            val now = System.currentTimeMillis()
            if (!timeUp && now >= endAt) {
                timeUp = true // let current round finish, do not close mid-round
            }
            if (phaseShow && now >= hideAtMs) {
                phaseShow = false // hide numbers, recall phase
            }
            if (!phaseShow && now >= tapUntilMs) {
                if (timeUp) {
                    running = false
                    onFinished(score)
                    break
                }
                r += 1
                newRound(r, System.currentTimeMillis())
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
                        val reveal = phaseShow || !active // show during view window, hide active during recall
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .background(
                                    when {
                                        !hasNumber -> Color(0xFF0B1220)
                                        active && !phaseShow -> Color(0xFF243447) // hidden active cell
                                        phaseShow && active -> Color(0xFF172554)  // number revealed
                                        else -> Color(0xFF111827)
                                    }
                                )
                                .border(1.dp, Color(0xFF334155))
                                .then(
                                    if (running && !phaseShow && active) {
                                        Modifier.clickable {
                                            if (value == nextExpected) {
                                                val gain = when (value) {
                                                    count - 3 -> 1          // combined for 1..(n-3)
                                                    count - 2 -> 1
                                                    count - 1 -> 1
                                                    count -> count - 3      // final number bonus
                                                    else -> 0
                                                }
                                                score += gain
                                                shownMask = shownMask and (1 shl idx).inv() // bit clear
                                                nextExpected += 1
                                                if (nextExpected > count) {
                                                    if (timeUp) {
                                                        running = false
                                                        onFinished(score)
                                                    } else {
                                                        val r = round + 1
                                                        newRound(r, System.currentTimeMillis())
                                                    }
                                                }
                                            } else {
                                                // wrong tap -> end round (or finish if time up)
                                                if (timeUp) {
                                                    running = false
                                                    onFinished(score)
                                                } else {
                                                    val r = round + 1
                                                    newRound(r, System.currentTimeMillis())
                                                }
                                            }
                                        }
                                    } else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (hasNumber && reveal) {
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
    }
}
