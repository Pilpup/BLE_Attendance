package com.college.feature.leave

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.college.core.ui.theme.AppTheme
import com.college.feature.leave.network.LeaveApplicationResponse
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ---------------------------------------------------------------------------
// Timezone-safe date helpers.
//
// Material3's date picker emits UTC-midnight millis, and the wire format is a
// bare calendar date ("YYYY-MM-DD"). Everything here treats those values as
// LOCAL CALENDAR DATES — no default-timezone instantiation, no UTC offset
// shifts — so a range picked on the phone is never off by one day when
// compared against the server's dates.
// ---------------------------------------------------------------------------

// UTC-midnight formatter: the picker's millis are UTC midnights, so formatting
// them through a UTC calendar reproduces the exact calendar date picked.
private val LEAVE_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

private const val MS_PER_DAY = 86_400_000L

// Today's UTC-midnight millis — the lower bound for selectable leave dates.
fun todayUtcMidnightMillis(): Long {
    val now = System.currentTimeMillis()
    return now - (now % MS_PER_DAY)
}

// Parses a wire date ("YYYY-MM-DD") into UTC-midnight millis, or null when
// malformed. Same scheme the picker uses, so comparisons stay exact.
fun parseLeaveDate(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return try {
        LEAVE_DATE_FORMAT.parse(raw)?.time
    } catch (_: ParseException) {
        null
    }
}

fun formatLeaveDate(millis: Long?): String {
    if (millis == null) return ""
    return LEAVE_DATE_FORMAT.format(Date(millis))
}

// Interval intersection between a stored [from,to] and a candidate [from,to].
fun leaveRangesOverlap(aFrom: Long, aTo: Long, bFrom: Long, bTo: Long): Boolean =
    !(aTo < bFrom || aFrom > bTo)

// "2026-11-13" -> "13 Nov 2026" for card display
fun prettyLeaveDate(isoDate: String): String {
    val millis = parseLeaveDate(isoDate) ?: return isoDate
    val out = SimpleDateFormat("dd MMM yyyy", Locale.US)
    return out.format(Date(millis))
}

// Renders an ISO timestamp from the API as "13 Nov 2026, 9:41 AM". On any
// surprise format the raw text is shown instead of crashing.
fun prettyLeaveTimestamp(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val normalized = iso.replace("Z", "+00:00")
    val withMilli = normalized.replace(Regex("\\.(\\d{3})\\d*")) { m -> ".${m.groupValues[1]}" }
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss"
    )
    for (pattern in patterns) {
        try {
            val parser = SimpleDateFormat(pattern, Locale.US)
            val parsed = parser.parse(withMilli) ?: continue
            val out = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US)
            return out.format(parsed)
        } catch (_: ParseException) {
            // try next pattern
        }
    }
    return normalized.take(16).replace("T", " ")
}

private const val MAX_REASON_LENGTH = 1000
private const val MIN_REASON_LENGTH = 3

// ---------------------------------------------------------------------------
// Leave application form: From/To date selection with chronological validation,
// overlap prevention, and the applicant reason with a live character counter.
// All errors render inline next to the field they belong to.
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaveApplicationForm(
    applications: List<LeaveApplicationResponse>,
    isSubmitting: Boolean,
    message: String?,
    onSubmit: (fromDate: String, toDate: String, reason: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var fromMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var toMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }
    var reason by rememberSaveable { mutableStateOf("") }

    val fromText = formatLeaveDate(fromMillis)
    val toText = formatLeaveDate(toMillis)

    // Overlap prevention against the student's own live (PENDING/APPROVED)
    // requests — mirrors the server-side guard so the error shows before any
    // network round trip.
    val overlapWith: LeaveApplicationResponse? = remember(applications, fromMillis, toMillis) {
        val newFrom = fromMillis
        val newTo = toMillis
        if (newFrom == null || newTo == null) {
            null
        } else {
            applications.firstOrNull { app ->
                (app.status.equals("PENDING", true) || app.status.equals("APPROVED", true)) &&
                        leaveRangesOverlap(
                            aFrom = parseLeaveDate(app.fromDate) ?: return@firstOrNull false,
                            aTo = parseLeaveDate(app.toDate) ?: return@firstOrNull false,
                            bFrom = newFrom,
                            bTo = newTo
                        )
            }
        }
    }

    val reasonTrimmed = reason.trim()
    val reasonError = when {
        reasonTrimmed.isNotEmpty() && reasonTrimmed.length < MIN_REASON_LENGTH ->
            "Reason must be at least $MIN_REASON_LENGTH characters"
        reason.length > MAX_REASON_LENGTH -> "Reason is too long"
        else -> null
    }

    val canSubmit = fromMillis != null && toMillis != null &&
            overlapWith == null &&
            reasonTrimmed.length >= MIN_REASON_LENGTH &&
            reason.length <= MAX_REASON_LENGTH &&
            !isSubmitting

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // "New application" sits at the very top of the leave menu content.
        Text(
            text = "New application",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppTheme.colors.textPrimary
        )

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = AppTheme.colors.surface,
            border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // --- Date range (From / To) -------------------------------------
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LeaveDateChip(
                        label = "From",
                        text = if (fromMillis == null) "" else prettyLeaveDate(fromText),
                        onClick = { showFromPicker = true },
                        modifier = Modifier.weight(1f)
                    )
                    // The To chip stays locked until a From date exists — its
                    // picker's earliest selectable date IS the From date.
                    LeaveDateChip(
                        label = "To",
                        text = if (toMillis == null) "" else prettyLeaveDate(toText),
                        enabled = fromMillis != null,
                        onClick = { showToPicker = true },
                        modifier = Modifier.weight(1f)
                    )
                }

                // --- Inline overlap error ---------------------------------------
                if (overlapWith != null) {
                    Text(
                        text = "Overlaps your ${overlapWith.status.lowercase(Locale.US)} leave " +
                                "(${prettyLeaveDate(overlapWith.fromDate)} → ${prettyLeaveDate(overlapWith.toDate)}). " +
                                "Pick different dates or cancel that request.",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppTheme.colors.dangerRed
                    )
                }

                // --- Applicant reason (required, counted) ------------------------
                OutlinedTextField(
                    value = reason,
                    onValueChange = { if (it.length <= MAX_REASON_LENGTH) reason = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Reason for leave") },
                    isError = reasonError != null,
                    supportingText = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = reasonError ?: "",
                                color = AppTheme.colors.dangerRed,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "${reason.length}/$MAX_REASON_LENGTH",
                                fontSize = 12.sp,
                                color = AppTheme.colors.textMuted
                            )
                        }
                    },
                    minLines = 3,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.colors.primaryPurple,
                        unfocusedBorderColor = AppTheme.colors.surfaceBorder,
                        focusedLabelColor = AppTheme.colors.primaryPurple,
                        cursorColor = AppTheme.colors.primaryPurple
                    )
                )

                // --- Submission feedback banner ----------------------------------
                if (!message.isNullOrBlank()) {
                    val ok = message.contains("submitted", ignoreCase = true) ||
                            message.contains("cancelled", ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (ok) AppTheme.colors.successGreenBg else AppTheme.colors.dangerRedBg,
                        border = BorderStroke(
                            1.dp,
                            (if (ok) AppTheme.colors.successGreen else AppTheme.colors.dangerRed).copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = message,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (ok) AppTheme.colors.successGreen else AppTheme.colors.dangerRed,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                // --- Submit -------------------------------------------------------
                Surface(
                    onClick = {
                        if (canSubmit) {
                            onSubmit(fromText, toText, reasonTrimmed)
                            reason = ""
                            fromMillis = null
                            toMillis = null
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = if (canSubmit) AppTheme.colors.primaryPurple else AppTheme.colors.surfaceBorder,
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (isSubmitting) "Submitting…" else "Submit application",
                            fontWeight = FontWeight.SemiBold,
                            color = if (canSubmit) Color.White else AppTheme.colors.textMuted
                        )
                    }
                }
            }
        }
    }

    // From picker: no dates before today
    if (showFromPicker) {
        val today = todayUtcMidnightMillis()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = fromMillis ?: today,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= today
            }
        )
        DatePickerDialog(
            onDismissRequest = { showFromPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val picked = state.selectedDateMillis
                    if (picked != null) {
                        fromMillis = picked
                        // Clamp To forward so to_date >= from_date always holds.
                        if (toMillis != null && toMillis!! < picked) toMillis = picked
                    }
                    showFromPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showFromPicker = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = AppTheme.colors.accentOrange)
                ) { Text("Cancel") }
            }
        ) { DatePicker(state = state) }
    }

    // To picker: earliest selectable date is the later of today and From
    if (showToPicker) {
        val minDate = maxOf(todayUtcMidnightMillis(), fromMillis ?: todayUtcMidnightMillis())
        val state = rememberDatePickerState(
            initialSelectedDateMillis = toMillis ?: minDate,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= minDate
            }
        )
        DatePickerDialog(
            onDismissRequest = { showToPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { toMillis = it }
                    showToPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showToPicker = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = AppTheme.colors.accentOrange)
                ) { Text("Cancel") }
            }
        ) { DatePicker(state = state) }
    }
}

// A tappable date chip in the form ("From" / "To")
@Composable
private fun LeaveDateChip(
    label: String,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = AppTheme.colors.background,
        border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, fontSize = 12.sp, color = AppTheme.colors.textSecondary)
            if (text.isNotBlank()) {
                Text(
                    text = text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) AppTheme.colors.textPrimary else AppTheme.colors.textMuted
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Leave history: list of applications with status badges, expandable reviewer
// feedback, and the cancel action for still-pending requests.
// ---------------------------------------------------------------------------
@Composable
fun LeaveHistoryTracker(
    applications: List<LeaveApplicationResponse>,
    isLoading: Boolean,
    isCancelling: Boolean,
    onCancel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingCancelId by remember { mutableStateOf<String?>(null) }

    // Newest first by applied/created time
    val ordered = remember(applications) {
        applications.sortedByDescending { it.createdAt ?: "" }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "My applications",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppTheme.colors.textPrimary
        )

        when {
            isLoading -> {
                repeat(3) { LeaveSkeletonCard() }
            }
            ordered.isEmpty() -> LeaveEmptyState()
            else -> {
                ordered.forEach { app ->
                    LeaveHistoryCard(
                        app = app,
                        isCancelling = isCancelling,
                        onCancelRequest = { pendingCancelId = app.id }
                    )
                }
            }
        }
    }

    // Cancel confirmation: only reachable for PENDING requests.
    pendingCancelId?.let { id ->
        val target = applications.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { pendingCancelId = null },
            title = { Text("Cancel this leave?", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Text(
                    text = "Your request for ${prettyLeaveDate(target?.fromDate ?: "")} → " +
                            "${prettyLeaveDate(target?.toDate ?: "")} will be withdrawn and marked CANCELLED. " +
                            "This can't be undone.",
                    fontSize = 14.sp,
                    color = AppTheme.colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCancel(id)
                        pendingCancelId = null
                    },
                    enabled = !isCancelling
                ) {
                    if (isCancelling) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Yes, cancel it", color = AppTheme.colors.dangerRed, fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingCancelId = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = AppTheme.colors.accentOrange)
                ) { Text("Keep request") }
            },
            containerColor = AppTheme.colors.surface
        )
    }
}

// One history entry: date range, status (+ cancel for PENDING) on the same row,
// the applicant reason, and expandable reviewer feedback.
@Composable
private fun LeaveHistoryCard(
    app: LeaveApplicationResponse,
    isCancelling: Boolean,
    onCancelRequest: () -> Unit
) {
    var feedbackExpanded by remember { mutableStateOf(false) }
    val hasFeedback = !app.reviewComment.isNullOrBlank()
    val isRejected = app.status.equals("REJECTED", true)
    val isPending = app.status.equals("PENDING", true)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = AppTheme.colors.surface,
        border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header: date range, with status and (when pending) cancel side by side
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (app.fromDate == app.toDate) prettyLeaveDate(app.fromDate)
                    else "${prettyLeaveDate(app.fromDate)} → ${prettyLeaveDate(app.toDate)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LeaveStatusBadge(app.status)
                    if (isPending) {
                        Surface(
                            onClick = { if (!isCancelling) onCancelRequest() },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isCancelling) AppTheme.colors.surfaceBorder else AppTheme.colors.dangerRed,
                        ) {
                            Text(
                                text = if (isCancelling) "…" else "Cancel",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            // Applicant reason
            if (app.reason.isNotBlank()) {
                Text(
                    text = app.reason,
                    fontSize = 13.sp,
                    color = AppTheme.colors.textSecondary,
                    maxLines = 3
                )
            }

            // Reviewer feedback: tap to expand full note + reviewer/time.
            if (hasFeedback) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isRejected) AppTheme.colors.dangerRedBg else AppTheme.colors.background,
                    border = BorderStroke(
                        1.dp,
                        (if (isRejected) AppTheme.colors.dangerRed else AppTheme.colors.surfaceBorder).copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { feedbackExpanded = !feedbackExpanded }
                ) {
                    Column(
                        modifier = Modifier
                            .padding(10.dp)
                            .animateContentSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isRejected) "Why it was rejected" else "HOD feedback",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isRejected) AppTheme.colors.dangerRed else AppTheme.colors.textSecondary
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = if (feedbackExpanded) "Show less" else "Show full feedback",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isRejected) AppTheme.colors.dangerRed else AppTheme.colors.primaryPurple
                            )
                        }
                        Text(
                            text = app.reviewComment.orEmpty(),
                            fontSize = 13.sp,
                            fontWeight = if (isRejected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isRejected) AppTheme.colors.dangerRed else AppTheme.colors.textPrimary,
                            maxLines = if (feedbackExpanded) Int.MAX_VALUE else 2
                        )
                        if (feedbackExpanded) {
                            Text(
                                text = buildString {
                                    val reviewer = app.reviewerName
                                    if (!reviewer.isNullOrBlank()) append("— $reviewer")
                                    val reviewed = prettyLeaveTimestamp(app.reviewedAt)
                                    if (reviewed.isNotBlank()) {
                                        if (isNotBlank()) append(" · ")
                                        append(reviewed)
                                    }
                                },
                                fontSize = 11.sp,
                                color = AppTheme.colors.textMuted
                            )
                        }
                    }
                }
            }
        }
    }
}

// Status badge: PENDING amber / APPROVED green / REJECTED red / CANCELLED gray,
// rendered with white text. internal: shared with the HOD queue cards.
@Composable
internal fun LeaveStatusBadge(status: String) {
    val bg = when (status.uppercase(Locale.US)) {
        "APPROVED" -> AppTheme.colors.successGreen
        "REJECTED" -> AppTheme.colors.dangerRed
        "CANCELLED" -> AppTheme.colors.textMuted
        else -> AppTheme.colors.warningAmber // PENDING
    }
    Surface(shape = RoundedCornerShape(8.dp), color = bg) {
        Text(
            text = status.uppercase(Locale.US),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

// Pulsing placeholder card shown while history loads
@Composable
private fun LeaveSkeletonCard() {
    val transition = rememberInfiniteTransition(label = "leave_skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "leave_skeleton_alpha"
    )
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = AppTheme.colors.surface,
        border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(14.dp)
                    .alpha(alpha)
                    .background(AppTheme.colors.surfaceBorder, RoundedCornerShape(7.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(12.dp)
                    .alpha(alpha)
                    .background(AppTheme.colors.surfaceBorder.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .alpha(alpha)
                    .background(AppTheme.colors.surfaceBorder.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            )
        }
    }
}

// Empty state: no leave applications yet
@Composable
private fun LeaveEmptyState() {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = AppTheme.colors.surface,
        border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.EventNote,
                contentDescription = null,
                tint = AppTheme.colors.textMuted,
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = "No leaves yet",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.colors.textPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Submit your first application using the form above.",
                fontSize = 12.sp,
                color = AppTheme.colors.textMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ---------------------------------------------------------------------------
// HOD review dialog: collects reviewer feedback. Optional on APPROVED,
// mandatory on REJECTED — the confirm button stays disabled until a non-blank
// remark exists for a rejection, matching the server-side guard.
// ---------------------------------------------------------------------------
@Composable
fun LeaveReviewDialog(
    app: LeaveApplicationResponse,
    isReviewing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (decision: String, comment: String?) -> Unit
) {
    var decision by remember { mutableStateOf("APPROVED") }
    var comment by remember { mutableStateOf("") }

    val commentTrimmed = comment.trim()
    val isRejection = decision == "REJECTED"
    val feedbackMissing = isRejection && commentTrimmed.isEmpty()
    val canConfirm = !isReviewing && !feedbackMissing

    AlertDialog(
        onDismissRequest = { if (!isReviewing) onDismiss() },
        title = { Text("Review leave request", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = buildString {
                        append(app.studentName ?: "Student")
                        if (!app.studentRoll.isNullOrBlank()) append(" (${app.studentRoll})")
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.textPrimary
                )
                Text(
                    text = if (app.fromDate == app.toDate) prettyLeaveDate(app.fromDate)
                    else "${prettyLeaveDate(app.fromDate)} → ${prettyLeaveDate(app.toDate)}",
                    fontSize = 13.sp,
                    color = AppTheme.colors.textSecondary
                )
                if (app.reason.isNotBlank()) {
                    Text(
                        text = "“${app.reason}”",
                        fontSize = 13.sp,
                        color = AppTheme.colors.textSecondary
                    )
                }

                // Decision toggle
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        onClick = { decision = "APPROVED" },
                        shape = RoundedCornerShape(10.dp),
                        color = if (!isRejection) AppTheme.colors.successGreen.copy(alpha = 0.15f) else AppTheme.colors.background,
                        border = BorderStroke(
                            1.dp,
                            if (!isRejection) AppTheme.colors.successGreen else AppTheme.colors.surfaceBorder
                        ),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "Approve",
                                fontWeight = FontWeight.SemiBold,
                                color = if (!isRejection) AppTheme.colors.successGreen else AppTheme.colors.textSecondary
                            )
                        }
                    }
                    Surface(
                        onClick = { decision = "REJECTED" },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isRejection) AppTheme.colors.dangerRed.copy(alpha = 0.12f) else AppTheme.colors.background,
                        border = BorderStroke(
                            1.dp,
                            if (isRejection) AppTheme.colors.dangerRed else AppTheme.colors.surfaceBorder
                        ),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "Reject",
                                fontWeight = FontWeight.SemiBold,
                                color = if (isRejection) AppTheme.colors.dangerRed else AppTheme.colors.textSecondary
                            )
                        }
                    }
                }

                // Feedback: optional on approval, required on rejection
                OutlinedTextField(
                    value = comment,
                    onValueChange = { if (it.length <= 500) comment = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (isRejection) "Feedback (required)" else "Feedback (optional)") },
                    placeholder = {
                        Text(
                            if (isRejection) "Tell the student why this is rejected"
                            else "Optional note for the student",
                            fontSize = 13.sp
                        )
                    },
                    isError = feedbackMissing,
                    supportingText = {
                        Text(
                            text = when {
                                feedbackMissing -> "A remark is required when rejecting"
                                else -> "${comment.length}/500"
                            },
                            fontSize = 12.sp,
                            color = if (feedbackMissing) AppTheme.colors.dangerRed else AppTheme.colors.textMuted
                        )
                    },
                    minLines = 2,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.colors.primaryPurple,
                        unfocusedBorderColor = AppTheme.colors.surfaceBorder,
                        focusedLabelColor = AppTheme.colors.primaryPurple,
                        cursorColor = AppTheme.colors.primaryPurple
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(decision, commentTrimmed.takeIf { it.isNotBlank() }) },
                enabled = canConfirm,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRejection) AppTheme.colors.dangerRed else AppTheme.colors.successGreen
                )
            ) {
                if (isReviewing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(if (isRejection) "Reject" else "Approve", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isReviewing,
                colors = ButtonDefaults.textButtonColors(contentColor = AppTheme.colors.accentOrange)
            ) { Text("Cancel") }
        },
        containerColor = AppTheme.colors.surface
    )
}
