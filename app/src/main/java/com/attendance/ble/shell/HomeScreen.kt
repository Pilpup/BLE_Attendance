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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.college.feature.library.LibraryDashboardCard

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
                onClick = { onNavigate(AttendanceDashboardCard.NAV_ROUTE) },
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
                onClick = { onNavigate(LibraryDashboardCard.NAV_ROUTE) },
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
                    user.department?.let {
                        Text(text = "Branch: $it", fontSize = 13.sp, color = Color(0xFFCBD5E1))
                    }
                    user.year?.let {
                        Text(text = "Year: $it Year", fontSize = 13.sp, color = Color(0xFFCBD5E1))
                    }
                }

                HorizontalDivider(color = ObsidianBorder)

                Text(
                    text = "QUICK ACCOUNT SWITCH",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = ObsidianTextSecondary
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MockUsers.all.forEach { mock ->
                        val isCurrent = mock.id == user.id
                        OutlinedButton(
                            onClick = {
                                SessionManager.default.startSession(mock, MockUsers.MOCK_DEV_TOKEN)
                                onDismiss()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isCurrent) ObsidianAccent else ObsidianCardAlt,
                                contentColor = if (isCurrent) Color.White else ObsidianTextPrimary
                            ),
                            border = BorderStroke(1.dp, if (isCurrent) ObsidianAccentGlow else ObsidianBorderHighlight),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(text = mock.name, fontSize = 11.sp)
                        }
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
@Composable
private fun UserAuthDialog(
    onDismiss: () -> Unit
) {
    var isRegisterMode by remember { mutableStateOf(false) }

    // Form inputs
    var nameInput by remember { mutableStateOf("") }
    var emailInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var rollNumberInput by remember { mutableStateOf("") }
    var courseInput by remember { mutableStateOf("") }
    var branchInput by remember { mutableStateOf("") }
    var yearInput by remember { mutableStateOf("") }
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
            // Clean Segmented Tabs: Sign In vs Register
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ObsidianCardAlt, RoundedCornerShape(10.dp))
                    .padding(4.dp)
            ) {
                Surface(
                    onClick = { isRegisterMode = false },
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
                    onClick = { isRegisterMode = true },
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
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!isRegisterMode) {
                    // ---- Sign In Form -------------------------------------
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text("Email") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    // ---- Full Registration Form ---------------------------
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Full Name") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text("Email") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = rollNumberInput,
                        onValueChange = { rollNumberInput = it },
                        label = { Text("Roll Number / Employee ID") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Role Dropdown (Student, Teacher, Staff) immediately after Employee ID / Roll Number
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

                    // Academic details (Course, Branch, Year) only appear for Students
                    if (selectedRole == Role.STUDENT) {
                        // Course Selection (B.Tech or BCA) with tightened gap between title and chips
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Course",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ObsidianTextSecondary
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf("B.Tech", "BCA").forEach { c ->
                                    val selected = courseInput == c
                                    Surface(
                                        onClick = {
                                            // Tapping active chip deselects it (empty option)
                                            courseInput = if (selected) "" else c
                                            if (c == "BCA" && yearInput == "4th") {
                                                yearInput = ""
                                            }
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (selected) ObsidianAccent else ObsidianCardAlt,
                                        border = BorderStroke(1.dp, if (selected) ObsidianAccentGlow else ObsidianBorderHighlight),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = c,
                                                fontSize = 13.sp,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (selected) Color.White else ObsidianTextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Branch Dropdown (Hidden when BCA is selected)
                        if (courseInput != "BCA") {
                            AppDropdownField(
                                label = "Branch",
                                selectedValue = branchInput,
                                options = listOf("CSE", "ECE", "ME", "CIVIL", "IT"),
                                onSelect = { branchInput = it },
                                placeholder = "Select Branch",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Year Dropdown (Year 4 disappears when BCA is selected)
                        val availableYears = if (courseInput == "BCA") {
                            listOf("1st Year", "2nd Year", "3rd Year")
                        } else {
                            listOf("1st Year", "2nd Year", "3rd Year", "4th Year")
                        }

                        AppDropdownField(
                            label = "Year",
                            selectedValue = if (yearInput.isNotBlank()) "$yearInput Year" else "",
                            options = availableYears,
                            onSelect = { selected ->
                                yearInput = selected.substringBefore(" Year")
                            },
                            placeholder = "Select Year",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Secret token required for Teacher and Staff
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
            }
        },
        confirmButton = {
            if (!isRegisterMode) {
                Button(
                    onClick = {
                        if (emailInput.isNotBlank()) {
                            val newUser = User(
                                id = "user-" + System.currentTimeMillis(),
                                name = emailInput.substringBefore("@").replace(".", " ").capitalizeWords(),
                                email = emailInput.trim(),
                                roles = setOf(Role.STUDENT)
                            )
                            SessionManager.default.startSession(newUser, MockUsers.MOCK_DEV_TOKEN)
                            onDismiss()
                        }
                    },
                    enabled = emailInput.isNotBlank() && passwordInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianAccent)
                ) {
                    Text("Sign In", color = Color.White)
                }
            } else {
                val isRoleValid = selectedRole != null
                val isAcademicValid = if (selectedRole == Role.STUDENT) {
                    courseInput.isNotBlank() &&
                    (if (courseInput == "BCA") true else branchInput.isNotBlank()) &&
                    yearInput.isNotBlank()
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
                        val newUser = User(
                            id = "user-" + System.currentTimeMillis(),
                            name = nameInput.trim(),
                            email = emailInput.trim(),
                            roles = setOf(activeRole),
                            rollNumber = rollNumberInput.trim(),
                            department = if (isStudent) (if (courseInput == "BCA") "BCA" else branchInput) else null,
                            year = if (isStudent) yearInput else null,
                            program = if (isStudent) courseInput else null
                        )
                        SessionManager.default.startSession(newUser, MockUsers.MOCK_DEV_TOKEN)
                        onDismiss()
                    },
                    enabled = canRegister,
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianAccent)
                ) {
                    Text("Register", color = Color.White)
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

/**
 * Lightweight, accessible dropdown selector that strictly opens downwards in Obsidian Dark.
 */
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

@Composable
internal fun MockRoleSelector(onSelect: (User) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    )
}
