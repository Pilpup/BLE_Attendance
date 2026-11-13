package com.attendance.ble.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.college.core.model.Role
import com.college.core.network.NetworkResult
import com.college.core.network.model.RegisterRequest
import com.college.core.network.repository.AuthRepository
import com.college.core.ui.components.AppDropdownField
import com.college.core.ui.components.AppTextField
import com.college.core.ui.components.appScrollbar
import com.college.core.ui.components.appTextFieldColors
import com.college.core.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Authentication flow steps
private enum class AuthStep {
    CREDENTIALS,
    OTP_VERIFICATION
}

// Dedicated full-screen authentication interface
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    onBack: () -> Unit,
    onAuthSuccess: () -> Unit
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

    var passwordVisible by remember { mutableStateOf(false) }

    val fieldColors = appTextFieldColors()
    val formScrollState = rememberScrollState()

    // Live registration format validation (pure string checks, recomputed on input)
    val studentRollError = if (selectedRole == Role.STUDENT) rollNumberFormatError(rollNumberInput.trim()) else null
    val normalizedEmail = normalizeCollegeEmail(emailInput)
    val emailError = if (emailInput.isNotBlank() && !isEmailValid(normalizedEmail))
        "Invalid email" else null
    val passwordError = if (passwordInput.isNotEmpty() && passwordInput.length < 6)
        "Password must be at least 6 characters" else null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            .systemBarsPadding()
            .imePadding()
    ) {
        // Top navigation bar with back arrow and screen tracker
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    if (currentStep == AuthStep.OTP_VERIFICATION) {
                        currentStep = AuthStep.CREDENTIALS
                        authErrorText = null
                        otpInput = ""
                    } else {
                        onBack()
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = AppTheme.colors.textPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = if (currentStep == AuthStep.OTP_VERIFICATION) {
                    "Check your email"
                } else if (isRegisterMode) {
                    "Create Account"
                } else {
                    "Sign In"
                },
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = AppTheme.colors.textPrimary,
                letterSpacing = (-0.5).sp
            )
        }

        // Scrollable form container
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .appScrollbar(formScrollState)
                .verticalScroll(formScrollState)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (currentStep == AuthStep.CREDENTIALS) {
                // High contrast segmented mode switcher
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = AppTheme.colors.surface,
                    border = null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp)
                    ) {
                        Surface(
                            onClick = {
                                isRegisterMode = false
                                passwordVisible = false
                                authErrorText = null
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (!isRegisterMode) AppTheme.colors.primaryPurple else Color.Transparent,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Sign In",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = if (!isRegisterMode) Color.White else AppTheme.colors.textSecondary
                                )
                            }
                        }

                        Surface(
                            onClick = {
                                isRegisterMode = true
                                passwordVisible = false
                                authErrorText = null
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isRegisterMode) AppTheme.colors.accentOrange else Color.Transparent,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Register",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = if (isRegisterMode) Color.White else AppTheme.colors.textSecondary
                                )
                            }
                        }
                    }
                }

                // Input fields inside card container
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = AppTheme.colors.surface,
                    border = null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (!isRegisterMode) {
                            AppTextField(
                                value = emailInput,
                                onValueChange = { emailInput = it },
                                label = { Text("Email Address") },
                                placeholder = { Text("qwerty@gmail.com") },
                                singleLine = true,
                                isError = emailError != null,
                                colors = fieldColors,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            AppTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text("Password") },
                                placeholder = { Text("Enter your password") },
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = if (passwordVisible) "Hide password" else "Show password"
                                        )
                                    }
                                },
                                colors = fieldColors,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            AppTextField(
                                value = nameInput,
                                onValueChange = { nameInput = it },
                                label = { Text("Full Name") },
                                placeholder = { Text("e.g. Rahul Sharma") },
                                singleLine = true,
                                colors = fieldColors,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            AppTextField(
                                value = emailInput,
                                onValueChange = { emailInput = it },
                                label = { Text("Email Address") },
                                placeholder = { Text("qwerty@gmail.com") },
                                singleLine = true,
                                isError = emailError != null,
                                colors = fieldColors,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            AppTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text("Password") },
                                placeholder = { Text("Min. 6 characters") },
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                singleLine = true,
                                isError = passwordError != null,
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = if (passwordVisible) "Hide password" else "Show password"
                                        )
                                    }
                                },
                                colors = fieldColors,
                                shape = RoundedCornerShape(12.dp),
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

                            // Role-specific fields only shown once role is selected
                            if (selectedRole == Role.STUDENT) {
                                AppTextField(
                                    value = rollNumberInput,
                                    onValueChange = { rollNumberInput = it },
                                    label = { Text("Roll Number") },
                                    placeholder = { Text("e.g. 251340101019") },
                                    singleLine = true,
                                    isError = studentRollError != null,
                                    colors = fieldColors,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

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
                                        options = listOf(
                                            "Computer Science",
                                            "Information Technology",
                                            "Electronics & Communication",
                                            "Mechanical Engineering",
                                            "Civil Engineering",
                                            "Electrical Engineering"
                                        ),
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
                            } else if (selectedRole == Role.TEACHER) {
                                AppTextField(
                                    value = rollNumberInput,
                                    onValueChange = { rollNumberInput = it },
                                    label = { Text("Employee ID") },
                                    placeholder = { Text("e.g. EMP-1042") },
                                    singleLine = true,
                                    colors = fieldColors,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                AppTextField(
                                    value = secretTokenInput,
                                    onValueChange = { secretTokenInput = it },
                                    label = { Text("Faculty Passcode") },
                                    placeholder = { Text("Enter faculty passcode") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    colors = fieldColors,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else if (selectedRole == Role.STAFF) {
                                AppTextField(
                                    value = rollNumberInput,
                                    onValueChange = { rollNumberInput = it },
                                    label = { Text("Staff ID") },
                                    placeholder = { Text("e.g. STF-801") },
                                    singleLine = true,
                                    colors = fieldColors,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                AppTextField(
                                    value = secretTokenInput,
                                    onValueChange = { secretTokenInput = it },
                                    label = { Text("Staff Passcode") },
                                    placeholder = { Text("Enter staff passcode") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    colors = fieldColors,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // Error banner
                        if (authErrorText != null) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = AppTheme.colors.dangerRedBg,
                                border = BorderStroke(1.dp, AppTheme.colors.dangerRed.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = authErrorText!!,
                                    color = AppTheme.colors.dangerRed,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }

                // Primary submit button
                if (!isRegisterMode) {
                    val canSubmit = !isAuthLoading &&
                            emailInput.isNotBlank() && passwordInput.isNotBlank() &&
                            emailError == null
                    Button(
                        onClick = {
                            val email = normalizedEmail // bare usernames auto-complete to @gmail.com
                            val password = passwordInput
                            if (email.isNotBlank() && password.isNotBlank()) {
                                isAuthLoading = true
                                authErrorText = null
                                scope.launch(Dispatchers.IO) {
                                    val result = AuthRepository.default.login(email, password)
                                    withContext(Dispatchers.Main) {
                                        isAuthLoading = false
                                        when (result) {
                                            is NetworkResult.Success -> {
                                                val body = result.data
                                                if (body.requiresOtp) {
                                                    pendingEmail = body.email
                                                    currentStep = AuthStep.OTP_VERIFICATION
                                                    otpInput = ""
                                                    authErrorText = null
                                                    startCooldownTimer()
                                                } else {
                                                    onAuthSuccess()
                                                }
                                            }
                                            is NetworkResult.Error -> {
                                                authErrorText = "Sign In failed: ${result.message}"
                                            }
                                            is NetworkResult.Exception -> {
                                                authErrorText = "Network error: ${result.throwable.localizedMessage ?: "Cannot reach server"}"
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        enabled = canSubmit,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppTheme.colors.accentOrange,
                            disabledContainerColor = AppTheme.colors.accentOrange.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        if (isAuthLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("Sign In", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
                            studentRollError == null &&
                            emailError == null &&
                            passwordError == null &&
                            isRoleValid &&
                            isAcademicValid &&
                            isSecretValid

                    Button(
                        onClick = {
                            val activeRole = selectedRole ?: Role.STUDENT
                            val isStudent = activeRole == Role.STUDENT
                            val semNumber = semesterInput.substringAfter("Semester ").trim().toIntOrNull()
                            val cleanEmail = normalizedEmail // bare usernames auto-complete to @gmail.com
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
                                val result = AuthRepository.default.register(req)
                                withContext(Dispatchers.Main) {
                                    isAuthLoading = false
                                    when (result) {
                                        is NetworkResult.Success -> {
                                            pendingEmail = result.data.email
                                            currentStep = AuthStep.OTP_VERIFICATION
                                            otpInput = ""
                                            authErrorText = null
                                            startCooldownTimer()
                                        }
                                        is NetworkResult.Error -> {
                                            authErrorText = "Registration failed: ${result.message}"
                                        }
                                        is NetworkResult.Exception -> {
                                            authErrorText = "Network error: ${result.throwable.localizedMessage ?: "Cannot reach server"}"
                                        }
                                    }
                                }
                            }
                        },
                        enabled = !isAuthLoading && canRegister,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppTheme.colors.accentOrange,
                            disabledContainerColor = AppTheme.colors.accentOrange.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        if (isAuthLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("Continue", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }


            } else {
                // OTP Step Header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "We’ve sent a code to",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppTheme.colors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = pendingEmail,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.colors.textPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // OTP Card
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = AppTheme.colors.surface,
                    border = null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Centered 6-digit verification code input
                        val otpInteractionSource = remember { MutableInteractionSource() }
                        BasicTextField(
                            value = otpInput,
                            onValueChange = {
                                if (it.length <= 6 && it.all { ch -> ch.isDigit() }) {
                                    otpInput = it
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            interactionSource = otpInteractionSource,
                            textStyle = TextStyle(
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center,
                                letterSpacing = 6.sp,
                                color = AppTheme.colors.textPrimary
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            cursorBrush = SolidColor(AppTheme.colors.primaryPurple),
                            decorationBox = { innerTextField ->
                                OutlinedTextFieldDefaults.DecorationBox(
                                    value = otpInput,
                                    innerTextField = {
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            innerTextField()
                                        }
                                    },
                                    placeholder = {
                                        Text(
                                            "• • • • • •",
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth(),
                                            fontSize = 22.sp,
                                            letterSpacing = 6.sp,
                                            color = AppTheme.colors.textSecondary.copy(alpha = 0.5f)
                                        )
                                    },
                                    visualTransformation = VisualTransformation.None,
                                    singleLine = true,
                                    enabled = true,
                                    isError = false,
                                    interactionSource = otpInteractionSource,
                                    colors = fieldColors,
                                    container = {
                                        OutlinedTextFieldDefaults.ContainerBox(
                                            enabled = true,
                                            isError = false,
                                            interactionSource = otpInteractionSource,
                                            colors = fieldColors,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                    }
                                )
                            }
                        )

                        // Resend timer or action
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (resendCooldown > 0) {
                                Text(
                                    text = "Resend code in ${resendCooldown}s",
                                    fontSize = 13.sp,
                                    color = AppTheme.colors.textSecondary
                                )
                            } else {
                                TextButton(
                                    onClick = {
                                        isAuthLoading = true
                                        authErrorText = null
                                        scope.launch(Dispatchers.IO) {
                                            val result = AuthRepository.default.resendOtp(
                                                email = pendingEmail,
                                                purpose = if (isRegisterMode) "REGISTER" else "LOGIN"
                                            )
                                            withContext(Dispatchers.Main) {
                                                isAuthLoading = false
                                                when (result) {
                                                    is NetworkResult.Success -> startCooldownTimer()
                                                    is NetworkResult.Error -> authErrorText = "Resend failed: ${result.message}"
                                                    is NetworkResult.Exception -> authErrorText = "Network error: ${result.throwable.localizedMessage ?: "Cannot reach server"}"
                                                }
                                            }
                                        }
                                    }
                                ) {
                                    Text(
                                        text = "Resend Code",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppTheme.colors.primaryPurple
                                    )
                                }
                            }
                        }

                        if (authErrorText != null) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = AppTheme.colors.dangerRedBg,
                                border = BorderStroke(1.dp, AppTheme.colors.dangerRed.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = authErrorText!!,
                                    color = AppTheme.colors.dangerRed,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }

                // Verify Button
                Button(
                    onClick = {
                        val code = otpInput.trim()
                        if (code.length == 6) {
                            isAuthLoading = true
                            authErrorText = null
                            scope.launch(Dispatchers.IO) {
                                val result = if (isRegisterMode) {
                                    AuthRepository.default.verifyRegisterOtp(pendingEmail, code)
                                } else {
                                    AuthRepository.default.verifyLoginOtp(pendingEmail, code)
                                }
                                withContext(Dispatchers.Main) {
                                    isAuthLoading = false
                                    when (result) {
                                        is NetworkResult.Success -> {
                                            onAuthSuccess()
                                        }
                                        is NetworkResult.Error -> {
                                            authErrorText = "Verification failed: ${result.message}"
                                        }
                                        is NetworkResult.Exception -> {
                                            authErrorText = "Network error: ${result.throwable.localizedMessage ?: "Cannot reach server"}"
                                        }
                                    }
                                }
                            }
                        }
                    },
                    enabled = !isAuthLoading && otpInput.length == 6,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppTheme.colors.primaryPurple,
                        disabledContainerColor = AppTheme.colors.primaryPurple.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    if (isAuthLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text(
                            text = if (isRegisterMode) "Verify & Register" else "Verify & Sign In",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    }
}

// ---- Registration format validation helpers -------------------------------

// College roll number: <admission-year xx><college code 13401010><roll xx> = 12
// digits, e.g. 251340101019 = 25 (2025 admission) + 13401010 + roll 19
private const val COLLEGE_ID_CODE = "13401010"

private fun rollNumberFormatError(roll: String): String? {
    return when {
        roll.any { !it.isDigit() } -> "Roll number must contain digits only"
        roll.length != 12 -> "Roll number must be exactly 12 digits"
        roll.substring(2, 10) != COLLEGE_ID_CODE -> "Digits 3-10 must be the college code $COLLEGE_ID_CODE"
        else -> null
    }
}

// Auto-completes bare usernames to Gmail and repairs common typos:
private fun normalizeCollegeEmail(raw: String): String {
    val trimmed = raw.trim()
    return when {
        trimmed.endsWith("@mgail.com", ignoreCase = true) -> trimmed.replace("@mgail.com", "@gmail.com", ignoreCase = true)
        trimmed.endsWith("@gamil.com", ignoreCase = true) -> trimmed.replace("@gamil.com", "@gmail.com", ignoreCase = true)
        !trimmed.contains('@') -> "$trimmed@gmail.com"
        else -> trimmed
    }
}

private fun isEmailValid(email: String): Boolean =
    android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
