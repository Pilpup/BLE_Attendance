package com.college.feature.attendance.game

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Random
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

private const val SELECT_WINDOW_MS = 2000L

private fun weirdifyCase(word: String, rng: Random): String {
    val chars = word.lowercase().toCharArray()
    var hasUpper = false
    var hasLower = false
    for (i in chars.indices) {
        if (rng.nextBoolean()) {
            chars[i] = chars[i].uppercaseChar()
            hasUpper = true
        } else {
            hasLower = true
        }
    }
    if (!hasUpper && chars.isNotEmpty()) chars[0] = chars[0].uppercaseChar()
    if (!hasLower && chars.size > 1) chars[1] = chars[1].lowercaseChar()
    return String(chars)
}

/**
 * Stroop Match Game
 * - 12 trials with 65% wrong (mismatch) and 35% right (match) ratio
 * - 12 rich basic colors
 * - Shuffled weird casing, fonts, font weights, sizes, and rotation tilts
 * - Controlled text position changes (moderate, clean roaming)
 * - Fixed MATCH (right) and MISMATCH (left) positions at the bottom
 */
@Composable
fun StroopMatchGame(
    seedKey: String,
    onProgress: (hitCount: Int, totalTrials: Int) -> Unit,
    onComplete: (interactions: List<GameInteractionRecord>) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // 1. Comprehensive basic color palette (12 distinct, high-contrast colors)
    val colorTokens = remember {
        listOf(
            "RED" to Color(0xFFFF1744),
            "BLUE" to Color(0xFF2979FF),
            "GREEN" to Color(0xFF00E676),
            "YELLOW" to Color(0xFFFFEA00),
            "ORANGE" to Color(0xFFFF6D00),
            "PURPLE" to Color(0xFFD500F9),
            "PINK" to Color(0xFFFF4081),
            "CYAN" to Color(0xFF00E5FF),
            "WHITE" to Color(0xFFFFFFFF),
            "BROWN" to Color(0xFFA1887F),
            "LIME" to Color(0xFFAEEA00),
            "GRAY" to Color(0xFFCFD8DC)
        )
    }

    // Dynamic background palettes that animate on every card to confuse peripheral vision
    val bgPalettes = remember {
        listOf(
            listOf(Color(0xFF22050B), Color(0xFF0D0204), Color(0xFF190308)), // Crimson
            listOf(Color(0xFF021622), Color(0xFF010A10), Color(0xFF041E2E)), // Cyan
            listOf(Color(0xFF051C0F), Color(0xFF020E07), Color(0xFF082614)), // Forest Emerald
            listOf(Color(0xFF221602), Color(0xFF0E0901), Color(0xFF2A1C03)), // Deep Amber
            listOf(Color(0xFF1C0324), Color(0xFF0C0110), Color(0xFF260430)), // Deep Purple
            listOf(Color(0xFF220316), Color(0xFF0E0109), Color(0xFF2D041D)), // Deep Magenta
            listOf(Color(0xFF201E03), Color(0xFF0F0E01), Color(0xFF2B2804)), // Deep Gold
            listOf(Color(0xFF071B26), Color(0xFF020E15), Color(0xFF08212E)), // Deep Navy
            listOf(Color(0xFF1A1208), Color(0xFF0D0904), Color(0xFF21170A)), // Deep Brown
            listOf(Color(0xFF121A06), Color(0xFF080D03), Color(0xFF182309))  // Deep Lime
        )
    }

    data class StroopCard(
        val displayText: String,
        val baseText: String,
        val textColor: Color,
        val isMatch: Boolean,
        val fontSize: Int,
        val fontFamily: FontFamily,
        val fontWeight: FontWeight,
        val letterSpacing: Int,
        val rotationTilt: Float,
        val textRelOffsetX: Dp,
        val textRelOffsetY: Dp
    )

    // Generate 12 procedural trials with calibrated 35% match / 65% mismatch ratio
    val cards = remember(seedKey) {
        val seed = seedKey.hashCode().toLong().absoluteValue
        val rng = Random(seed)

        // Exactly 4 matches (33.3% ~ 35%) and 8 mismatches (66.7% ~ 65%)
        val matchPlan = mutableListOf(
            true, true, true, true,
            false, false, false, false, false, false, false, false
        )
        matchPlan.shuffle(rng)

        val fontFamilies = listOf(
            FontFamily.Monospace,
            FontFamily.SansSerif,
            FontFamily.Serif,
            FontFamily.Cursive
        )

        val fontWeights = listOf(
            FontWeight.Normal,
            FontWeight.Medium,
            FontWeight.SemiBold,
            FontWeight.Bold,
            FontWeight.ExtraBold,
            FontWeight.Black
        )

        val list = mutableListOf<StroopCard>()
        for (i in 0 until 12) {
            val shouldMatch = matchPlan[i]
            val textIdx = rng.nextInt(colorTokens.size)
            val colorIdx = if (shouldMatch) textIdx else {
                var c = rng.nextInt(colorTokens.size)
                while (c == textIdx) c = rng.nextInt(colorTokens.size)
                c
            }
            val baseName = colorTokens[textIdx].first
            val textColor = colorTokens[colorIdx].second
            val weirdWord = weirdifyCase(baseName, rng)

            list.add(
                StroopCard(
                    displayText = weirdWord,
                    baseText = baseName,
                    textColor = textColor,
                    isMatch = shouldMatch,
                    fontSize = 42 + rng.nextInt(15), // 42.sp to 56.sp
                    fontFamily = fontFamilies[rng.nextInt(fontFamilies.size)],
                    fontWeight = fontWeights[rng.nextInt(fontWeights.size)],
                    letterSpacing = 1 + rng.nextInt(5), // 1.sp to 5.sp
                    rotationTilt = (rng.nextFloat() * 12f - 6f), // -6° to +6°
                    // Controlled, moderate text shift (noticeable, but not excessive)
                    textRelOffsetX = (rng.nextInt(111) - 55).dp, // -55dp to +55dp
                    textRelOffsetY = (rng.nextInt(121) - 60).dp  // -60dp to +60dp
                )
            )
        }
        list
    }

    var currentCardIndex by remember { mutableIntStateOf(0) }
    var cardSpawnTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val interactions = remember { mutableStateListOf<GameInteractionRecord>() }
    var isTransitioning by remember { mutableStateOf(false) }

    // Card drag offset for fluid horizontal swipe feel
    val cardOffsetX = remember { Animatable(0f) }

    // Dynamic background colors that interpolate on card transitions
    val currentBg = bgPalettes[currentCardIndex % bgPalettes.size]
    val bgAnimColor1 by animateColorAsState(targetValue = currentBg[0], animationSpec = tween(400), label = "bgTop")
    val bgAnimColor2 by animateColorAsState(targetValue = currentBg[1], animationSpec = tween(400), label = "bgMid")
    val bgAnimColor3 by animateColorAsState(targetValue = currentBg[2], animationSpec = tween(400), label = "bgBottom")

    fun processChoice(userSaidMatch: Boolean, flingTargetX: Float) {
        if (isTransitioning || currentCardIndex >= cards.size) return
        isTransitioning = true

        val card = cards[currentCardIndex]
        val now = System.currentTimeMillis()
        val latency = now - cardSpawnTimestamp
        val isCorrect = (userSaidMatch == card.isMatch)

        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        interactions.add(
            GameInteractionRecord(
                trialIndex = currentCardIndex,
                latencyMs = latency,
                isCorrect = isCorrect,
                stimulusKey = "STROOP_${card.baseText}_MATCH_${card.isMatch}"
            )
        )

        val hits = interactions.count { it.isCorrect }
        onProgress(hits, cards.size)

        scope.launch {
            // Animate word flick horizontally off-screen
            cardOffsetX.animateTo(flingTargetX, tween(160))

            if (currentCardIndex + 1 < cards.size) {
                currentCardIndex++
                cardOffsetX.snapTo(0f)
                cardSpawnTimestamp = System.currentTimeMillis()
                isTransitioning = false
            } else {
                delay(100L)
                onComplete(interactions.toList())
            }
        }
    }

    // Animated countdown for the select window
    val cardProgress = remember { Animatable(1f) }

    // Auto-advance on SELECT_WINDOW_MS (2000ms) timeout if player hesitated too long
    LaunchedEffect(currentCardIndex) {
        cardSpawnTimestamp = System.currentTimeMillis()
        cardProgress.snapTo(1f)
        launch {
            cardProgress.animateTo(0f, tween(SELECT_WINDOW_MS.toInt()))
        }
        delay(SELECT_WINDOW_MS)
        if (!isTransitioning && currentCardIndex < cards.size) {
            interactions.add(
                GameInteractionRecord(
                    trialIndex = currentCardIndex,
                    latencyMs = SELECT_WINDOW_MS,
                    isCorrect = false,
                    stimulusKey = "STROOP_TIMEOUT"
                )
            )
            val hits = interactions.count { it.isCorrect }
            onProgress(hits, cards.size)

            if (currentCardIndex + 1 < cards.size) {
                currentCardIndex++
                cardOffsetX.snapTo(0f)
                isTransitioning = false
            } else {
                onComplete(interactions.toList())
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(bgAnimColor1, bgAnimColor2, bgAnimColor3)
                )
            )
            .padding(16.dp)
    ) {
        // Top Indicator (Card X of 12) + Visual Select Window Progress Line
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CARD ${currentCardIndex + 1} OF ${cards.size}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = TsodingOrbRenderer.CyanPulse
                )
                Text(
                    text = "DOES WORD MATCH COLOR?",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = Color(0xAAFFFFFF)
                )
            }

            // Real-time Select Window Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0x22FFFFFF), RoundedCornerShape(2.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = cardProgress.value.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(
                            when {
                                cardProgress.value > 0.45f -> TsodingOrbRenderer.CyanPulse
                                cardProgress.value > 0.2f -> TsodingOrbRenderer.SolarOrange
                                else -> TsodingOrbRenderer.CrimsonAlert
                            },
                            RoundedCornerShape(2.dp)
                        )
                )
            }
        }

        if (currentCardIndex < cards.size) {
            val currentCard = cards[currentCardIndex]

            // Central Interactive Color Text (position shifts inside bounded rectangle + draggable)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset {
                        IntOffset(
                            x = (currentCard.textRelOffsetX.toPx() + cardOffsetX.value).roundToInt(),
                            y = currentCard.textRelOffsetY.toPx().roundToInt()
                        )
                    }
                    .rotate(currentCard.rotationTilt + cardOffsetX.value * 0.04f)
                    .pointerInput(currentCardIndex) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                scope.launch {
                                    cardOffsetX.snapTo(cardOffsetX.value + dragAmount.x)
                                }
                            },
                            onDragEnd = {
                                val dx = cardOffsetX.value
                                if (dx > 45f) {
                                    // Flicked Right -> MATCH
                                    processChoice(userSaidMatch = true, flingTargetX = 1300f)
                                } else if (dx < -45f) {
                                    // Flicked Left -> MISMATCH
                                    processChoice(userSaidMatch = false, flingTargetX = -1300f)
                                } else {
                                    scope.launch {
                                        cardOffsetX.animateTo(0f, tween(150))
                                    }
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = currentCard.displayText,
                    fontSize = currentCard.fontSize.sp,
                    fontWeight = currentCard.fontWeight,
                    fontFamily = currentCard.fontFamily,
                    letterSpacing = currentCard.letterSpacing.sp,
                    color = currentCard.textColor
                )
            }

            // Fixed Position Bottom Action Buttons: MISMATCH (Left) and MATCH (Right)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: MISMATCH (Fixed position)
                Surface(
                    onClick = { processChoice(userSaidMatch = false, flingTargetX = -1300f) },
                    shape = RoundedCornerShape(18.dp),
                    color = TsodingOrbRenderer.SolarOrange.copy(alpha = 0.16f),
                    border = BorderStroke(1.5.dp, TsodingOrbRenderer.SolarOrange.copy(alpha = 0.65f)),
                    modifier = Modifier.size(width = 150.dp, height = 54.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = TsodingOrbRenderer.SolarOrange,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "MISMATCH",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            color = TsodingOrbRenderer.SolarOrange
                        )
                    }
                }

                // Right: MATCH (Fixed position)
                Surface(
                    onClick = { processChoice(userSaidMatch = true, flingTargetX = 1300f) },
                    shape = RoundedCornerShape(18.dp),
                    color = TsodingOrbRenderer.EmeraldGreen.copy(alpha = 0.16f),
                    border = BorderStroke(1.5.dp, TsodingOrbRenderer.EmeraldGreen.copy(alpha = 0.65f)),
                    modifier = Modifier.size(width = 150.dp, height = 54.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "MATCH",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            color = TsodingOrbRenderer.EmeraldGreen
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = TsodingOrbRenderer.EmeraldGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
