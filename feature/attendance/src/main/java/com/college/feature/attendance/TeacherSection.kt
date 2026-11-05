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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
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
    onExportAttendance: () -> Unit = {}
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
                    border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder)
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
                    border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder)
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
                border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder),
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
                            key = { _, rec -> rec.bleDeviceId }
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

            // Stop session button in solar orange with white text
            Button(
                onClick = onStopSession,
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
                    text = "Stop",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    } else if (selectedSessionForDetails != null) {
        SessionDetailScreen(
            session = selectedSessionForDetails
        )
    } else {
        when (teacherViewMode) {
            TeacherViewMode.MENU,
            TeacherViewMode.TAKE_ATTENDANCE -> {
                // Course selection and session configuration view
                TakeAttendanceSelectionView(
                    onStartSession = onStartSession,
                    onSelectCourse = onSelectCourse,
                    isCloudSyncing = isCloudSyncing,
                    cloudStatusMessage = cloudStatusMessage
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

private val syllabusTopics = mapOf(
    "Unit 1" to listOf(
        "Foundations & System Architecture",
        "BLE Protocol Stack Overview",
        "Physical & Link Layer Specs"
    ),
    "Unit 2" to listOf(
        "BLE Advertising & Proximity Packets",
        "RSSI Path Loss & Distance Estimation",
        "Scan Interval & Beacon Scheduling"
    ),
    "Unit 3" to listOf(
        "Peer Witnessing & Mesh Consensus",
        "Mutual Directed In-Degree Verification",
        "Quorum Witness Consensus"
    ),
    "Unit 4" to listOf(
        "Cryptographic Nonce & Ephemeral Auth",
        "Anti-Spoofing & Replay Attack Defense",
        "Zero-Knowledge Proofs & Privacy"
    ),
    "Unit 5" to listOf(
        "Scalability & Distributed Mesh Sync",
        "Cloud Reconciliation & Audit Trails",
        "Practical Lab Simulation & Verification"
    )
)

private val engineeringBranches = listOf(
    "CSE",
    "IT",
    "ECE",
    "ME",
    "CE",
    "EE"
)

// Academic subjects mapped by engineering branch and degree program
private val branchSubjects = mapOf(
    "CSE" to listOf(
        "Computer Networks",
        "Operating Systems",
        "Data Structures & Algorithms",
        "Database Management Systems",
        "Software Engineering",
        "Theory of Computation",
        "Compiler Design",
        "Machine Learning",
        "Computer Architecture",
        "Cyber Security"
    ),
    "IT" to listOf(
        "Web Technologies",
        "Information Security",
        "Cloud Computing",
        "Data Mining & Warehousing",
        "Software Project Management",
        "Computer Networks",
        "Mobile App Development",
        "Database Systems"
    ),
    "ECE" to listOf(
        "Digital Signal Processing",
        "Signals & Systems",
        "Analog Circuits",
        "Digital Electronics",
        "Microprocessors & Microcontrollers",
        "VLSI Design",
        "Communication Systems",
        "Electromagnetic Theory"
    ),
    "ME" to listOf(
        "Thermodynamics",
        "Fluid Mechanics",
        "Mechanics of Materials",
        "Manufacturing Processes",
        "Theory of Machines",
        "Heat & Mass Transfer",
        "Machine Design",
        "CAD/CAM"
    ),
    "CE" to listOf(
        "Structural Analysis",
        "Geotechnical Engineering",
        "Fluid Mechanics",
        "Surveying",
        "Concrete Technology",
        "Transportation Engineering",
        "Environmental Engineering"
    ),
    "EE" to listOf(
        "Power Systems",
        "Electrical Machines",
        "Control Systems",
        "Power Electronics",
        "Circuit Theory",
        "Electromagnetic Fields",
        "Renewable Energy Systems"
    ),
    "BCA" to listOf(
        "Programming in C/C++",
        "Data Structures",
        "Database Management (SQL)",
        "Web Development",
        "Java Programming",
        "Python Programming",
        "Computer Networks",
        "Operating Systems",
        "Software Engineering"
    )
)

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
    cloudStatusMessage: String?
) {
    var classType by remember { mutableStateOf("Regular") }
    var courseProgram by remember { mutableStateOf("B-Tech") }
    var branch by remember { mutableStateOf("CSE") }
    var semester by remember { mutableStateOf("Semester 1") }
    var batchType by remember { mutableStateOf("Lecture") }
    var subjectName by remember { mutableStateOf("Computer Networks") }
    var selectedUnit by remember { mutableStateOf("Unit 1") }
    var topicCovered by remember { mutableStateOf("Foundations & System Architecture") }

    var showCustomSubjectDialog by remember { mutableStateOf(false) }
    var customSubjectInput by remember { mutableStateOf("") }
    var showCustomTopicDialog by remember { mutableStateOf(false) }
    var customTopicInput by remember { mutableStateOf("") }

    val currentSubjects = remember(courseProgram, branch) {
        if (courseProgram == "BCA") {
            branchSubjects["BCA"] ?: listOf("Computer Networks")
        } else {
            branchSubjects[branch] ?: branchSubjects["CSE"] ?: listOf("Computer Networks")
        }
    }
    val subjectOptions = remember(currentSubjects) {
        currentSubjects + listOf("+ Enter Custom Subject...")
    }

    // Keep subject aligned with branch when switching courses or branches
    LaunchedEffect(currentSubjects) {
        if (subjectName !in currentSubjects && customSubjectInput.isBlank()) {
            subjectName = currentSubjects.firstOrNull() ?: "Computer Networks"
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
    LaunchedEffect(classType, courseProgram, branch, semester, batchType, subjectName, selectedUnit, topicCovered) {
        val branchSuffix = if (courseProgram != "BCA" && branch.isNotBlank()) " • $branch" else ""
        val topicSuffix = if (topicCovered.isNotBlank()) " • $topicCovered" else ""
        val code = "$courseProgram$branchSuffix $semester"
        val name = "$subjectName • $classType $batchType ($selectedUnit$topicSuffix)"
        val stableCourseId = java.util.UUID.nameUUIDFromBytes("academic-${courseProgram.lowercase()}-${branch.lowercase()}-${semester.replace(" ", "").lowercase()}".toByteArray()).toString()
        onSelectCourse(
            CourseResponse(
                id = stableCourseId,
                courseCode = code,
                courseName = name
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
                options = listOf("B-Tech", "BCA"),
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
                    options = listOf("B-Tech", "BCA"),
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
                options = subjectOptions,
                onSelect = { selected ->
                    if (selected == "+ Enter Custom Subject...") {
                        customSubjectInput = ""
                        showCustomSubjectDialog = true
                    } else {
                        subjectName = selected
                    }
                },
                placeholder = "Select Subject",
                modifier = Modifier.weight(1.8f),
                scrollable = true
            )

            AppDropdownField(
                label = "Unit",
                selectedValue = selectedUnit,
                options = listOf("Unit 1", "Unit 2", "Unit 3", "Unit 4", "Unit 5"),
                onSelect = { u ->
                    selectedUnit = u
                    topicCovered = syllabusTopics[u]?.firstOrNull() ?: topicCovered
                },
                placeholder = "Select Unit",
                modifier = Modifier.weight(1.0f),
                scrollable = false
            )
        }

        // 5. Full horizontal length topic name
        val availableTopics = syllabusTopics[selectedUnit] ?: listOf("General Syllabus Topic")
        val topicOptions = remember(availableTopics) {
            availableTopics + listOf("+ Enter Custom Topic...")
        }
        AppDropdownField(
            label = "Topic Covered",
            selectedValue = topicCovered,
            options = topicOptions,
            onSelect = { selected ->
                if (selected == "+ Enter Custom Topic...") {
                    customTopicInput = ""
                    showCustomTopicDialog = true
                } else {
                    topicCovered = selected
                }
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
                border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder)
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
        Button(
            onClick = onStartSession,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AppTheme.colors.primaryPurple,
                contentColor = Color.White
            )
        ) {
            Text(
                text = "Start Attendance",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showCustomSubjectDialog) {
        AlertDialog(
            onDismissRequest = { showCustomSubjectDialog = false },
            title = {
                Text(
                    text = "Custom Subject Name",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = AppTheme.colors.textPrimary
                )
            },
            text = {
                OutlinedTextField(
                    value = customSubjectInput,
                    onValueChange = { customSubjectInput = it },
                    placeholder = { Text("e.g. Distributed Systems", color = AppTheme.colors.textMuted, fontSize = 14.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.colors.primaryPurple,
                        unfocusedBorderColor = AppTheme.colors.surfaceBorder,
                        focusedTextColor = AppTheme.colors.textPrimary,
                        unfocusedTextColor = AppTheme.colors.textPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (customSubjectInput.isNotBlank()) {
                            subjectName = customSubjectInput.trim()
                        }
                        showCustomSubjectDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primaryPurple)
                ) {
                    Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomSubjectDialog = false }) {
                    Text("Cancel", color = AppTheme.colors.textSecondary)
                }
            },
            containerColor = AppTheme.colors.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showCustomTopicDialog) {
        AlertDialog(
            onDismissRequest = { showCustomTopicDialog = false },
            title = {
                Text(
                    text = "Custom Topic Name",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = AppTheme.colors.textPrimary
                )
            },
            text = {
                OutlinedTextField(
                    value = customTopicInput,
                    onValueChange = { customTopicInput = it },
                    placeholder = { Text("e.g. Paxos & Raft Consensus", color = AppTheme.colors.textMuted, fontSize = 14.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.colors.primaryPurple,
                        unfocusedBorderColor = AppTheme.colors.surfaceBorder,
                        focusedTextColor = AppTheme.colors.textPrimary,
                        unfocusedTextColor = AppTheme.colors.textPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (customTopicInput.isNotBlank()) {
                            topicCovered = customTopicInput.trim()
                        }
                        showCustomTopicDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primaryPurple)
                ) {
                    Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomTopicDialog = false }) {
                    Text("Cancel", color = AppTheme.colors.textSecondary)
                }
            },
            containerColor = AppTheme.colors.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

private fun formatSessionDate(raw: String?): String {
    if (raw.isNullOrBlank()) return "Past session"
    val trimmed = raw.trim()
    if (trimmed.length >= 10 && trimmed[4] == '-' && trimmed[7] == '-') {
        val datePart = trimmed.substring(0, 10)
        val timePart = if (trimmed.length >= 16 && trimmed[10] == 'T') {
            trimmed.substring(11, 16)
        } else ""
        return if (timePart.isNotEmpty()) "$datePart $timePart" else datePart
    }
    return trimmed
}

@Composable
private fun OlderSessionsView(
    pastSessions: List<AttendanceSessionResponse>,
    isLoading: Boolean,
    onSelectSession: (AttendanceSessionResponse) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = primaryBlue, strokeWidth = 2.5.dp)
            }
        } else if (pastSessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(cardBg, RoundedCornerShape(14.dp))
                    .border(1.dp, cardBorder, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No past attendance sessions found.",
                        color = textMuted,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Sessions you conduct will be archived here.",
                        color = textSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(pastSessions, key = { it.id }) { session ->
                    val details = remember(session) { getSessionAcademicDetails(session) }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectSession(session) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = BorderStroke(1.dp, cardBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val sessionTitle = when {
                                !details.courseProgram.isNullOrBlank() && details.courseProgram != "—" && !details.semester.isNullOrBlank() && details.semester != "—" ->
                                    "${details.courseProgram} • ${details.semester}"
                                !details.courseProgram.isNullOrBlank() && details.courseProgram != "—" ->
                                    details.courseProgram
                                !session.courseCode.isNullOrBlank() ->
                                    session.courseCode
                                else ->
                                    "Session ${session.sessionToken}"
                            }
                            Text(
                                text = sessionTitle,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = formatSessionDate(session.startedAt),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// Detailed roster and breakdown view for a selected attendance session
@Composable
private fun SessionDetailScreen(
    session: AttendanceSessionResponse
) {
    val details = remember(session) { getSessionAcademicDetails(session) }
    val records = session.records ?: emptyList()
    val sortedRecords = remember(records) {
        records.sortedWith { r1, r2 ->
            compareRollNumbers(getDisplayRollNo(r1), getDisplayRollNo(r2))
        }
    }
    val (verifiedCount, unverifiedCount, flaggedCount) = remember(sortedRecords) {
        var v = 0
        var u = 0
        var f = 0
        for (i in sortedRecords.indices) {
            when (getRecordAuditStatus(sortedRecords[i])) {
                "Verified" -> v++
                "Unverified" -> u++
                "Flagged" -> f++
            }
        }
        Triple(v, u, f)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Session Details Card (clean details card, top bar handled by main Scaffold)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Row 1: Course, Branch, Semester, Class Type
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SessionDetailPropertyField(label = "Course", value = details.courseProgram, modifier = Modifier.weight(1f))
                    if (details.branch.isNotBlank() && details.branch != "--") {
                        SessionDetailPropertyField(label = "Branch", value = details.branch, modifier = Modifier.weight(1f))
                    }
                    SessionDetailPropertyField(label = "Semester", value = details.semester, modifier = Modifier.weight(1f))
                    SessionDetailPropertyField(label = "Class Type", value = details.classType, modifier = Modifier.weight(1f))
                }

                // Row 2: Subject, Unit, Batch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SessionDetailPropertyField(label = "Subject", value = details.subjectName, modifier = Modifier.weight(1.8f))
                    SessionDetailPropertyField(label = "Unit", value = details.unit, modifier = Modifier.weight(1.0f))
                    SessionDetailPropertyField(label = "Batch", value = details.batchType, modifier = Modifier.weight(1.0f))
                }

                // Row 3: Topic Covered (full-width so long syllabus topics never overflow)
                SessionDetailPropertyField(
                    label = "Topic Covered",
                    value = details.topicCovered,
                    modifier = Modifier.fillMaxWidth(),
                    allowMultiLine = true,
                    maxLines = 3
                )

                HorizontalDivider(color = cardBorder, modifier = Modifier.padding(vertical = 1.dp))

                // Stats row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricStatMiniCard(count = verifiedCount, label = "Verified", color = successGreen, bg = successGreenBg, modifier = Modifier.weight(1f))
                    MetricStatMiniCard(count = unverifiedCount, label = "Unverified", color = warningOrange, bg = warningOrangeBg, modifier = Modifier.weight(1f))
                    MetricStatMiniCard(count = flaggedCount, label = "Flagged", color = alertRed, bg = alertRedBg, modifier = Modifier.weight(1f))
                }
            }
        }

        // Compact list of students sorted by roll numbers
        if (sortedRecords.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(cardBg, RoundedCornerShape(12.dp))
                    .border(1.dp, cardBorder, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No students recorded in this session.",
                    fontSize = 13.sp,
                    color = textMuted
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                items(sortedRecords, key = { it.id ?: it.bleDeviceId }) { rec ->
                    val status = remember(rec) { getRecordAuditStatus(rec) }
                    val statusColor = when (status) {
                        "Verified" -> successGreen
                        "Unverified" -> warningOrange
                        else -> alertRed
                    }
                    val statusBg = when (status) {
                        "Verified" -> successGreenBg
                        "Unverified" -> warningOrangeBg
                        else -> alertRedBg
                    }
                    val rollNo = remember(rec) { getDisplayRollNo(rec) }
                    val shortRoll = remember(rollNo) { rollNo.takeLast(2) }
                    val studentName = remember(rec) { getDisplayStudentName(rec) }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = BorderStroke(1.dp, cardBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Circle in front of name: last two digits of roll number (01, 02, 03..)
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(AppTheme.colors.background, CircleShape)
                                    .border(1.dp, AppTheme.colors.surfaceBorder, CircleShape),
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

                            // Student name only (no roll number underneath)
                            Text(
                                text = studentName,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            // Compact status pill
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = statusBg,
                                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = status,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionDetailPropertyField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    allowMultiLine: Boolean = false,
    maxLines: Int = 1
) {
    Box(
        modifier = modifier
            .background(cardBorder.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .border(1.dp, cardBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Column {
            Text(
                text = label.uppercase(),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = textSecondary
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = value,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = textPrimary,
                maxLines = if (allowMultiLine) maxLines else 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MetricStatMiniCard(
    count: Int,
    label: String,
    color: Color,
    bg: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "$count",
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                color = color
            )
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
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
        ?: if (session.courseCode?.isNotBlank() == true) session.courseCode else "Semester 1"

    val rawName = session.courseName ?: ""
    val subject = if (rawName.contains("•")) {
        rawName.substringBefore("•").trim()
    } else {
        "Computer Networks"
    }
    val remainder = if (rawName.contains("•")) rawName.substringAfter("•").trim() else rawName

    val clsType = session.classType ?: when {
        remainder.contains("Extra", ignoreCase = true) -> "Extra"
        remainder.contains("Adjustment", ignoreCase = true) -> "Adjustment"
        else -> "Regular"
    }
    val batch = session.batchType ?: if (remainder.contains("Lab", ignoreCase = true) == true) "Lab" else "Lecture"
    val unit = session.unit
        ?: UNIT_REGEX.find(remainder)?.value
        ?: "Unit 1"
    val topic = session.topicCovered ?: run {
        if (remainder.contains("•")) {
            remainder.substringAfter("•").substringBefore(")").trim()
        } else if (remainder.isNotBlank()) {
            remainder
        } else {
            "Foundations & System Architecture"
        }
    }
    return SessionAcademicDetails(clsType, courseProg, branch, sem, batch, subject, unit, topic)
}

