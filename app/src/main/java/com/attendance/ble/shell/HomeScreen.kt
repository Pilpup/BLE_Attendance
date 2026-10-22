package com.attendance.ble.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.college.core.model.Role
import com.college.core.model.User
import com.college.core.network.SessionManager
import com.college.feature.attendance.AttendanceDashboardCard
import com.college.feature.attendance.network.AttendanceApiClient
import com.college.feature.attendance.network.LoginRequest
import com.college.feature.attendance.network.RegisterRequest
import com.college.feature.attendance.network.ResendOtpRequest
import com.college.feature.attendance.network.VerifyOtpRequest
import com.college.feature.library.LibraryDashboardCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Highly optimized, lightweight Home Screen for all devices (including older/slower phones).
 * Shows a sleek Deep Midnight / Obsidian Dark interface with an accessible top bar profile icon.
 */

// Deep Midnight / Obsidian Dark Design Tokens
private val ObsidianBg = Color(0xFF0B0F17) // Sleek AMOLED-friendly obsidian void
private val ObsidianCard = Color(0xFF131B2A) // Card & dialog surface
private val ObsidianCardAlt = Color(0xFF0F172A) // Inset surface / input background
private val ObsidianBorder = Color(0xFF1E293B) // Structural divider & border
private val ObsidianBorderHighlight = Color(0xFF334155) // Active outline
private val ObsidianTextPrimary = Color(0xFFF8FAFC) // Luminescent white
private val ObsidianTextSecondary = Color(0xFF94A3B8) // Slate 400
private val ObsidianAccent = Color(0xFF2563EB) // Electric ultramarine
private val ObsidianAccentGlow = Color(0xFF3B82F6) // Bright blue

@Composable
private fun darkTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = ObsidianTextPrimary,
    unfocusedTextColor = ObsidianTextPrimary,
    focusedContainerColor = ObsidianCardAlt,
    unfocusedContainerColor = ObsidianCardAlt,
    focusedBorderColor = ObsidianAccentGlow,
    unfocusedBorderColor = ObsidianBorderHighlight,
    focusedLabelColor = ObsidianAccentGlow,
    unfocusedLabelColor = ObsidianTextSecondary,
    cursorColor = ObsidianAccentGlow
)

@Composable
fun HomeScreen(
    user: User?,
    onNavigate: (String) -> Unit
) {
    var showAccountDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .systemBarsPadding()
    ) {
        // ---- Top Bar: Title & User Profile Icon -----------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "College Hub",
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = ObsidianTextPrimary
            )

            // Right side: Role badge next to profile icon + User Profile Icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (user != null) {
                    val activeRole = user.primaryRole ?: user.roles.firstOrNull() ?: Role.STUDENT
                    val roleLabel = when (activeRole) {
                        Role.TEACHER -> "Teacher"
                        Role.STUDENT -> "Student"
                        Role.STAFF -> "Staff"
                        Role.DEVELOPER -> "Developer"
                    }
                    Surface(
                        onClick = { showAccountDialog = true },
                        shape = RoundedCornerShape(20.dp),
                        color = roleBadgeBg(activeRole),
                        border = BorderStroke(1.dp, roleBadgeColor(activeRole).copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = roleLabel,
                            color = roleBadgeColor(activeRole),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                // User Profile Icon (Tapping opens user details / sign in)
                Surface(
                    onClick = { showAccountDialog = true },
                    shape = CircleShape,
                    color = if (user != null) ObsidianAccent else ObsidianCard,
                    shadowElevation = 2.dp,
                    border = BorderStroke(1.5.dp, if (user != null) ObsidianAccentGlow else ObsidianBorderHighlight),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (user != null) {
                            Text(
                                text = user.name.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = "User Profile / Sign In",
                                tint = ObsidianTextSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }

        // ---- Two Feature Options in One Row --------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Attendance
            Surface(
                onClick = {
                    if (user != null) {
                        onNavigate(AttendanceDashboardCard.NAV_ROUTE)
                    } else {
                        showAccountDialog = true
                    }
                },
                shape = RoundedCornerShape(16.dp),
                color = ObsidianCard,
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 22.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color(0xFF1E3A8A), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.FactCheck,
                            contentDescription = "Attendance",
                            tint = Color(0xFF60A5FA),
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Attendance",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = ObsidianTextPrimary
                    )
                }
            }

            // Library
            Surface(
                onClick = {
                    if (user != null) {
                        onNavigate(LibraryDashboardCard.NAV_ROUTE)
                    } else {
                        showAccountDialog = true
                    }
                },
                shape = RoundedCornerShape(16.dp),
                color = ObsidianCard,
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 22.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color(0xFF064E3B), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = "Library",
                            tint = Color(0xFF34D399),
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Library",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = ObsidianTextPrimary
                    )
                }
            }
        }
    }

    // ---- User Details or Sign In / Register Dialog --------------------------
    if (showAccountDialog) {
        if (user != null) {
            UserDetailsDialog(
                user = user,
                onDismiss = { showAccountDialog = false }
            )
        } else {
            UserAuthDialog(
                onDismiss = { showAccountDialog = false }
            )
        }
    }
}

/**
 * Dialog displaying active user details and quick account switching in Obsidian Dark.
 */
@Composable
private fun UserDetailsDialog(
    user: User,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianCard,
        titleContentColor = ObsidianTextPrimary,
        textContentColor = ObsidianTextPrimary,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(ObsidianAccent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = user.name.take(1).uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }
                Column {
                    Text(
                        text = user.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = ObsidianTextPrimary
                    )
                    Text(
                        text = user.email,
                        fontSize = 12.sp,
                        color = ObsidianTextSecondary
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HorizontalDivider(color = ObsidianBorder)

                Text(
                    text = "ACTIVE ROLES",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = ObsidianTextSecondary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    user.roles.forEach { role ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = roleBadgeBg(role)
                        ) {
                            Text(
                                text = role.name,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = roleBadgeColor(role),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // Show Academic Info if recorded
                if (!user.rollNumber.isNullOrBlank() || !user.department.isNullOrBlank() || !user.year.isNullOrBlank()) {
                    HorizontalDivider(color = ObsidianBorder)

                    Text(
                        text = "ACADEMIC DETAILS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = ObsidianTextSecondary
                    )

                    user.program?.let {
                        Text(text = "Course: $it", fontSize = 13.sp, color = Color(0xFFCBD5E1))
                    }
                    user.rollNumber?.let {
                        Text(text = "ID / Roll No: $it", fontSize = 13.sp, color = Color(0xFFCBD5E1))
                    }
                    user.program?.let {
                        Text(text = "Course: $it", fontSize = 13.sp, color = Color(0xFFCBD5E1))
                    }
                    user.department?.let {
                        Text(text = "Branch: $it", fontSize = 13.sp, color = Color(0xFFCBD5E1))
                    }
                    user.semester?.let {
                        Text(text = "Semester: $it", fontSize = 13.sp, color = Color(0xFFCBD5E1))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    SessionManager.default.endSession()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
            ) {
                Text("Sign Out", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = ObsidianTextSecondary)
            }
        }
    )
}

/**
 * Clean Auth Dialog in Obsidian Dark with dedicated Sign In and Register tabs.
 */
private enum class AuthStep {
    CREDENTIALS,
    OTP_VERIFICATION
}

@Composable
private fun UserAuthDialog(
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var currentStep by remember { mutableStateOf(AuthStep.CREDENTIALS) }
    var isRegisterMode by remember { mutableStateOf(false) }
    var isAuthLoading by remember { mutableStateOf(false) }
    var authErrorText by remember { mutableStateOf<String?>(null) }
    var pendingEmail by remember { mutableStateOf("") }
    var otpInput by remember { mutableStateOf("") }
    var resendCooldown by remember { mutableIntStateOf(0) }

    fun startCooldownTimer() {
        resendCooldown = 45
        scope.launch {
            while (resendCooldown > 0) {
                delay(1000L)
                resendCooldown -= 1
            }
        }
    }

    // Form inputs
    var nameInput by remember { mutableStateOf("") }
    var emailInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var rollNumberInput by remember { mutableStateOf("") }
    var courseInput by remember { mutableStateOf("") }
    var branchInput by remember { mutableStateOf("") }
    var semesterInput by remember { mutableStateOf("") }
    var roleInput by remember { mutableStateOf("") }
    var secretTokenInput by remember { mutableStateOf("") }

    val selectedRole = when (roleInput) {
        "Student" -> Role.STUDENT
        "Teacher" -> Role.TEACHER
        "Staff" -> Role.STAFF
        else -> null
    }

    val fieldColors = darkTextFieldColors()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianCard,
        titleContentColor = ObsidianTextPrimary,
        textContentColor = ObsidianTextPrimary,
        title = {
            if (currentStep == AuthStep.CREDENTIALS) {
                // Clean Segmented Tabs: Sign In vs Register
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianCardAlt, RoundedCornerShape(10.dp))
                        .padding(4.dp)
                ) {
                    Surface(
                        onClick = {
                            isRegisterMode = false
                            authErrorText = null
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = if (!isRegisterMode) ObsidianBorderHighlight else Color.Transparent,
                        shadowElevation = if (!isRegisterMode) 1.dp else 0.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Sign In",
                                fontWeight = if (!isRegisterMode) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 14.sp,
                                color = if (!isRegisterMode) ObsidianTextPrimary else ObsidianTextSecondary
                            )
                        }
                    }

                    Surface(
                        onClick = {
                            isRegisterMode = true
                            authErrorText = null
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isRegisterMode) ObsidianBorderHighlight else Color.Transparent,
                        shadowElevation = if (isRegisterMode) 1.dp else 0.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Register",
                                fontWeight = if (isRegisterMode) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 14.sp,
                                color = if (isRegisterMode) ObsidianTextPrimary else ObsidianTextSecondary
                            )
                        }
                    }
                }
            } else {
                // OTP Step Title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isRegisterMode) "Verify Email" else "Two-Factor Auth",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = ObsidianTextPrimary
                    )
                    TextButton(
                        onClick = {
                            currentStep = AuthStep.CREDENTIALS
                            authErrorText = null
                            otpInput = ""
                        }
                    ) {
                        Text("Edit", color = ObsidianAccentGlow, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        text = {
            if (currentStep == AuthStep.CREDENTIALS) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!isRegisterMode) {
                        // Sign In Fields: Email & Password
                        OutlinedTextField(
                            value = emailInput,
                            onValueChange = { emailInput = it },
                            label = { Text("Email Address") },
                            placeholder = { Text("e.g. name@college.edu") },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text("Password") },
                            placeholder = { Text("Enter your password") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        // Register Fields
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            label = { Text("Full Name") },
                            placeholder = { Text("e.g. Rahul Sharma") },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = emailInput,
                            onValueChange = { emailInput = it },
                            label = { Text("Email Address") },
                            placeholder = { Text("e.g. name@college.edu") },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text("Password") },
                            placeholder = { Text("Min. 6 characters") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )

                        val rollLabel = when (selectedRole) {
                            Role.TEACHER -> "Employee ID"
                            Role.STAFF -> "Staff ID"
                            else -> "Roll Number"
                        }
                        val rollPlaceholder = when (selectedRole) {
                            Role.TEACHER -> "e.g. EMP-1042"
                            Role.STAFF -> "e.g. STF-801"
                            else -> "e.g. 251430101019"
                        }
                        OutlinedTextField(
                            value = rollNumberInput,
                            onValueChange = { rollNumberInput = it },
                            label = { Text(rollLabel) },
                            placeholder = { Text(rollPlaceholder) },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )

                        AppDropdownField(
                            label = "Role",
                            selectedValue = roleInput,
                            options = listOf("Student", "Teacher", "Staff"),
                            onSelect = { selectedName ->
                                roleInput = selectedName
                            },
                            placeholder = "Select Role",
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (selectedRole == Role.STUDENT) {
                            AppDropdownField(
                                label = "Course",
                                selectedValue = courseInput,
                                options = listOf("B-Tech", "BCA"),
                                onSelect = { selectedCourse ->
                                    courseInput = selectedCourse
                                    if (selectedCourse == "BCA") {
                                        branchInput = ""
                                    }
                                },
                                placeholder = "Select Course",
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (courseInput == "B-Tech") {
                                AppDropdownField(
                                    label = "Branch",
                                    selectedValue = branchInput,
                                    options = listOf("CSE", "IT", "ECE", "ME", "CE", "EE"),
                                    onSelect = { selectedBranch ->
                                        branchInput = selectedBranch
                                    },
                                    placeholder = "Select Branch",
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            val availableSemesters = (1..8).map { "Semester $it" }
                            AppDropdownField(
                                label = "Semester",
                                selectedValue = semesterInput,
                                options = availableSemesters,
                                onSelect = { selected ->
                                    semesterInput = selected
                                },
                                placeholder = "Select Semester",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (selectedRole == Role.TEACHER || selectedRole == Role.STAFF) {
                            OutlinedTextField(
                                value = secretTokenInput,
                                onValueChange = { secretTokenInput = it },
                                label = { Text("Secret Token / Passcode") },
                                placeholder = { Text("Enter faculty / staff passcode") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                colors = fieldColors,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    if (authErrorText != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFEF4444).copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = authErrorText!!,
                                color = Color(0xFFFCA5A5),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            } else {
                // OTP Step Content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "We sent a 6-digit verification code to:",
                        fontSize = 13.sp,
                        color = ObsidianTextSecondary,
                        textAlign = TextAlign.Center
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = ObsidianCardAlt,
                        border = BorderStroke(1.dp, ObsidianBorderHighlight)
                    ) {
                        Text(
                            text = pendingEmail,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = ObsidianAccentGlow,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }

                    OutlinedTextField(
                        value = otpInput,
                        onValueChange = {
                            if (it.length <= 6 && it.all { ch -> ch.isDigit() }) {
                                otpInput = it
                            }
                        },
                        placeholder = {
                            Text(
                                "• • • • • •",
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                                fontSize = 22.sp,
                                letterSpacing = 6.sp,
                                color = ObsidianTextSecondary.copy(alpha = 0.5f)
                            )
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                            letterSpacing = 6.sp,
                            color = ObsidianTextPrimary
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (resendCooldown > 0) {
                            Text(
                                text = "Resend code in ${resendCooldown}s",
                                fontSize = 12.sp,
                                color = ObsidianTextSecondary
                            )
                        } else {
                            TextButton(
                                onClick = {
                                    isAuthLoading = true
                                    authErrorText = null
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val res = AttendanceApiClient.service.resendOtp(
                                                ResendOtpRequest(
                                                    email = pendingEmail,
                                                    purpose = if (isRegisterMode) "REGISTER" else "LOGIN"
                                                )
                                            )
                                            withContext(Dispatchers.Main) {
                                                isAuthLoading = false
                                                if (res.isSuccessful) {
                                                    startCooldownTimer()
                                                } else {
                                                    val err = try { res.errorBody()?.string() } catch (_: Exception) { null }
                                                    authErrorText = "Resend failed: ${err ?: "HTTP ${res.code()}"}"
                                                }
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) {
                                                isAuthLoading = false
                                                authErrorText = "Network error: ${e.localizedMessage ?: "Cannot reach server"}"
                                            }
                                        }
                                    }
                                }
                            ) {
                                Text(
                                    text = "Resend code",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ObsidianAccentGlow
                                )
                            }
                        }
                    }

                    if (authErrorText != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFEF4444).copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = authErrorText!!,
                                color = Color(0xFFFCA5A5),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (currentStep == AuthStep.CREDENTIALS) {
                if (!isRegisterMode) {
                    Button(
                        onClick = {
                            val email = emailInput.trim()
                            val password = passwordInput
                            if (email.isNotBlank() && password.isNotBlank()) {
                                isAuthLoading = true
                                authErrorText = null
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val res = AttendanceApiClient.service.login(LoginRequest(email, password))
                                        withContext(Dispatchers.Main) {
                                            isAuthLoading = false
                                            if (res.isSuccessful && res.body() != null) {
                                                val body = res.body()!!
                                                if (body.requiresOtp) {
                                                    pendingEmail = body.email
                                                    currentStep = AuthStep.OTP_VERIFICATION
                                                    otpInput = ""
                                                    authErrorText = null
                                                    startCooldownTimer()
                                                } else {
                                                    val u = body.user
                                                    val tok = body.accessToken
                                                    if (tok != null && u != null) {
                                                        val role = Role.fromBackendName(u.role) ?: Role.STUDENT
                                                        val authenticatedUser = User(
                                                            id = u.id,
                                                            name = u.name,
                                                            email = u.email,
                                                            roles = setOf(role),
                                                            rollNumber = u.rollNumber,
                                                            department = u.department,
                                                            program = u.program,
                                                            semester = u.semester,
                                                            section = u.section
                                                        )
                                                        SessionManager.default.startSession(authenticatedUser, tok)
                                                        onDismiss()
                                                    }
                                                }
                                            } else {
                                                val err = try { res.errorBody()?.string() } catch (_: Exception) { null }
                                                authErrorText = "Sign In failed: HTTP ${res.code()}${if (!err.isNullOrBlank()) " - $err" else ""}"
                                            }
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            isAuthLoading = false
                                            authErrorText = "Network error: ${e.localizedMessage ?: "Cannot reach server"}"
                                        }
                                    }
                                }
                            }
                        },
                        enabled = !isAuthLoading && emailInput.isNotBlank() && passwordInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = ObsidianAccent)
                    ) {
                        if (isAuthLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("Sign In", color = Color.White)
                        }
                    }
                } else {
                    val isRoleValid = selectedRole != null
                    val isAcademicValid = if (selectedRole == Role.STUDENT) {
                        courseInput.isNotBlank() &&
                        (if (courseInput == "BCA") true else branchInput.isNotBlank()) &&
                        semesterInput.isNotBlank()
                    } else true
                    val isSecretValid = if (selectedRole == Role.TEACHER || selectedRole == Role.STAFF) {
                        secretTokenInput.isNotBlank()
                    } else true
                    val canRegister = nameInput.isNotBlank() &&
                            emailInput.isNotBlank() &&
                            passwordInput.isNotBlank() &&
                            rollNumberInput.isNotBlank() &&
                            isRoleValid &&
                            isAcademicValid &&
                            isSecretValid

                    Button(
                        onClick = {
                            val activeRole = selectedRole ?: Role.STUDENT
                            val isStudent = activeRole == Role.STUDENT
                            val semNumber = semesterInput.substringAfter("Semester ").trim().toIntOrNull()
                            val rawEmail = emailInput.trim()
                            val cleanEmail = if (rawEmail.endsWith("@mgail.com", ignoreCase = true)) {
                                rawEmail.replace("@mgail.com", "@gmail.com", ignoreCase = true)
                            } else if (rawEmail.endsWith("@gamil.com", ignoreCase = true)) {
                                rawEmail.replace("@gamil.com", "@gmail.com", ignoreCase = true)
                            } else rawEmail
                            pendingEmail = cleanEmail
                            val req = RegisterRequest(
                                email = cleanEmail,
                                password = passwordInput,
                                name = nameInput.trim(),
                                role = activeRole.name,
                                rollNumber = rollNumberInput.trim(),
                                department = if (isStudent) (if (courseInput == "BCA") "BCA" else branchInput) else "Faculty",
                                program = if (isStudent) courseInput else null,
                                semester = if (isStudent) semNumber else null,
                                section = null,
                                facultyCode = if (activeRole == Role.TEACHER || activeRole == Role.STAFF) secretTokenInput.trim() else null
                            )
                            isAuthLoading = true
                            authErrorText = null
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val res = AttendanceApiClient.service.register(req)
                                    withContext(Dispatchers.Main) {
                                        isAuthLoading = false
                                        if (res.isSuccessful && res.body() != null) {
                                            pendingEmail = res.body()!!.email
                                            currentStep = AuthStep.OTP_VERIFICATION
                                            otpInput = ""
                                            authErrorText = null
                                            startCooldownTimer()
                                        } else {
                                            val err = try { res.errorBody()?.string() } catch (_: Exception) { null }
                                            authErrorText = "Registration failed: HTTP ${res.code()}${if (!err.isNullOrBlank()) " - $err" else ""}"
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        isAuthLoading = false
                                        authErrorText = "Network error: ${e.localizedMessage ?: "Cannot reach server"}"
                                    }
                                }
                            }
                        },
                        enabled = !isAuthLoading && canRegister,
                        colors = ButtonDefaults.buttonColors(containerColor = ObsidianAccent)
                    ) {
                        if (isAuthLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("Continue", color = Color.White)
                        }
                    }
                }
            } else {
                // Verify OTP Button
                Button(
                    onClick = {
                        val code = otpInput.trim()
                        if (code.length == 6) {
                            isAuthLoading = true
                            authErrorText = null
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val res = if (isRegisterMode) {
                                        AttendanceApiClient.service.verifyRegisterOtp(VerifyOtpRequest(pendingEmail, code))
                                    } else {
                                        AttendanceApiClient.service.verifyLoginOtp(VerifyOtpRequest(pendingEmail, code))
                                    }
                                    withContext(Dispatchers.Main) {
                                        isAuthLoading = false
                                        if (res.isSuccessful && res.body() != null) {
                                            val body = res.body()!!
                                            val role = Role.fromBackendName(body.user.role) ?: Role.STUDENT
                                            val authenticatedUser = User(
                                                id = body.user.id,
                                                name = body.user.name,
                                                email = body.user.email,
                                                roles = setOf(role),
                                                rollNumber = body.user.rollNumber,
                                                department = body.user.department,
                                                program = body.user.program,
                                                semester = body.user.semester,
                                                section = body.user.section
                                            )
                                            SessionManager.default.startSession(authenticatedUser, body.accessToken)
                                            onDismiss()
                                        } else {
                                            val err = try { res.errorBody()?.string() } catch (_: Exception) { null }
                                            authErrorText = "Verification failed: HTTP ${res.code()}${if (!err.isNullOrBlank()) " - $err" else ""}"
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        isAuthLoading = false
                                        authErrorText = "Network error: ${e.localizedMessage ?: "Cannot reach server"}"
                                    }
                                }
                            }
                        }
                    },
                    enabled = !isAuthLoading && otpInput.length == 6,
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianAccent)
                ) {
                    if (isAuthLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text(
                            text = if (isRegisterMode) "Verify & Register" else "Verify & Sign In",
                            color = Color.White
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = ObsidianTextSecondary)
            }
        }
    )
}

@Composable
private fun AppDropdownField(
    label: String,
    selectedValue: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Select $label",
    allowEmpty: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidthPx by remember { mutableStateOf(0) }

    val allOptions = remember(options, allowEmpty) {
        if (allowEmpty && !options.contains("")) listOf("") + options else options
    }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            anchorWidthPx = coordinates.size.width
        }
    ) {
        OutlinedTextField(
            value = selectedValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text(placeholder, color = ObsidianTextSecondary) },
            colors = darkTextFieldColors(),
            trailingIcon = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = "Select $label",
                        tint = ObsidianTextSecondary,
                        modifier = Modifier.rotate(if (expanded) 180f else 0f)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        // Clickable overlay over the field
        Surface(
            onClick = { expanded = !expanded },
            color = Color.Transparent,
            modifier = Modifier.matchParentSize()
        ) {}

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
                        // Always position directly below the anchor (drop DOWN)
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
                    shape = RoundedCornerShape(8.dp),
                    color = ObsidianCard,
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, ObsidianBorderHighlight),
                    modifier = Modifier.width(if (anchorWidthDp > 0.dp) anchorWidthDp else 280.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp)
                    ) {
                        allOptions.forEach { option ->
                            val isSelected = option == selectedValue
                            Surface(
                                onClick = {
                                    onSelect(option)
                                    expanded = false
                                },
                                color = if (isSelected) Color(0xFF1E3A8A) else ObsidianCard,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (option.isEmpty()) "— None (Empty) —" else option,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (option.isEmpty()) ObsidianTextSecondary else if (isSelected) Color(0xFF93C5FD) else ObsidianTextPrimary
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

private fun String.capitalizeWords(): String =
    split(" ").joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }

private fun roleBadgeColor(role: Role): Color = when (role) {
    Role.TEACHER -> Color(0xFF60A5FA)
    Role.STUDENT -> Color(0xFF4ADE80)
    Role.STAFF -> Color(0xFFFBBF24)
    Role.DEVELOPER -> Color(0xFFA78BFA)
}

private fun roleBadgeBg(role: Role): Color = when (role) {
    Role.TEACHER -> Color(0xFF1E293B)
    Role.STUDENT -> Color(0xFF143823)
    Role.STAFF -> Color(0xFF38280B)
    Role.DEVELOPER -> Color(0xFF2E1B4E)
}
