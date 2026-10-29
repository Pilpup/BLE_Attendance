package com.college.feature.attendance.game

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Random
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * Game #2: Stroop Match Rush
 * Rapid semantic conflict test:
 * A color word appears in a colored font.
 * Rule: Swipe RIGHT if the text matches the font color. Swipe LEFT if different.
 *
 * Saccadic cross-talk between phones causes prefrontal conflict resolution breakdown.
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

    // Rich vibrant colors for high-conflict Stroop testing
    val colorTokens = remember {
        listOf(
            "RED" to Color(0xFFFF1744),
            "BLUE" to Color(0xFF00E5FF),
            "GREEN" to Color(0xFF00F5A0),
            "AMBER" to Color(0xFFFF9100),
            "PURPLE" to Color(0xFFD500F9),
            "YELLOW" to Color(0xFFFFEA00),
            "PINK" to Color(0xFFFF4081)
        )
    }

    // Dynamic background palettes that shift on every card to confuse the user
    val bgPalettes = remember {
        listOf(
            listOf(Color(0xFF22050B), Color(0xFF0D0204), Color(0xFF190308)), // Crimson hue
            listOf(Color(0xFF021622), Color(0xFF010A10), Color(0xFF041E2E)), // Deep Cyan hue
            listOf(Color(0xFF051C0F), Color(0xFF020E07), Color(0xFF082614)), // Forest Emerald hue
            listOf(Color(0xFF221602), Color(0xFF0E0901), Color(0xFF2A1C03)), // Deep Amber hue
            listOf(Color(0xFF1C0324), Color(0xFF0C0110), Color(0xFF260430)), // Deep Purple hue
            listOf(Color(0xFF220316), Color(0xFF0E0109), Color(0xFF2D041D)), // Deep Magenta/Pink hue
            listOf(Color(0xFF201E03), Color(0xFF0F0E01), Color(0xFF2B2804))  // Deep Gold hue
        )
    }

    // Generate 12 procedural trials from seedKey (15 seconds total session)
    data class StroopCard(val text: String, val textColor: Color, val isMatch: Boolean)
    val cards = remember(seedKey) {
        val seed = seedKey.hashCode().toLong().absoluteValue
        val rng = Random(seed)
        val list = mutableListOf<StroopCard>()
        for (i in 0 until 12) {
            val shouldMatch = rng.nextBoolean()
            val textIdx = rng.nextInt(colorTokens.size)
            val colorIdx = if (shouldMatch) textIdx else {
                var c = rng.nextInt(colorTokens.size)
                while (c == textIdx) c = rng.nextInt(colorTokens.size)
                c
            }
            list.add(
                StroopCard(
                    text = colorTokens[textIdx].first,
                    textColor = colorTokens[colorIdx].second,
                    isMatch = shouldMatch
                )
            )
        }
        list
    }

    var currentCardIndex by remember { mutableIntStateOf(0) }
    var cardSpawnTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val interactions = remember { mutableStateListOf<GameInteractionRecord>() }
    var isTransitioning by remember { mutableStateOf(false) }

    // Card drag offset for fluid swipe feel
    val cardOffsetX = remember { Animatable(0f) }

    // Dynamic background colors that interpolate on card transitions
    val currentBg = bgPalettes[currentCardIndex % bgPalettes.size]
    val bgAnimColor1 by animateColorAsState(targetValue = currentBg[0], animationSpec = tween(400), label = "bgTop")
    val bgAnimColor2 by animateColorAsState(targetValue = currentBg[1], animationSpec = tween(400), label = "bgMid")
    val bgAnimColor3 by animateColorAsState(targetValue = currentBg[2], animationSpec = tween(400), label = "bgBottom")

    fun processSwipe(directionRight: Boolean) {
        if (isTransitioning || currentCardIndex >= cards.size) return
        isTransitioning = true

        val card = cards[currentCardIndex]
        val now = System.currentTimeMillis()
        val latency = now - cardSpawnTimestamp
        val userSaidMatch = directionRight
        val isCorrect = (userSaidMatch == card.isMatch)

        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        interactions.add(
            GameInteractionRecord(
                trialIndex = currentCardIndex,
                latencyMs = latency,
                isCorrect = isCorrect,
                stimulusKey = "STROOP_${card.text}_MATCH_${card.isMatch}"
            )
        )

        val hits = interactions.count { it.isCorrect }
        onProgress(hits, cards.size)

        scope.launch {
            // Animate card flick off-screen
            val targetOffset = if (directionRight) 1200f else -1200f
            cardOffsetX.animateTo(targetOffset, tween(160))

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

    // Auto-advance on 1250ms timeout if player hesitated too long (12 cards * 1.25s = 15s session)
    LaunchedEffect(currentCardIndex) {
        cardSpawnTimestamp = System.currentTimeMillis()
        delay(1250L)
        if (!isTransitioning && currentCardIndex < cards.size) {
            // Timeout registered as miss
            interactions.add(
                GameInteractionRecord(
                    trialIndex = currentCardIndex,
                    latencyMs = 1250L,
                    isCorrect = false,
                    stimulusKey = "STROOP_TIMEOUT"
                )
            )
            val hits = interactions.count { it.isCorrect }
            onProgress(hits, cards.size)

            if (currentCardIndex + 1 < cards.size) {
                currentCardIndex++
            } else {
                onComplete(interactions.toList())
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(bgAnimColor1, bgAnimColor2, bgAnimColor3)
                )
            )
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
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

            // Central Interactive Card
            if (currentCardIndex < cards.size) {
                val currentCard = cards[currentCardIndex]
                val currentOffset = cardOffsetX.value

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFF0B1712),
                    border = androidx.compose.foundation.BorderStroke(
                        2.dp,
                        when {
                            currentOffset > 80f -> TsodingOrbRenderer.EmeraldGreen
                            currentOffset < -80f -> TsodingOrbRenderer.SolarOrange
                            else -> Color(0x3300F5A0)
                        }
                    ),
                    modifier = Modifier
                        .size(width = 280.dp, height = 220.dp)
                        .offset { IntOffset(currentOffset.roundToInt(), 0) }
                        .rotate(currentOffset * 0.04f)
                        .draggable(
                            state = rememberDraggableState { delta ->
                                scope.launch {
                                    cardOffsetX.snapTo(cardOffsetX.value + delta)
                                }
                            },
                            orientation = Orientation.Horizontal,
                            onDragStopped = {
                                if (cardOffsetX.value > 120f) {
                                    processSwipe(directionRight = true)
                                } else if (cardOffsetX.value < -120f) {
                                    processSwipe(directionRight = false)
                                } else {
                                    cardOffsetX.animateTo(0f, tween(150))
                                }
                            }
                        )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = currentCard.text,
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp,
                            color = currentCard.textColor
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(220.dp))
            }

            // Bottom Directional Action Guides (Tap or Swipe)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Guide (NO MATCH)
                Surface(
                    onClick = { processSwipe(directionRight = false) },
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0x15FF9100),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x44FF9100)),
                    modifier = Modifier.size(width = 130.dp, height = 54.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = TsodingOrbRenderer.SolarOrange,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = " NO MATCH",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TsodingOrbRenderer.SolarOrange
                        )
                    }
                }

                // Right Guide (MATCH)
                Surface(
                    onClick = { processSwipe(directionRight = true) },
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0x1500F5A0),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x4400F5A0)),
                    modifier = Modifier.size(width = 130.dp, height = 54.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "MATCH ",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TsodingOrbRenderer.EmeraldGreen
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = TsodingOrbRenderer.EmeraldGreen,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
