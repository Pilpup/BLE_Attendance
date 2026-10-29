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
    onProgressUpdate: (hits: Int, total: Int) -> Unit = { _, _ -> },
    onComplete: (CognitiveFocusReport) -> Unit,
    modifier: Modifier = Modifier
) {
    // Determine which game to launch
    val assignedGame = remember(seedKey, neighborIds) {
        DeterministicGameAssigner.assign(seedKey, neighborIds)
    }
    val activeGame = debugGameOverride ?: assignedGame

    Box(modifier = modifier.fillMaxSize()) {
        when (activeGame) {
                AttendanceGameType.CHIMP_MEMORY -> {
                    ChimpMemoryGame(
                        seedKey = seedKey,
                        onProgress = onProgressUpdate,
                        onComplete = { interactions ->
                            val report = GameTelemetryEngine.analyze(
                                gameType = AttendanceGameType.CHIMP_MEMORY,
                                interactions = interactions,
                                totalTrials = 7
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
                                totalTrials = 8
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
                                totalTrials = 6
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
                                totalTrials = 7
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
                                totalTrials = 6
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
                                totalTrials = 5
                            )
                            onComplete(report)
                        }
                    )
                }
            }
        }
}
