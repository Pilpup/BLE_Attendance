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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radar
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
import androidx.compose.ui.draw.clip
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
import kotlin.math.sqrt

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

data class ColorOption(
    val name: String,
    val color: Color
)

/**
 * Solution 1: 4-Way Color Palette Stroop Match Game
 * - 50% match (congruent: word text == ink color) and 50% mismatch (incongruent: word text != ink color)
 * - 4 distinct color choices per trial in a 2x2 grid (1 correct ink color + 3 distractors)
 * - Pure spamming yields only 25% expected score (3/12), completely destroying blind-clicking exploits!
 * - Supports direct tap and 4-way quadrant flick gestures.
 * - Tetris 12-Bag guarantees zero color repetitions.
 * - High-Proximity Defense Mode reduces select window to 1100ms when ultra-close peers are detected.
 */
@Composable
fun StroopMatchGame(
    seedKey: String,
    onProgress: (hitCount: Int, totalTrials: Int) -> Unit,
    onComplete: (interactions: List<GameInteractionRecord>) -> Unit,
    isUltraCloseProximity: Boolean = false,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Select window: 2000ms normal mode, 1100ms in ultra-close proximity defense mode
    val selectWindowMs = if (isUltraCloseProximity) 1100L else 2000L

    // 12 distinct, high-contrast basic colors
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

    // Dynamic background palettes that animate on card transitions to confuse peripheral vision
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
        val inkColorName: String,
        val inkColor: Color,
        val isMatch: Boolean,
        val options: List<ColorOption>,
        val correctOptionIndex: Int,
        val fontSize: Int,
        val fontFamily: FontFamily,
        val fontWeight: FontWeight,
        val letterSpacing: Int,
        val rotationTilt: Float,
        val textRelOffsetX: Dp,
        val textRelOffsetY: Dp
    )

    // Tetris Bag Pre-Shuffled Generation (computed once, played sequentially from array)
    val cards = remember(seedKey, isUltraCloseProximity) {
        val seed = seedKey.hashCode().toLong().absoluteValue
        val rng = Random(seed)

        // 1. Color Bag: shuffle all 12 basic colors once (each color is used as primary text)
        val colorIndices = (0 until colorTokens.size).toMutableList()
        colorIndices.shuffle(rng)

        // 2. Exactly 50% match (6 congruent) and 50% mismatch (6 incongruent)
        val matchPlan = mutableListOf(
            true, true, true, true, true, true,
            false, false, false, false, false, false
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
            val textIdx = colorIndices[i] // Word color name
            val baseName = colorTokens[textIdx].first

            val inkIdx = if (shouldMatch) {
                textIdx // Congruent: ink matches word
            } else {
                // Incongruent: ink conflicts with word
                var c = rng.nextInt(colorTokens.size)
                while (c == textIdx) c = rng.nextInt(colorTokens.size)
                c
            }

            val inkColorName = colorTokens[inkIdx].first
            val inkColor = colorTokens[inkIdx].second
            val weirdWord = weirdifyCase(baseName, rng)

            // Generate 4 distinct options:
            // 1. Correct ink color
            // 2. If incongruent, include the distractor word text color (maximum cognitive Stroop trap!)
            // 3. Other distractor colors to make 4
            val optionIndices = mutableListOf<Int>()
            optionIndices.add(inkIdx)
            if (!shouldMatch) {
                optionIndices.add(textIdx)
            }

            val remainingPool = (0 until colorTokens.size).filter { it !in optionIndices }.toMutableList()
            remainingPool.shuffle(rng)
            while (optionIndices.size < 4) {
                optionIndices.add(remainingPool.removeAt(0))
            }

            optionIndices.shuffle(rng)
            val correctOptionIndex = optionIndices.indexOf(inkIdx)
            val options = optionIndices.map { idx ->
                ColorOption(name = colorTokens[idx].first, color = colorTokens[idx].second)
            }

            list.add(
                StroopCard(
                    displayText = weirdWord,
                    baseText = baseName,
                    inkColorName = inkColorName,
                    inkColor = inkColor,
                    isMatch = shouldMatch,
                    options = options,
                    correctOptionIndex = correctOptionIndex,
                    fontSize = 44 + rng.nextInt(14), // 44.sp to 58.sp
                    fontFamily = fontFamilies[rng.nextInt(fontFamilies.size)],
                    fontWeight = fontWeights[rng.nextInt(fontWeights.size)],
                    letterSpacing = 1 + rng.nextInt(5), // 1.sp to 5.sp
                    rotationTilt = (rng.nextFloat() * 12f - 6f), // -6° to +6°
                    textRelOffsetX = (rng.nextInt(111) - 55).dp, // -55dp to +55dp
                    textRelOffsetY = (rng.nextInt(101) - 50).dp  // -50dp to +50dp
                )
            )
        }
        list
    }

    var currentCardIndex by remember { mutableIntStateOf(0) }
    var cardSpawnTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val interactions = remember { mutableStateListOf<GameInteractionRecord>() }
    var isTransitioning by remember { mutableStateOf(false) }

    // Card drag offsets for 2D fluid swipe feel
    val cardOffsetX = remember { Animatable(0f) }
    val cardOffsetY = remember { Animatable(0f) }

    // Dynamic background colors that interpolate on card transitions
    val currentBg = bgPalettes[currentCardIndex % bgPalettes.size]
    val bgAnimColor1 by animateColorAsState(targetValue = currentBg[0], animationSpec = tween(400), label = "bgTop")
    val bgAnimColor2 by animateColorAsState(targetValue = currentBg[1], animationSpec = tween(400), label = "bgMid")
    val bgAnimColor3 by animateColorAsState(targetValue = currentBg[2], animationSpec = tween(400), label = "bgBottom")

    fun processChoice(chosenOptionIndex: Int, flingVector: Pair<Float, Float>) {
        if (isTransitioning || currentCardIndex >= cards.size) return
        isTransitioning = true

        val card = cards[currentCardIndex]
        val now = System.currentTimeMillis()
        val latency = now - cardSpawnTimestamp
        val isCorrect = (chosenOptionIndex == card.correctOptionIndex)

        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        interactions.add(
            GameInteractionRecord(
                trialIndex = currentCardIndex,
                latencyMs = latency,
                isCorrect = isCorrect,
                stimulusKey = "STROOP_${card.baseText}_INK_${card.inkColorName}"
            )
        )

        val hits = interactions.count { it.isCorrect }
        onProgress(hits, cards.size)

        scope.launch {
            // Animate word flick off-screen towards the chosen option quadrant
            cardOffsetX.animateTo(flingVector.first, tween(160))
            cardOffsetY.animateTo(flingVector.second, tween(160))

            if (currentCardIndex + 1 < cards.size) {
                currentCardIndex++
                cardOffsetX.snapTo(0f)
                cardOffsetY.snapTo(0f)
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

    // Auto-advance on selectWindowMs timeout if player hesitated too long
    LaunchedEffect(currentCardIndex) {
        cardSpawnTimestamp = System.currentTimeMillis()
        cardProgress.snapTo(1f)
        launch {
            cardProgress.animateTo(0f, tween(selectWindowMs.toInt()))
        }
        delay(selectWindowMs)
        if (!isTransitioning && currentCardIndex < cards.size) {
            interactions.add(
                GameInteractionRecord(
                    trialIndex = currentCardIndex,
                    latencyMs = selectWindowMs,
                    isCorrect = false,
                    stimulusKey = "STROOP_TIMEOUT"
                )
            )
            val hits = interactions.count { it.isCorrect }
            onProgress(hits, cards.size)

            if (currentCardIndex + 1 < cards.size) {
                currentCardIndex++
                cardOffsetX.snapTo(0f)
                cardOffsetY.snapTo(0f)
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

                if (isUltraCloseProximity) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0x33FF9100),
                        border = BorderStroke(1.dp, TsodingOrbRenderer.SolarOrange)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Radar,
                                contentDescription = null,
                                tint = TsodingOrbRenderer.SolarOrange,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "DENSE RADAR",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp,
                                color = TsodingOrbRenderer.SolarOrange
                            )
                        }
                    }
                }

                Text(
                    text = "WHAT COLOR IS THE INK?",
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
                            y = (currentCard.textRelOffsetY.toPx() + cardOffsetY.value).roundToInt()
                        )
                    }
                    .rotate(currentCard.rotationTilt + cardOffsetX.value * 0.04f)
                    .pointerInput(currentCardIndex) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                scope.launch {
                                    cardOffsetX.snapTo(cardOffsetX.value + dragAmount.x)
                                    cardOffsetY.snapTo(cardOffsetY.value + dragAmount.y)
                                }
                            },
                            onDragEnd = {
                                val dx = cardOffsetX.value
                                val dy = cardOffsetY.value
                                val dist = sqrt(dx * dx + dy * dy)
                                if (dist > 45f) {
                                    // 4-Quadrant flick selection:
                                    // dx < 0 && dy < 0 -> Top-Left (Option 0)
                                    // dx >= 0 && dy < 0 -> Top-Right (Option 1)
                                    // dx < 0 && dy >= 0 -> Bottom-Left (Option 2)
                                    // dx >= 0 && dy >= 0 -> Bottom-Right (Option 3)
                                    val (chosenIdx, flingVec) = when {
                                        dx < 0 && dy < 0 -> 0 to (-1100f to -900f)
                                        dx >= 0 && dy < 0 -> 1 to (1100f to -900f)
                                        dx < 0 && dy >= 0 -> 2 to (-1100f to 900f)
                                        else -> 3 to (1100f to 900f)
                                    }
                                    processChoice(chosenOptionIndex = chosenIdx, flingVector = flingVec)
                                } else {
                                    scope.launch {
                                        cardOffsetX.animateTo(0f, tween(150))
                                        cardOffsetY.animateTo(0f, tween(150))
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
                    color = currentCard.inkColor
                )
            }

            // Solution 1: 4-Way Color Target Buttons (2x2 Grid)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Row 1: Option 0 (Top-Left) and Option 1 (Top-Right)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ColorOptionButton(
                        option = currentCard.options[0],
                        modifier = Modifier.weight(1f),
                        onClick = {
                            processChoice(chosenOptionIndex = 0, flingVector = -1100f to -900f)
                        }
                    )
                    ColorOptionButton(
                        option = currentCard.options[1],
                        modifier = Modifier.weight(1f),
                        onClick = {
                            processChoice(chosenOptionIndex = 1, flingVector = 1100f to -900f)
                        }
                    )
                }

                // Row 2: Option 2 (Bottom-Left) and Option 3 (Bottom-Right)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ColorOptionButton(
                        option = currentCard.options[2],
                        modifier = Modifier.weight(1f),
                        onClick = {
                            processChoice(chosenOptionIndex = 2, flingVector = -1100f to 900f)
                        }
                    )
                    ColorOptionButton(
                        option = currentCard.options[3],
                        modifier = Modifier.weight(1f),
                        onClick = {
                            processChoice(chosenOptionIndex = 3, flingVector = 1100f to 900f)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorOptionButton(
    option: ColorOption,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = option.color.copy(alpha = 0.16f),
        border = BorderStroke(1.5.dp, option.color.copy(alpha = 0.65f)),
        modifier = modifier.height(52.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(option.color)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = option.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                color = option.color
            )
        }
    }
}
