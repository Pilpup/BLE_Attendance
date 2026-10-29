package com.college.feature.attendance.game

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.college.feature.attendance.BuildConfig
import com.college.feature.attendance.telemetry.CognitiveFocusReport

/**
 * Host container for the 6 cognitive attendance games.
 * Supports deterministic BLE mesh assignment and on-the-fly debug game switching.
 */
@Composable
fun AttendanceGameContainer(
    seedKey: String,
    neighborIds: List<String> = emptyList(),
    debugGameOverride: AttendanceGameType? = null,
    onDebugGameChange: (AttendanceGameType?) -> Unit = {},
    onProgressUpdate: (hits: Int, total: Int) -> Unit = { _, _ -> },
    onComplete: (CognitiveFocusReport) -> Unit,
    modifier: Modifier = Modifier
) {
    // Determine which game to launch
    val assignedGame = remember(seedKey, neighborIds) {
        DeterministicGameAssigner.assign(seedKey, neighborIds)
    }
    val activeGame = debugGameOverride ?: assignedGame

    Column(modifier = modifier.fillMaxSize()) {
        // ── DEBUG GAME SELECTOR ROW (Only visible in Debug Builds) ──
        if (BuildConfig.DEBUG) {
            Surface(
                color = Color(0xFF07120C),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "DEBUG:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF38BDF8)
                    )

                    // Auto (Mesh assigned) pill
                    val isAuto = (debugGameOverride == null)
                    Surface(
                        onClick = { onDebugGameChange(null) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isAuto) Color(0xFF00F5A0) else Color(0x22FFFFFF)
                    ) {
                        Text(
                            text = "AUTO (${assignedGame.displayName})",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isAuto) Color.Black else Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    // 6 Game Archetype pills
                    AttendanceGameType.entries.forEach { game ->
                        val isSelected = (debugGameOverride == game)
                        Surface(
                            onClick = { onDebugGameChange(game) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) Color(0xFF00E5FF) else Color(0x18FFFFFF)
                        ) {
                            Text(
                                text = "${game.id + 1}: ${game.displayName}",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color.Black else Color(0xCCFFFFFF),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }

        // ── ACTIVE GAME VIEWPORT ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when (activeGame) {
                AttendanceGameType.CHIMP_MEMORY -> {
                    ChimpMemoryGame(
                        seedKey = seedKey,
                        onProgress = onProgressUpdate,
                        onComplete = { interactions ->
                            val report = GameTelemetryEngine.analyze(
                                gameType = AttendanceGameType.CHIMP_MEMORY,
                                interactions = interactions,
                                totalTrials = 3
                            )
                            onComplete(report)
                        }
                    )
                }

                AttendanceGameType.STROOP_MATCH -> {
                    StroopMatchGame(
                        seedKey = seedKey,
                        onProgress = onProgressUpdate,
                        onComplete = { interactions ->
                            val report = GameTelemetryEngine.analyze(
                                gameType = AttendanceGameType.STROOP_MATCH,
                                interactions = interactions,
                                totalTrials = 3
                            )
                            onComplete(report)
                        }
                    )
                }

                AttendanceGameType.SUBITIZING_RUSH -> {
                    SubitizingRushGame(
                        seedKey = seedKey,
                        onProgress = onProgressUpdate,
                        onComplete = { interactions ->
                            val report = GameTelemetryEngine.analyze(
                                gameType = AttendanceGameType.SUBITIZING_RUSH,
                                interactions = interactions,
                                totalTrials = 2
                            )
                            onComplete(report)
                        }
                    )
                }

                AttendanceGameType.ORB_VECTOR_FLICK -> {
                    OrbVectorFlickGame(
                        seedKey = seedKey,
                        onProgress = onProgressUpdate,
                        onComplete = { interactions ->
                            val report = GameTelemetryEngine.analyze(
                                gameType = AttendanceGameType.ORB_VECTOR_FLICK,
                                interactions = interactions,
                                totalTrials = 3
                            )
                            onComplete(report)
                        }
                    )
                }

                AttendanceGameType.CHUBB_CONTRAST -> {
                    ChubbContrastGame(
                        seedKey = seedKey,
                        onProgress = onProgressUpdate,
                        onComplete = { interactions ->
                            val report = GameTelemetryEngine.analyze(
                                gameType = AttendanceGameType.CHUBB_CONTRAST,
                                interactions = interactions,
                                totalTrials = 2
                            )
                            onComplete(report)
                        }
                    )
                }

                AttendanceGameType.POPOUT_ANOMALY -> {
                    PopOutAnomalyGame(
                        seedKey = seedKey,
                        onProgress = onProgressUpdate,
                        onComplete = { interactions ->
                            val report = GameTelemetryEngine.analyze(
                                gameType = AttendanceGameType.POPOUT_ANOMALY,
                                interactions = interactions,
                                totalTrials = 2
                            )
                            onComplete(report)
                        }
                    )
                }
            }
        }
    }
}
