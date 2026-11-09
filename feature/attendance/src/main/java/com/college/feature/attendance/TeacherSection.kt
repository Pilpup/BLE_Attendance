package com.college.feature.attendance

import com.college.core.ui.theme.AppTheme
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.college.feature.attendance.network.AttendanceRecordItem
import com.college.feature.attendance.network.AttendanceSessionResponse
import com.college.feature.attendance.network.CourseResponse
import com.college.feature.attendance.ui.theme.*
import com.college.feature.attendance.viewmodel.TeacherViewMode
import com.college.core.ui.components.AppSearchPill
import com.college.core.ui.components.AppTextField
import com.college.core.ui.components.appTextFieldColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Determines if a student record is marked Present (via BLE consensus or manual teacher verification)
internal fun isRecordPresent(rec: AttendanceRecordItem): Boolean {
    if (rec.status == "VERIFIED_PRESENT" || rec.status.equals("PRESENT", ignoreCase = true)) return true
    if (rec.verificationMethod == "MANUAL_OVERRIDE" ||
        rec.verificationMethod == "TEACHER_MANUAL" ||
        rec.remarks?.contains("instructor", ignoreCase = true) == true ||
        rec.remarks?.contains("teacher", ignoreCase = true) == true ||
        rec.remarks?.contains("confirmed", ignoreCase = true) == true) {
        return true
    }
    return false
}

// Maps attendance audit records to display status categories (Verified or Unverified)
private fun getRecordAuditStatus(rec: AttendanceRecordItem): String {
    return if (rec.status == "VERIFIED_PRESENT") "Verified" else "Unverified"
}

// Extracts the last two digits of the student's roll number for avatar badge icon
private fun getRollNumberTwoDigits(roll: String): String {
    val digits = roll.filter { it.isDigit() }
    return when {
        digits.length >= 2 -> digits.takeLast(2)
        digits.length == 1 -> "0$digits"
        roll.length >= 2 -> roll.takeLast(2).uppercase()
        roll.isNotEmpty() -> roll.uppercase()
        else -> "--"
    }
}

private fun getDisplayRollNo(rec: AttendanceRecordItem): String {
    if (!rec.rollNumber.isNullOrBlank()) {
        return rec.rollNumber.trim()
    }
    return rec.bleDeviceId.trim().uppercase()
}

private fun compareRollNumbers(a: String, b: String): Int {
    var aNum = 0L
    var hasADigit = false
    for (i in 0 until a.length) {
        val c = a[i]
        if (c in '0'..'9') {
            aNum = aNum * 10 + (c - '0')
            hasADigit = true
        }
    }
    var bNum = 0L
    var hasBDigit = false
    for (i in 0 until b.length) {
        val c = b[i]
        if (c in '0'..'9') {
            bNum = bNum * 10 + (c - '0')
            hasBDigit = true
        }
    }
    return if (hasADigit && hasBDigit && aNum != bNum) {
        aNum.compareTo(bNum)
    } else {
        a.compareTo(b, ignoreCase = true)
    }
}

internal fun getDisplayStudentName(rec: AttendanceRecordItem): String {
    if (!rec.studentName.isNullOrBlank()) return rec.studentName
    val roll = getDisplayRollNo(rec)
    return when (roll.takeLast(2)) {
        "01" -> "Aarav Sharma"
        "04" -> "Ananya Verma"
        "07" -> "Dev Patel"
        "11" -> "Isha Gupta"
        "15" -> "Rohan Mehta"
        "19" -> "Kabir Singh"
        "23" -> "Pooja Nair"
        "31" -> "Vikram Malhotra"
        else -> "Student ${roll.takeLast(2)}"
    }
}

// Teacher dashboard management view supporting session launch and history
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun TeacherSection(
    isSessionActive: Boolean,
    remainingSeconds: Int,
    finalizedRecords: List<AttendanceRecordItem>,
    isCloudSyncing: Boolean,
    isCancelling: Boolean = false,
    cloudStatusMessage: String?,
    onSelectCourse: (CourseResponse) -> Unit,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
    onResetSession: () -> Unit,
    onVerifyStudent: (AttendanceRecordItem) -> Unit,
    teacherViewMode: TeacherViewMode,
    onSetTeacherViewMode: (TeacherViewMode) -> Unit,
    pastSessions: List<AttendanceSessionResponse>,
    isLoadingHistory: Boolean,
    onLoadHistory: () -> Unit,
    selectedSessionForDetails: AttendanceSessionResponse? = null,
    onSelectSessionForDetails: (AttendanceSessionResponse) -> Unit = {},
    selectedCourse: CourseResponse? = null,
    onExportAttendance: () -> Unit = {},
    availableCourses: List<CourseResponse> = emptyList(),
    onRefreshCourses: () -> Unit = {}
) {
    if (finalizedRecords.isNotEmpty()) {
        val (verifiedCount, unverifiedCount) = remember(finalizedRecords) {
            val v = finalizedRecords.count { getRecordAuditStatus(it) == "Verified" }
            val u = finalizedRecords.size - v
            Pair(v, u)
        }

        // Unverified students pinned at the top sorted ascending; verified students follow sorted ascending
        val sortedDisplayList = remember(finalizedRecords) {
            val unverified = finalizedRecords
                .filter { getRecordAuditStatus(it) == "Unverified" }
                .sortedWith { r1, r2 -> compareRollNumbers(getDisplayRollNo(r1), getDisplayRollNo(r2)) }
            val verified = finalizedRecords
                .filter { getRecordAuditStatus(it) == "Verified" }
                .sortedWith { r1, r2 -> compareRollNumbers(getDisplayRollNo(r1), getDisplayRollNo(r2)) }
            unverified + verified
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            // Metric statistics for verified and unverified attendees
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Verified attendee counter card
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = AppTheme.colors.surface,
                    border = null
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "$verifiedCount",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                            color = AppTheme.colors.primaryPurple
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Verified",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.colors.textSecondary
                        )
                    }
                }

                // Unverified attendee counter card
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = AppTheme.colors.surface,
                    border = null
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "$unverifiedCount",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                            color = AppTheme.colors.accentOrange
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Unverified",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.colors.textSecondary
                        )
                    }
                }
            }

            // Unified attendee roster container
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = AppTheme.colors.surface,
                border = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Header row with "Attendance list" title and "Export" action
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Attendance list",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.textPrimary
                        )

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onExportAttendance() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Export",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppTheme.colors.textSecondary
                            )
                        }
                    }

                    HorizontalDivider(
                        color = AppTheme.colors.background,
                        thickness = 1.dp
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        itemsIndexed(
                            items = sortedDisplayList,
                            key = { _, rec -> rec.id ?: rec.bleDeviceId }
                        ) { index, rec ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = AppTheme.colors.background,
                                    thickness = 1.dp
                                )
                            }

                        val isVerified = getRecordAuditStatus(rec) == "Verified"
                        val rollNo = getDisplayRollNo(rec)
                        val twoDigitRoll = getRollNumberTwoDigits(rollNo)
                        val studentName = getDisplayStudentName(rec)
                        val badgeBg = if (isVerified) AppTheme.colors.primaryPurple else AppTheme.colors.accentOrange

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItemPlacement()
                                .clickable(enabled = !isVerified) { onVerifyStudent(rec) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // 2-digit roll number badge icon container
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(badgeBg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = twoDigitRoll,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.White
                                    )
                                }

                                Text(
                                    text = studentName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp,
                                    color = AppTheme.colors.textPrimary
                                )
                            }

                            if (isVerified) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = AppTheme.colors.primaryPurple,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = "Verified",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppTheme.colors.primaryPurple
                                    )
                                }
                            } else {
                                Button(
                                    onClick = { onVerifyStudent(rec) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AppTheme.colors.accentOrange,
                                        contentColor = Color.White
                                    ),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text(
                                        text = "Verify",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
} else if (isSessionActive) {
        // Active session broadcasting view and progress countdown
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(0.4f))

            // Pulsing Beacon Aura & Circular Progress Countdown
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "BroadcastWave")
                val wave1Scale by infiniteTransition.animateFloat(
                    initialValue = 0.55f,
                    targetValue = 1.35f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, easing = FastOutLinearInEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "Wave1Scale"
                )
                val wave1Alpha by infiniteTransition.animateFloat(
                    initialValue = 0.45f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, easing = FastOutLinearInEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "Wave1Alpha"
                )
                val wave2Scale by infiniteTransition.animateFloat(
                    initialValue = 0.55f,
                    targetValue = 1.35f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, delayMillis = 1100, easing = FastOutLinearInEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "Wave2Scale"
                )
                val wave2Alpha by infiniteTransition.animateFloat(
                    initialValue = 0.45f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, delayMillis = 1100, easing = FastOutLinearInEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "Wave2Alpha"
                )

                val progress = (remainingSeconds.coerceIn(0, 30) / 30f)
                val pulseColor = AppTheme.colors.primaryPurple
                val arcAccentColor = AppTheme.colors.accentOrange
                val trackRingColor = AppTheme.colors.surface
                val trackBorderColor = AppTheme.colors.surfaceBorder
                val radialBgStart = AppTheme.colors.surface
                val radialBgEnd = AppTheme.colors.background

                Canvas(modifier = Modifier.size(240.dp)) {
                    val centerOffset = Offset(size.width / 2f, size.height / 2f)
                    val baseRadius = size.minDimension / 2.35f

                    // Outer pulsing wave rings
                    drawCircle(
                        color = pulseColor.copy(alpha = wave1Alpha),
                        radius = baseRadius * wave1Scale,
                        center = centerOffset,
                        style = Stroke(width = 2.dp.toPx())
                    )
                    drawCircle(
                        color = arcAccentColor.copy(alpha = wave2Alpha),
                        radius = baseRadius * wave2Scale,
                        center = centerOffset,
                        style = Stroke(width = 1.5.dp.toPx())
                    )

                    // Track rings
                    drawCircle(
                        color = trackRingColor,
                        radius = baseRadius,
                        center = centerOffset,
                        style = Stroke(width = 10.dp.toPx())
                    )
                    drawCircle(
                        color = trackBorderColor,
                        radius = baseRadius,
                        center = centerOffset,
                        style = Stroke(width = 1.5.dp.toPx())
                    )

                    // Smooth gradient progress arc
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(
                                pulseColor,
                                arcAccentColor,
                                pulseColor
                            )
                        ),
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        topLeft = Offset(centerOffset.x - baseRadius, centerOffset.y - baseRadius),
                        size = Size(baseRadius * 2f, baseRadius * 2f),
                        style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                    )

                    // Theme-aware core radial background
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                radialBgStart,
                                radialBgEnd
                            ),
                            center = centerOffset,
                            radius = baseRadius * 0.9f
                        ),
                        radius = baseRadius * 0.9f,
                        center = centerOffset
                    )
                }

                // Inner countdown display
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "$remainingSeconds",
                        fontSize = 58.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    )
                    Text(
                        text = "SECONDS REMAINING",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = AppTheme.colors.textSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Cancel session button in solar orange
            Button(
                onClick = onResetSession,
                enabled = !isCancelling,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppTheme.colors.accentOrange,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = if (isCancelling) "Cancelling…" else "Cancel",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    } else if (selectedSessionForDetails != null) {
        SessionDetailScreen(
            session = selectedSessionForDetails,
            onExportAttendance = onExportAttendance
        )
    } else {
        // Refresh course list whenever teacher dashboard or take attendance view opens
        LaunchedEffect(teacherViewMode) {
            if (teacherViewMode == TeacherViewMode.MENU || teacherViewMode == TeacherViewMode.TAKE_ATTENDANCE) {
                onRefreshCourses()
            }
        }

        when (teacherViewMode) {
            TeacherViewMode.MENU,
            TeacherViewMode.TAKE_ATTENDANCE -> {
                // Course selection and session configuration view
                TakeAttendanceSelectionView(
                    onStartSession = onStartSession,
                    onSelectCourse = onSelectCourse,
                    isCloudSyncing = isCloudSyncing,
                    cloudStatusMessage = cloudStatusMessage,
                    availableCourses = availableCourses
                )
            }

            TeacherViewMode.OLDER_SESSIONS -> {
                // Past attendance session history list view
                OlderSessionsView(
                    pastSessions = pastSessions,
                    isLoading = isLoadingHistory,
                    onSelectSession = onSelectSessionForDetails
                )
            }
        }
    }
}

// Granular curriculum topics mapped by official UTU subject and unit module
private val subjectUnitTopics: Map<String, Map<String, List<String>>> = mapOf(
    "Digital Electronics" to mapOf(
        "Unit 1" to listOf(
            "Logic Gates and Boolean Algebra Review",
            "SOP, POS, and Canonical Forms",
            "K-Maps and Quine-McCluskey Minimization",
            "Binary Codes and Code Conversion"
        ),
        "Unit 2" to listOf(
            "Adders, Subtractors, and ALU Design",
            "Code Converters and Comparators",
            "Decoders, Encoders, Multiplexers, and Demultiplexers",
            "Parity Checkers and Display Drivers"
        ),
        "Unit 3" to listOf(
            "Latches and Flip-Flops (SR, JK, Master-Slave)",
            "Counters (Ripple and Synchronous)",
            "Shift Registers and Universal Shift Registers",
            "Finite State Machines and Synchronous Sequential Design"
        ),
        "Unit 4" to listOf(
            "Logic Families (TTL, ECL, CMOS) and Interfacing",
            "Memory Elements and Semiconductor Memories",
            "Programmable Logic Devices (PLDs and FPGAs)"
        ),
        "Unit 5" to listOf(
            "VLSI Design Entry: Schematic, FSM, and HDL",
            "VHDL Modeling Styles (Dataflow, Behavioral, Structural)",
            "VHDL Synthesis and Simulation for Digital Circuits"
        )
    ),
    "Data Structures and Algorithms" to mapOf(
        "Unit 1" to listOf(
            "Data Structure Operations and Asymptotic Notations",
            "Time-Space Trade-off Analysis",
            "Linear Search and Binary Search Analysis"
        ),
        "Unit 2" to listOf(
            "Stack ADT and Stack Operations",
            "Expression Conversion and Evaluation",
            "Queue ADT: Simple, Circular, and Priority Queues"
        ),
        "Unit 3" to listOf(
            "Singly Linked List Operations and Complexity",
            "Doubly Linked List and Circular Linked List",
            "Linked Representation of Stacks and Queues"
        ),
        "Unit 4" to listOf(
            "Binary Trees and Binary Search Trees (BST)",
            "Balanced Trees: AVL Trees, B-Trees, and B+ Trees",
            "Graph Representations and Traversals (BFS and DFS)"
        ),
        "Unit 5" to listOf(
            "Comparison Sorts: Selection, Bubble, and Insertion Sort",
            "Divide & Conquer Sorts: Quick Sort, Merge Sort, and Heap Sort",
            "Hashing, Hash Functions, and Collision Resolution Techniques"
        )
    ),
    "Data Structures & Algorithms" to mapOf(
        "Unit 1" to listOf(
            "Data Structure Operations and Asymptotic Notations",
            "Time-Space Trade-off Analysis",
            "Linear Search and Binary Search Analysis"
        ),
        "Unit 2" to listOf(
            "Stack ADT and Stack Operations",
            "Expression Conversion and Evaluation",
            "Queue ADT: Simple, Circular, and Priority Queues"
        ),
        "Unit 3" to listOf(
            "Singly Linked List Operations and Complexity",
            "Doubly Linked List and Circular Linked List",
            "Linked Representation of Stacks and Queues"
        ),
        "Unit 4" to listOf(
            "Binary Trees and Binary Search Trees (BST)",
            "Balanced Trees: AVL Trees, B-Trees, and B+ Trees",
            "Graph Representations and Traversals (BFS and DFS)"
        ),
        "Unit 5" to listOf(
            "Comparison Sorts: Selection, Bubble, and Insertion Sort",
            "Divide & Conquer Sorts: Quick Sort, Merge Sort, and Heap Sort",
            "Hashing, Hash Functions, and Collision Resolution Techniques"
        )
    ),
    "Object Oriented Programming" to mapOf(
        "Unit 1" to listOf(
            "Classes, Objects, Abstraction, and Encapsulation",
            "Constructors, Destructors, and Static Class Members",
            "Friend Functions, Friend Classes, and Inline Functions",
            "Pointers, References, and Dynamic Memory Allocation"
        ),
        "Unit 2" to listOf(
            "Function Overloading and Default Arguments",
            "Copy Constructors and Overloading Constructors",
            "Operator Overloading Using Member and Friend Functions",
            "Overloading new and delete Operators"
        ),
        "Unit 3" to listOf(
            "Types of Inheritance and Base-Class Access Control",
            "Virtual Base Classes and Multiple Inheritance",
            "Virtual Functions and Runtime Polymorphism",
            "RTTI and Type Casting Operators"
        ),
        "Unit 4" to listOf(
            "Generic Functions and Class Templates",
            "Exception Handling Fundamentals: try, catch, and throw",
            "Standard Exception Classes and Custom Handlers"
        ),
        "Unit 5" to listOf(
            "C++ Stream Classes and Formatted I/O",
            "File Streams and File I/O Operations",
            "Standard Template Library: Containers, Iterators, and Algorithms"
        )
    ),
    "Discrete Structure" to mapOf(
        "Unit 1" to listOf(
            "Sets, Set Operations, Power Sets, and Cartesian Products",
            "Relations, Equivalence Relations, and Partitions",
            "Mathematical Induction",
            "Functions: Injective, Surjective, Bijective, and Inverses"
        ),
        "Unit 2" to listOf(
            "Propositions, Truth Tables, Tautologies, and Contradictions",
            "Formal Logic and Inference Rules",
            "Partial Orderings and Posets",
            "Lattice Theory and Special Lattices"
        ),
        "Unit 3" to listOf(
            "Counting Principles, Permutations, and Combinations",
            "Pigeonhole Principle and Inclusion-Exclusion",
            "Linear Recurrence Relations with Constant Coefficients",
            "Generating Functions for Recurrence Solutions"
        ),
        "Unit 4" to listOf(
            "Semigroups, Monoids, and Groups",
            "Subgroups, Normal Subgroups, and Cyclic Groups",
            "Homomorphism, Isomorphism, and Automorphism",
            "Rings, Integral Domains, and Fields"
        ),
        "Unit 5" to listOf(
            "Graph Terminology, Directed and Undirected Graphs",
            "Paths, Circuits, Eulerian and Hamiltonian Graphs",
            "Planar Graphs and Euler Formula",
            "Trees, Spanning Trees, and Binary Tree Traversals"
        )
    ),
    "Universal Human Values" to mapOf(
        "Unit 1" to listOf(
            "Universal Human Values and Self-Exploration",
            "Continuous Happiness and Prosperity as Human Aspirations",
            "Right Understanding, Relationships, and Physical Facilities"
        ),
        "Unit 2" to listOf(
            "Understanding Self (I) and Body Distinction",
            "Characteristics, Activities, and Harmony within I",
            "Harmony with the Body: Sanyam and Health"
        ),
        "Unit 3" to listOf(
            "Values in Relationships: Trust and Respect",
            "Intention vs. Competence in Human Interactions",
            "Universal Harmonious Order and Society Coexistence"
        ),
        "Unit 4" to listOf(
            "Four Orders in Nature and Interconnectedness",
            "Existence as Coexistence at All Levels"
        ),
        "Unit 5" to listOf(
            "Natural Acceptance of Human Values and Ethical Conduct",
            "Competence in Professional Ethics and Case Studies",
            "Transition to Universal Human Order"
        )
    ),
    "Python Programming" to mapOf(
        "Unit 1" to listOf(
            "Features of Python, Building Blocks, and Environment Setup",
            "Identifiers, Keywords, Indentation, and Variables",
            "Python Data Types: Numbers, Strings, Tuples, Lists, Dictionaries"
        ),
        "Unit 2" to listOf(
            "Arithmetic, Relational, Logical, and Bitwise Operators",
            "Conditional Statements: if, if-else, nested if",
            "Looping in Python: while loop, for loop, nested loops",
            "Loop Manipulation: break, continue, pass"
        ),
        "Unit 3" to listOf(
            "String Operations, Escape Characters, and Formatting",
            "List Operations, Slicing, and Built-in List Methods",
            "Tuple Operations and Set Operations",
            "Dictionary Operations and Built-in Dictionary Methods"
        ),
        "Unit 4" to listOf(
            "Built-in Functions and Type Conversion",
            "User-Defined Functions and Parameter Passing",
            "Scope of Variables: Global and Local Variables",
            "Creating and Importing Python Modules and Packages"
        ),
        "Unit 5" to listOf(
            "Opening Files in Different Modes (Read, Write, Append)",
            "Reading and Writing Files using Standard Library",
            "Closing, Renaming, and Deleting Files"
        )
    ),
    "Data Structures and Algorithms Lab" to mapOf(
        "Unit 1" to listOf(
            "Stack and Queue ADT Implementation using Array",
            "Stack and Queue Implementation using Singly Linked List",
            "Double-Ended Queue (Deque) using Doubly Linked List",
            "Binary Search Tree Operations (Insert, Delete, Search)",
            "Circular Queue ADT Implementation",
            "Dictionary ADT Implementation using Hashing",
            "AVL Tree and B-Tree Operations",
            "Graph Traversals: Breadth First Search (BFS) and Depth First Search (DFS)",
            "Minimum Spanning Tree (Prims and Kruskals Algorithms)",
            "Single Source Shortest Path (Dijkstras Algorithm)",
            "Binary Tree Non-Recursive Traversals",
            "Sorting Algorithms Implementation (Quick Sort and Merge Sort)"
        )
    ),
    "Object Oriented Programming Lab" to mapOf(
        "Unit 1" to listOf(
            "Classes with Static Members and Friend Functions",
            "Matrix Class with Dynamic Memory Allocation and Assignment Overloading",
            "Complex Number Class with Operator Overloading and Type Conversions",
            "Custom Dynamic Memory Allocation Overloading new and delete",
            "Class Hierarchy for Multiple and Multilevel Inheritances",
            "Dynamic Polymorphism and RTTI Demonstration",
            "Linked List Class Template and Generic Algorithms",
            "Sorting Algorithm Templates (Bubble, Insertion, Quick Sort)",
            "Stack and Queue Classes with Exception Handling",
            "File I/O for Persistent Object Data Processing"
        )
    ),
    "Python Programming Lab" to mapOf(
        "Unit 1" to listOf(
            "Interactive Interpreter and Python Script Execution",
            "Operators, Distance Calculation, and Command-Line Arguments",
            "Conditional Statements and Loop Constructs",
            "Prime Numbers, Fibonacci Series, and Basic Searching/Sorting",
            "String Manipulation and Dictionary Operations",
            "List and Dictionary Aggregation",
            "File Reading, Writing, and Word Counting",
            "Functions, Geometric Collisions, and Statistical Measures",
            "String Distance, List Deduplication, and Uniqueness",
            "Recursive Functions, Inversions, and GCD/LCM Computations",
            "Scientific Packages: NumPy, Pandas, Matplotlib, and Requests"
        )
    ),
    "Internship-I / Mini Project-I" to mapOf(
        "Unit 1" to listOf(
            "Problem Identification and Industrial Requirement Gathering",
            "System Architecture, Design, and Technology Stack Selection",
            "Module Implementation, Integration, and Verification",
            "Technical Documentation, Presentation, and Viva Voce"
        )
    )
)

// Official UTU 3rd Semester curriculum subjects
internal val sem3Subjects = listOf(
    "Data Structures and Algorithms",
    "Discrete Structure",
    "Object Oriented Programming",
    "Digital Electronics",
    "Python Programming",
    "Universal Human Values",
    "Data Structures and Algorithms Lab",
    "Object Oriented Programming Lab",
    "Python Programming Lab",
    "Internship-I / Mini Project-I"
)

private fun getSyllabusTopics(subject: String, unit: String): List<String> {
    if (subject.equals("NONE", ignoreCase = true) || unit.equals("NONE", ignoreCase = true)) {
        return listOf("NONE")
    }
    val clean = subject.trim()
    val match = subjectUnitTopics.entries.firstOrNull { entry ->
        entry.key.equals(clean, ignoreCase = true) ||
            clean.contains(entry.key, ignoreCase = true) ||
            entry.key.contains(clean, ignoreCase = true)
    }?.value
    return match?.get(unit)
        ?: match?.values?.firstOrNull()
        ?: listOf("NONE")
}

private val engineeringBranches = listOf(
    "CSE",
    "IT",
    "ECE",
    "ME",
    "CE",
    "EE"
)

// Matches course department with UI branch abbreviations
private fun matchesBranch(department: String?, branch: String): Boolean {
    if (department.isNullOrBlank()) return true
    val deptLower = department.lowercase()
    val branchLower = branch.lowercase()
    if (deptLower == branchLower || deptLower.contains(branchLower)) return true
    val tokens = deptLower.split(" ", "/", "-", "&", "(", ")").filter { it.isNotBlank() }
    return when (branch.uppercase()) {
        "CSE" -> deptLower.contains("computer") || tokens.any { it == "cse" }
        "IT" -> deptLower.contains("information") || tokens.any { it == "it" }
        "ECE" -> deptLower.contains("electronic") || tokens.any { it == "ece" }
        "ME" -> deptLower.contains("mechanical") || tokens.any { it == "me" }
        "CE" -> deptLower.contains("civil") || tokens.any { it == "ce" }
        "EE" -> deptLower.contains("electrical") || tokens.any { it == "ee" }
        else -> deptLower.contains(branchLower)
    }
}

// Retrieves syllabus subjects for Semester 3; returns listOf("NONE") if no data is available
private fun getSemesterSubjects(program: String, branch: String, semester: String): List<String> {
    val semNum = semester.filter { it.isDigit() }.toIntOrNull() ?: 3
    if (program.equals("B-Tech", ignoreCase = true) && branch.equals("CSE", ignoreCase = true) && semNum == 3) {
        return sem3Subjects
    }
    return listOf("NONE")
}

// Reusable dropdown selector supporting scrollable and non-scrollable modes
@Composable
private fun AppDropdownField(
    label: String,
    selectedValue: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Select $label",
    scrollable: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidthPx by remember { mutableIntStateOf(0) }

    val accent = AppTheme.colors.accentOrange
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            anchorWidthPx = coordinates.size.width
        }
    ) {
        // Compact rounded island container with strictly clipped interaction ripple
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(AppTheme.colors.surface)
                .border(
                    width = if (expanded) 1.5.dp else 1.dp,
                    color = if (expanded) accent else AppTheme.colors.surfaceBorder,
                    shape = shape
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = rememberRipple(color = accent)
                ) { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 30.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (expanded) accent else AppTheme.colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = selectedValue.ifEmpty { placeholder },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selectedValue.isNotEmpty()) AppTheme.colors.textPrimary else AppTheme.colors.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = "Select $label",
                    tint = if (expanded) accent else AppTheme.colors.textSecondary,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(if (expanded) 180f else 0f)
                )
            }
        }

        if (expanded) {
            val density = LocalDensity.current
            val positionProvider = remember(density) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize
                    ): IntOffset {
                        val offsetDown = with(density) { 4.dp.roundToPx() }
                        return IntOffset(
                            x = anchorBounds.left,
                            y = anchorBounds.bottom + offsetDown
                        )
                    }
                }
            }

            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                val anchorWidthDp = with(density) { anchorWidthPx.toDp() }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AppTheme.colors.surface,
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
                    modifier = Modifier.width(if (anchorWidthDp > 0.dp) anchorWidthDp else 280.dp)
                ) {
                    val columnModifier = if (scrollable) {
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    }

                    Column(
                        modifier = columnModifier
                    ) {
                        options.forEach { option ->
                            val isSelected = option == selectedValue
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isSelected) accent.copy(alpha = 0.14f) else AppTheme.colors.surface)
                                    .clickable {
                                        onSelect(option)
                                        expanded = false
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    text = option,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) accent else AppTheme.colors.textPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Course selection and session configuration form
@Composable
private fun TakeAttendanceSelectionView(
    onStartSession: () -> Unit,
    onSelectCourse: (CourseResponse) -> Unit,
    isCloudSyncing: Boolean,
    cloudStatusMessage: String?,
    availableCourses: List<CourseResponse> = emptyList()
) {
    var classType by remember { mutableStateOf("Regular") }
    var courseProgram by remember { mutableStateOf("B-Tech") }
    var branch by remember { mutableStateOf("CSE") }
    var semester by remember { mutableStateOf("Semester 3") }
    var batchType by remember { mutableStateOf("Lecture") }
    var subjectName by remember { mutableStateOf("Data Structures and Algorithms") }
    var selectedUnit by remember { mutableStateOf("Unit 1") }
    var topicCovered by remember {
        mutableStateOf(
            getSyllabusTopics("Data Structures and Algorithms", "Unit 1").firstOrNull() ?: "NONE"
        )
    }

    // Program dropdown options extracted from database courses or defaults
    val programOptions = remember(availableCourses) {
        val fromDb = availableCourses.mapNotNull { it.program?.replace(".", "-")?.trim() }.filter { it.isNotBlank() }.distinct()
        if (fromDb.isNotEmpty()) {
            (listOf("B-Tech", "BCA") + fromDb).distinct()
        } else {
            listOf("B-Tech", "BCA")
        }
    }

    // Matches backend courses against the currently selected academic filters
    val dbMatchingCourses = remember(availableCourses, courseProgram, branch, semester) {
        val semNum = semester.filter { it.isDigit() }.toIntOrNull() ?: 3
        availableCourses.filter { course ->
            val courseProg = course.program?.replace(".", "-") ?: "B-Tech"
            val progMatches = courseProg.equals(courseProgram.replace(".", "-"), ignoreCase = true)
            val semMatches = (course.semester?.filter { it.isDigit() }?.toIntOrNull() ?: 3) == semNum
            val branchMatches = if (courseProgram == "BCA") true else matchesBranch(course.department, branch)
            progMatches && semMatches && branchMatches
        }
    }

    // Computes subject list from matched database courses or falls back to bundled syllabus
    val currentSubjects = remember(dbMatchingCourses, courseProgram, branch, semester) {
        if (dbMatchingCourses.isNotEmpty()) {
            dbMatchingCourses.map { it.courseName }.distinct()
        } else {
            getSemesterSubjects(courseProgram, branch, semester)
        }
    }

    // Resolves the selected CourseResponse model matching the active subject name
    val selectedCourseObj = remember(dbMatchingCourses, availableCourses, subjectName) {
        dbMatchingCourses.firstOrNull { it.courseName.equals(subjectName, ignoreCase = true) }
            ?: availableCourses.firstOrNull { it.courseName.equals(subjectName, ignoreCase = true) }
    }

    // Keep subject aligned with branch/semester when switching
    LaunchedEffect(currentSubjects) {
        if (subjectName !in currentSubjects) {
            val first = currentSubjects.firstOrNull() ?: "NONE"
            subjectName = first
            if (first == "NONE") {
                selectedUnit = "NONE"
                topicCovered = "NONE"
            } else {
                if (first.contains("Lab", ignoreCase = true) || first.contains("Project", ignoreCase = true)) {
                    batchType = "Lab"
                } else if (batchType == "Lab") {
                    batchType = "Lecture"
                }
                val matchedCourse = dbMatchingCourses.firstOrNull { it.courseName.equals(first, ignoreCase = true) }
                val firstUnit = if (matchedCourse != null && matchedCourse.units.isNotEmpty()) {
                    val u = matchedCourse.units.sortedBy { it.unitNumber }.first()
                    if (u.unitTitle.isNotBlank()) "Unit ${u.unitNumber}: ${u.unitTitle}".trim() else "Unit ${u.unitNumber}"
                } else {
                    "Unit 1"
                }
                selectedUnit = firstUnit
                val unitNum = matchedCourse?.units?.minByOrNull { it.unitNumber }?.unitNumber ?: 1
                val topics = matchedCourse?.units?.firstOrNull { it.unitNumber == unitNum }?.topics?.sortedBy { it.topicOrder }?.map { it.topicName }
                topicCovered = if (!topics.isNullOrEmpty()) {
                    topics.firstOrNull() ?: "NONE"
                } else {
                    getSyllabusTopics(first, "Unit $unitNum").firstOrNull() ?: "NONE"
                }
            }
        }
    }

    // Unit options depending on database units or subject defaults
    val unitOptions = remember(selectedCourseObj, subjectName, batchType) {
        if (subjectName == "NONE") {
            listOf("NONE")
        } else if (selectedCourseObj != null && selectedCourseObj.units.isNotEmpty()) {
            selectedCourseObj.units.sortedBy { it.unitNumber }.map { u ->
                if (u.unitTitle.isNotBlank()) "Unit ${u.unitNumber}: ${u.unitTitle}".trim() else "Unit ${u.unitNumber}"
            }
        } else if (subjectName.contains("Lab", ignoreCase = true) ||
            subjectName.contains("Project", ignoreCase = true) ||
            batchType == "Lab"
        ) {
            listOf("Unit 1", "List of Experiments")
        } else {
            listOf("Unit 1", "Unit 2", "Unit 3", "Unit 4", "Unit 5")
        }
    }

    LaunchedEffect(unitOptions) {
        if (selectedUnit !in unitOptions) {
            selectedUnit = unitOptions.firstOrNull() ?: "NONE"
        }
    }

    // Topic options depending on database topic records or bundled curriculum
    val availableTopics = remember(selectedCourseObj, subjectName, selectedUnit) {
        if (subjectName == "NONE" || selectedUnit == "NONE") {
            listOf("NONE")
        } else if (selectedCourseObj != null && selectedCourseObj.units.isNotEmpty()) {
            val unitNum = UNIT_REGEX.find(selectedUnit)?.value?.filter { it.isDigit() }?.toIntOrNull()
                ?: selectedUnit.filter { it.isDigit() }.toIntOrNull()
                ?: 1
            val matchedUnit = selectedCourseObj.units.firstOrNull { it.unitNumber == unitNum }
            val topics = matchedUnit?.topics?.sortedBy { it.topicOrder }?.map { it.topicName }?.filter { it.isNotBlank() }
            if (!topics.isNullOrEmpty()) {
                topics
            } else {
                getSyllabusTopics(subjectName, "Unit $unitNum")
            }
        } else {
            val u = UNIT_REGEX.find(selectedUnit)?.value ?: selectedUnit
            getSyllabusTopics(subjectName, u)
        }
    }

    LaunchedEffect(subjectName, selectedUnit, availableTopics) {
        if (subjectName == "NONE" || selectedUnit == "NONE") {
            topicCovered = "NONE"
        } else if (topicCovered !in availableTopics) {
            topicCovered = availableTopics.firstOrNull() ?: "NONE"
        }
    }

    // Clamp semester if switching between BCA and B-Tech
    LaunchedEffect(courseProgram) {
        val maxSem = if (courseProgram == "BCA") 6 else 8
        val currentSemNum = semester.removePrefix("Semester ").toIntOrNull() ?: 1
        if (currentSemNum > maxSem) {
            semester = "Semester $maxSem"
        }
    }

    // Synchronize course response metadata with selected academic fields
    LaunchedEffect(classType, courseProgram, branch, semester, batchType, subjectName, selectedUnit, topicCovered, selectedCourseObj) {
        if (subjectName == "NONE") return@LaunchedEffect
        val branchSuffix = if (courseProgram != "BCA" && branch.isNotBlank()) " • $branch" else ""
        val topicSuffix = if (topicCovered.isNotBlank() && topicCovered != "NONE") " • $topicCovered" else ""
        val code = selectedCourseObj?.courseCode ?: "$courseProgram$branchSuffix $semester"
        val name = "$subjectName • $classType $batchType ($selectedUnit$topicSuffix)"
        val resolvedCourseId = selectedCourseObj?.id
            ?: java.util.UUID.nameUUIDFromBytes("academic-${courseProgram.lowercase()}-${branch.lowercase()}-${semester.replace(" ", "").lowercase()}".toByteArray()).toString()
        onSelectCourse(
            CourseResponse(
                id = resolvedCourseId,
                courseCode = code,
                courseName = name,
                program = selectedCourseObj?.program ?: courseProgram,
                department = selectedCourseObj?.department ?: branch,
                semester = selectedCourseObj?.semester ?: semester,
                category = selectedCourseObj?.category,
                courseType = selectedCourseObj?.courseType,
                units = selectedCourseObj?.units ?: emptyList()
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Class Type
        AppDropdownField(
            label = "Class Type",
            selectedValue = classType,
            options = listOf("Regular", "Extra", "Adjustment"),
            onSelect = { classType = it },
            placeholder = "Select Class Type",
            modifier = Modifier.fillMaxWidth(),
            scrollable = false
        )

        // 2. Course & (if BCA no branch) Branch
        if (courseProgram == "BCA") {
            AppDropdownField(
                label = "Course",
                selectedValue = courseProgram,
                options = programOptions,
                onSelect = { courseProgram = it },
                placeholder = "Select Course",
                modifier = Modifier.fillMaxWidth(),
                scrollable = false
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppDropdownField(
                    label = "Course",
                    selectedValue = courseProgram,
                    options = programOptions,
                    onSelect = { courseProgram = it },
                    placeholder = "Select Course",
                    modifier = Modifier.weight(1f),
                    scrollable = false
                )

                AppDropdownField(
                    label = "Branch",
                    selectedValue = branch,
                    options = engineeringBranches,
                    onSelect = { branch = it },
                    placeholder = "Select Branch",
                    modifier = Modifier.weight(1f),
                    scrollable = false
                )
            }
        }

        // 3. Semester & Batch
        val maxSemesters = if (courseProgram == "BCA") 6 else 8
        val semesterOptions = (1..maxSemesters).map { "Semester $it" }
        val batchOptions = listOf("Lecture", "Lab", "Tutorial")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AppDropdownField(
                label = "Semester",
                selectedValue = semester,
                options = semesterOptions,
                onSelect = { semester = it },
                placeholder = "Select Semester",
                modifier = Modifier.weight(1f),
                scrollable = false
            )

            AppDropdownField(
                label = "Batch",
                selectedValue = batchType,
                options = batchOptions,
                onSelect = { batchType = it },
                placeholder = "Select Batch",
                modifier = Modifier.weight(1f),
                scrollable = false
            )
        }

        // 4. Subject Name & Unit in requested order (subject name -> unit, unit size smaller than subject name)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AppDropdownField(
                label = "Subject Name",
                selectedValue = subjectName,
                options = currentSubjects,
                onSelect = { selected ->
                    subjectName = selected
                    if (selected == "NONE") {
                        selectedUnit = "NONE"
                        topicCovered = "NONE"
                    } else {
                        if (selected.contains("Lab", ignoreCase = true) || selected.contains("Project", ignoreCase = true)) {
                            batchType = "Lab"
                        } else if (batchType == "Lab") {
                            batchType = "Lecture"
                        }
                        val matchedCourse = dbMatchingCourses.firstOrNull { it.courseName.equals(selected, ignoreCase = true) }
                        val firstUnit = if (matchedCourse != null && matchedCourse.units.isNotEmpty()) {
                            val u = matchedCourse.units.sortedBy { it.unitNumber }.first()
                            if (u.unitTitle.isNotBlank()) "Unit ${u.unitNumber}: ${u.unitTitle}".trim() else "Unit ${u.unitNumber}"
                        } else {
                            if (selectedUnit != "NONE") selectedUnit else "Unit 1"
                        }
                        selectedUnit = firstUnit
                        val unitNum = UNIT_REGEX.find(firstUnit)?.value?.filter { it.isDigit() }?.toIntOrNull()
                            ?: firstUnit.filter { it.isDigit() }.toIntOrNull()
                            ?: 1
                        val topics = matchedCourse?.units?.firstOrNull { it.unitNumber == unitNum }?.topics?.sortedBy { it.topicOrder }?.map { it.topicName }
                        topicCovered = if (!topics.isNullOrEmpty()) {
                            topics.firstOrNull() ?: "NONE"
                        } else {
                            getSyllabusTopics(selected, "Unit $unitNum").firstOrNull() ?: "NONE"
                        }
                    }
                },
                placeholder = "Select Subject",
                modifier = Modifier.weight(1.8f),
                scrollable = true
            )

            AppDropdownField(
                label = "Unit",
                selectedValue = selectedUnit,
                options = unitOptions,
                onSelect = { u ->
                    selectedUnit = u
                    val unitNum = UNIT_REGEX.find(u)?.value?.filter { it.isDigit() }?.toIntOrNull()
                        ?: u.filter { it.isDigit() }.toIntOrNull()
                        ?: 1
                    val matchedUnit = selectedCourseObj?.units?.firstOrNull { it.unitNumber == unitNum }
                    val topics = matchedUnit?.topics?.sortedBy { it.topicOrder }?.map { it.topicName }?.filter { it.isNotBlank() }
                    topicCovered = if (!topics.isNullOrEmpty()) {
                        topics.firstOrNull() ?: "NONE"
                    } else {
                        getSyllabusTopics(subjectName, "Unit $unitNum").firstOrNull() ?: "NONE"
                    }
                },
                placeholder = "Select Unit",
                modifier = Modifier.weight(1.0f),
                scrollable = false
            )
        }

        // 5. Full horizontal length topic name
        AppDropdownField(
            label = "Topic Covered",
            selectedValue = topicCovered,
            options = availableTopics,
            onSelect = { selected ->
                topicCovered = selected
            },
            placeholder = "Select Topic Covered",
            modifier = Modifier.fillMaxWidth(),
            scrollable = false
        )

        // Cloud sync and audit status banner
        if (cloudStatusMessage != null || isCloudSyncing) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = AppTheme.colors.surface,
                border = null
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isCloudSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AppTheme.colors.accentOrange)
                    }
                    Text(
                        text = cloudStatusMessage ?: "Auditing attendance...",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppTheme.colors.accentOrange
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Start Attendance Button in Electric Purple
        val canStart = subjectName != "NONE" && topicCovered != "NONE"
        Button(
            onClick = onStartSession,
            enabled = canStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AppTheme.colors.primaryPurple,
                contentColor = Color.White,
                disabledContainerColor = AppTheme.colors.surfaceBorder,
                disabledContentColor = AppTheme.colors.textMuted
            )
        ) {
            Text(
                text = if (canStart) "Start Attendance" else "No Syllabus Available",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (canStart) Color.White else AppTheme.colors.textMuted
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// Formats session start timestamp into clean date and time
private fun formatSessionDateTime(raw: String?): String {
    if (raw.isNullOrBlank()) return "Past Session"
    val trimmed = raw.trim()
    val clean = trimmed.replace("Z", "")
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd"
    )
    for (pat in patterns) {
        try {
            val sdf = SimpleDateFormat(pat, Locale.getDefault())
            sdf.isLenient = false
            val parsed = sdf.parse(clean.take(pat.length))
            if (parsed != null) {
                val outFormat = if (clean.length >= 16) {
                    SimpleDateFormat("dd MMM yyyy • hh:mm a", Locale.getDefault())
                } else {
                    SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                }
                return outFormat.format(parsed)
            }
        } catch (_: Exception) {
            // Evaluates subsequent date pattern on mismatch
        }
    }
    return trimmed
}

// Read-only metadata island matching the island input styling
@Composable
private fun AppDetailIsland(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(AppTheme.colors.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 30.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                color = AppTheme.colors.textSecondary
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = value.ifBlank { "—" },
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// Filter dialog for past attendance sessions
@Composable
private fun OlderSessionsFilterDialog(
    selectedCourse: String?,
    selectedClassType: String?,
    selectedSemester: String?,
    onApply: (course: String?, classType: String?, semester: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var course by remember { mutableStateOf(selectedCourse) }
    var classType by remember { mutableStateOf(selectedClassType) }
    var semester by remember { mutableStateOf(selectedSemester) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppTheme.colors.surface,
        titleContentColor = AppTheme.colors.textPrimary,
        textContentColor = AppTheme.colors.textPrimary,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "Filter Sessions",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = AppTheme.colors.textPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Program filter section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Course Program",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.colors.textSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("B-Tech", "BCA").forEach { prog ->
                            val isSel = course == prog
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSel) AppTheme.colors.primaryPurple else AppTheme.colors.surface,
                                border = null,
                                modifier = Modifier.clickable { course = if (isSel) null else prog }
                            ) {
                                Text(
                                    text = prog,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSel) Color.White else AppTheme.colors.textPrimary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // Class type filter section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Class Type",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.colors.textSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Regular", "Extra", "Adjustment").forEach { cls ->
                            val isSel = classType == cls
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSel) AppTheme.colors.primaryPurple else AppTheme.colors.surface,
                                border = null,
                                modifier = Modifier.clickable { classType = if (isSel) null else cls }
                            ) {
                                Text(
                                    text = cls,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSel) Color.White else AppTheme.colors.textPrimary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // Academic semester filter section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Semester",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.colors.textSecondary
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        (1..8).map { "Semester $it" }.forEach { sem ->
                            val isSel = semester == sem
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSel) AppTheme.colors.primaryPurple else AppTheme.colors.surface,
                                border = null,
                                modifier = Modifier.clickable { semester = if (isSel) null else sem }
                            ) {
                                Text(
                                    text = sem,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSel) Color.White else AppTheme.colors.textPrimary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                course = null
                classType = null
                semester = null
                onApply(null, null, null)
                onDismiss()
            }) {
                Text("Reset", color = AppTheme.colors.textSecondary)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onApply(course, classType, semester)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primaryPurple)
            ) {
                Text("Apply", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    )
}

// Past attendance session list sorted by latest first with top filters and bottom search
@Composable
private fun OlderSessionsView(
    pastSessions: List<AttendanceSessionResponse>,
    isLoading: Boolean,
    onSelectSession: (AttendanceSessionResponse) -> Unit
) {
    val sortedSessions = remember(pastSessions) {
        pastSessions.sortedByDescending { it.startedAt ?: "" }
    }

    var showFilterDialog by remember { mutableStateOf(false) }
    var filterCourse by remember { mutableStateOf<String?>(null) }
    var filterClassType by remember { mutableStateOf<String?>(null) }
    var filterSemester by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val activeFilterCount = (if (filterCourse != null) 1 else 0) +
        (if (filterClassType != null) 1 else 0) +
        (if (filterSemester != null) 1 else 0)

    val filteredSessions = remember(sortedSessions, filterCourse, filterClassType, filterSemester, searchQuery) {
        val curCourse = filterCourse
        val curClassType = filterClassType
        val curSemester = filterSemester
        sortedSessions.filter { session ->
            val details = getSessionAcademicDetails(session)
            val formattedDate = formatSessionDateTime(session.startedAt)

            val matchesCourse = curCourse == null || details.courseProgram.equals(curCourse, ignoreCase = true)
            val matchesClassType = curClassType == null || details.classType.contains(curClassType, ignoreCase = true)
            val matchesSemester = curSemester == null || details.semester.contains(curSemester, ignoreCase = true)

            val matchesSearch = if (searchQuery.isBlank()) true else {
                formattedDate.contains(searchQuery, ignoreCase = true) ||
                    (session.startedAt?.contains(searchQuery, ignoreCase = true) == true) ||
                    details.subjectName.contains(searchQuery, ignoreCase = true) ||
                    details.courseProgram.contains(searchQuery, ignoreCase = true) ||
                    details.branch.contains(searchQuery, ignoreCase = true) ||
                    details.topicCovered.contains(searchQuery, ignoreCase = true)
            }

            matchesCourse && matchesClassType && matchesSemester && matchesSearch
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AppTheme.colors.primaryPurple, strokeWidth = 2.5.dp)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Filter bar at the top with Filter button and All option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Filter dialog button
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (activeFilterCount > 0) AppTheme.colors.primaryPurple.copy(alpha = 0.15f) else AppTheme.colors.surface,
                        border = null,
                        modifier = Modifier.clickable { showFilterDialog = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = "Filter",
                                tint = if (activeFilterCount > 0) AppTheme.colors.primaryPurple else AppTheme.colors.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (activeFilterCount > 0) "Filter ($activeFilterCount)" else "Filter",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (activeFilterCount > 0) AppTheme.colors.primaryPurple else AppTheme.colors.textSecondary
                            )
                        }
                    }

                    // All chip to display all sessions or reset active filters
                    val isAllSelected = activeFilterCount == 0 && searchQuery.isBlank()
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isAllSelected) AppTheme.colors.primaryPurple else AppTheme.colors.surface,
                        border = null,
                        modifier = Modifier.clickable {
                            filterCourse = null
                            filterClassType = null
                            filterSemester = null
                            searchQuery = ""
                        }
                    ) {
                        Text(
                            text = "All",
                            fontSize = 12.5.sp,
                            fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isAllSelected) Color.White else AppTheme.colors.textSecondary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }

                // Active search banner with clear action
                if (searchQuery.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Filtered by: \"$searchQuery\"",
                            fontSize = 12.sp,
                            color = AppTheme.colors.textSecondary
                        )
                        Text(
                            text = "Clear",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.primaryPurple,
                            modifier = Modifier.clickable { searchQuery = "" }
                        )
                    }
                }

                // Single big island containing past sessions
                if (filteredSessions.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = AppTheme.colors.surface,
                        border = null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 36.dp, horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (pastSessions.isEmpty()) "No past attendance sessions found" else "No sessions match your filter",
                                    fontSize = 14.sp,
                                    color = AppTheme.colors.textSecondary,
                                    fontWeight = FontWeight.Medium
                                )
                                if (activeFilterCount > 0 || searchQuery.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Reset all filters",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppTheme.colors.primaryPurple,
                                        modifier = Modifier.clickable {
                                            filterCourse = null
                                            filterClassType = null
                                            filterSemester = null
                                            searchQuery = ""
                                        }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = AppTheme.colors.surface,
                        border = null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            filteredSessions.forEachIndexed { index, session ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        color = AppTheme.colors.background,
                                        thickness = 1.dp,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                // Single session row displaying only date and time
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectSession(session) }
                                        .padding(horizontal = 18.dp, vertical = 15.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = formatSessionDateTime(session.startedAt),
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AppTheme.colors.textPrimary
                                    )
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = AppTheme.colors.textMuted.copy(alpha = 0.6f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Clearance for floating search pill
                Spacer(modifier = Modifier.height(72.dp))
            }

            // Floating search pill pinned at bottom
            AppSearchPill(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "Search sessions",
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (showFilterDialog) {
        OlderSessionsFilterDialog(
            selectedCourse = filterCourse,
            selectedClassType = filterClassType,
            selectedSemester = filterSemester,
            onApply = { c, cls, sem ->
                filterCourse = c
                filterClassType = cls
                filterSemester = sem
            },
            onDismiss = { showFilterDialog = false }
        )
    }
}

// Detailed roster and breakdown view for a selected attendance session
@Composable
private fun SessionDetailScreen(
    session: AttendanceSessionResponse,
    onExportAttendance: () -> Unit
) {
    val details = remember(session) { getSessionAcademicDetails(session) }
    val records = session.records ?: emptyList()
    val sortedRecords = remember(records) {
        records.sortedWith { r1, r2 ->
            compareRollNumbers(getDisplayRollNo(r1), getDisplayRollNo(r2))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            // Details on top in same island format as while filling in before taking attendance
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Class Type
                AppDetailIsland(
                    label = "Class Type",
                    value = details.classType,
                    modifier = Modifier.fillMaxWidth()
                )

                // 2. Course & Branch
                if (details.courseProgram == "BCA" || details.branch == "--" || details.branch.isBlank()) {
                    AppDetailIsland(
                        label = "Course",
                        value = details.courseProgram,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AppDetailIsland(
                            label = "Course",
                            value = details.courseProgram,
                            modifier = Modifier.weight(1f)
                        )
                        AppDetailIsland(
                            label = "Branch",
                            value = details.branch,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 3. Semester & Batch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AppDetailIsland(
                        label = "Semester",
                        value = details.semester,
                        modifier = Modifier.weight(1f)
                    )
                    AppDetailIsland(
                        label = "Batch",
                        value = details.batchType,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 4. Subject Name & Unit (unit size smaller than subject name)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AppDetailIsland(
                        label = "Subject Name",
                        value = details.subjectName,
                        modifier = Modifier.weight(1.8f)
                    )
                    AppDetailIsland(
                        label = "Unit",
                        value = details.unit,
                        modifier = Modifier.weight(1.0f)
                    )
                }

                // 5. Full horizontal length topic name
                AppDetailIsland(
                    label = "Topic Covered",
                    value = details.topicCovered,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(6.dp))

            // Attendance list title with grey Export text option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Attendance list",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.textPrimary
                )

                Text(
                    text = "Export",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppTheme.colors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onExportAttendance() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Attendance student roster unified into a single island
        if (sortedRecords.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(AppTheme.colors.surface, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No students recorded in this session.",
                        fontSize = 13.sp,
                        color = textMuted
                    )
                }
            }
        } else {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = AppTheme.colors.surface,
                    border = null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        sortedRecords.forEachIndexed { index, rec ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = AppTheme.colors.background,
                                    thickness = 1.dp,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            val isPresent = isRecordPresent(rec)
                            val statusText = if (isPresent) "Present" else "Absent"
                            val statusBg = if (isPresent) AppTheme.colors.primaryPurple else AppTheme.colors.accentOrange

                            val rollNo = remember(rec) { getDisplayRollNo(rec) }
                            val shortRoll = remember(rollNo) { getRollNumberTwoDigits(rollNo) }
                            val studentName = remember(rec) { getDisplayStudentName(rec) }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Roll number badge circle
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(AppTheme.colors.background, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = shortRoll,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = AppTheme.colors.textPrimary
                                    )
                                }

                                // Student name
                                Text(
                                    text = studentName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppTheme.colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )

                                // Present or Absent status pill
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = statusBg,
                                    border = null
                                ) {
                                    Text(
                                        text = statusText,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
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

@Immutable
private data class SessionAcademicDetails(
    val classType: String,
    val courseProgram: String,
    val branch: String,
    val semester: String,
    val batchType: String,
    val subjectName: String,
    val unit: String,
    val topicCovered: String
)

private val SEMESTER_REGEX_1 = Regex("Semester\\s*\\d", RegexOption.IGNORE_CASE)
private val SEMESTER_REGEX_2 = Regex("Sem\\s*\\d", RegexOption.IGNORE_CASE)
private val UNIT_REGEX = Regex("Unit\\s*\\d", RegexOption.IGNORE_CASE)

// Extracts a clean subject name from course name metadata or course code
internal fun extractSubjectName(rawCourseName: String?, courseCode: String?): String {
    val raw = rawCourseName?.trim().orEmpty()

    // 1. Direct match against official Semester 3 subjects
    val sem3Match = sem3Subjects.firstOrNull {
        raw.startsWith(it, ignoreCase = true) || raw.contains(it, ignoreCase = true)
    }
    if (sem3Match != null) return sem3Match

    if (raw.isNotBlank()) {
        if (raw.contains("•")) {
            val prefix = raw.substringBefore("•").trim()
            val isClassTypePrefix = prefix.startsWith("Regular", ignoreCase = true) ||
                prefix.startsWith("Extra", ignoreCase = true) ||
                prefix.startsWith("Adjustment", ignoreCase = true) ||
                prefix.startsWith("Lecture", ignoreCase = true) ||
                prefix.startsWith("Lab", ignoreCase = true) ||
                prefix.startsWith("Tutorial", ignoreCase = true)
            if (!isClassTypePrefix && prefix.isNotBlank()) {
                return prefix
            }
        } else {
            val isClassTypeOnly = raw.startsWith("Regular", ignoreCase = true) ||
                raw.startsWith("Extra", ignoreCase = true) ||
                raw.startsWith("Adjustment", ignoreCase = true)
            if (!isClassTypeOnly) {
                return raw
            }
        }
    }

    val combined = "$raw ${courseCode.orEmpty()}"
    return when {
        combined.contains("Digital Electronics", ignoreCase = true) -> "Digital Electronics"
        combined.contains("Universal Human Values", ignoreCase = true) || combined.contains("UHV", ignoreCase = true) -> "Universal Human Values"
        combined.contains("Discrete Structure", ignoreCase = true) || combined.contains("Discrete", ignoreCase = true) -> "Discrete Structure"
        combined.contains("Data Structures and Algorithms Lab", ignoreCase = true) || combined.contains("DSA Lab", ignoreCase = true) -> "Data Structures and Algorithms Lab"
        combined.contains("Data Structures", ignoreCase = true) || combined.contains("DSA", ignoreCase = true) -> "Data Structures and Algorithms"
        combined.contains("Object Oriented Programming Lab", ignoreCase = true) || combined.contains("OOP Lab", ignoreCase = true) -> "Object Oriented Programming Lab"
        combined.contains("Object Oriented", ignoreCase = true) || combined.contains("OOP", ignoreCase = true) -> "Object Oriented Programming"
        combined.contains("Python Programming Lab", ignoreCase = true) || combined.contains("Python Lab", ignoreCase = true) -> "Python Programming Lab"
        combined.contains("Python", ignoreCase = true) -> "Python Programming"
        combined.contains("Internship", ignoreCase = true) || combined.contains("Mini Project", ignoreCase = true) -> "Internship-I / Mini Project-I"
        raw.isNotBlank() -> raw
        else -> "NONE"
    }
}

// Parses and reconstructs academic session fields from session response metadata
private fun getSessionAcademicDetails(session: AttendanceSessionResponse): SessionAcademicDetails {
    val courseProg = session.courseProgram
        ?: if (session.courseCode?.contains("BCA", ignoreCase = true) == true) "BCA"
        else if (session.courseCode?.contains("B-Tech", ignoreCase = true) == true || session.courseCode?.contains("BTECH", ignoreCase = true) == true) "B-Tech"
        else session.courseCode?.takeIf { it.isNotBlank() } ?: "B-Tech"
    val branch = when {
        session.courseCode?.contains("CSE", ignoreCase = true) == true -> "CSE"
        session.courseCode?.contains("ME", ignoreCase = true) == true -> "ME"
        session.courseCode?.contains("ECE", ignoreCase = true) == true -> "ECE"
        session.courseCode?.contains("EE", ignoreCase = true) == true -> "EE"
        session.courseCode?.contains("CE", ignoreCase = true) == true -> "CE"
        session.courseCode?.contains("IT", ignoreCase = true) == true -> "IT"
        else -> if (courseProg == "BCA") "--" else "CSE"
    }
    val sem = session.semester
        ?: SEMESTER_REGEX_1.find(session.courseCode ?: "")?.value
        ?: SEMESTER_REGEX_2.find(session.courseCode ?: "")?.value
        ?: if (session.courseCode?.isNotBlank() == true) session.courseCode else "Semester 3"

    val rawName = session.courseName ?: ""
    val subject = session.subjectName?.takeIf { it.isNotBlank() }
        ?: extractSubjectName(rawName, session.courseCode)
    val remainder = if (rawName.startsWith(subject, ignoreCase = true)) {
        rawName.substring(subject.length).trim().removePrefix("•").trim()
    } else if (rawName.contains("•")) {
        rawName.substringAfter("•").trim()
    } else {
        rawName
    }

    val clsType = session.classType ?: when {
        remainder.contains("Extra", ignoreCase = true) -> "Extra"
        remainder.contains("Adjustment", ignoreCase = true) -> "Adjustment"
        else -> "Regular"
    }
    val batch = session.batchType ?: if (remainder.contains("Lab", ignoreCase = true) || rawName.contains("Lab", ignoreCase = true)) "Lab" else "Lecture"
    val unit = session.unit
        ?: UNIT_REGEX.find(remainder)?.value
        ?: UNIT_REGEX.find(rawName)?.value
        ?: "Unit 1"
    val topic = session.topicCovered?.takeIf { it.isNotBlank() } ?: run {
        if (rawName.contains("(") && rawName.contains(")")) {
            val inside = rawName.substringAfter("(").substringBefore(")").trim()
            if (inside.contains("•")) {
                inside.substringAfter("•").trim()
            } else if (!inside.startsWith("Unit", ignoreCase = true) && !inside.startsWith("Exp", ignoreCase = true)) {
                inside
            } else if (remainder.contains("•")) {
                remainder.substringAfterLast("•").trim()
            } else {
                "General Lecture"
            }
        } else if (remainder.contains("•")) {
            val last = remainder.substringAfterLast("•").trim()
            if (!last.startsWith("Unit", ignoreCase = true)) last else remainder
        } else if (remainder.isNotBlank()) {
            remainder
        } else {
            "General Lecture"
        }
    }
    return SessionAcademicDetails(clsType, courseProg, branch, sem, batch, subject, unit, topic)
}

