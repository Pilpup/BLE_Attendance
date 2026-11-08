package com.attendance.ble.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.college.core.model.Role
import com.college.core.model.User
import com.college.core.network.SessionManager
import com.college.core.ui.components.AppCardGroup
import com.college.core.ui.components.AppCardItem
import com.college.core.ui.components.AppDivider
import com.college.core.ui.components.AppProfileCard
import com.college.core.ui.components.AppSearchPill
import com.college.core.ui.components.PokemonTrainerCard
import com.college.core.ui.theme.AppTheme
import com.college.feature.attendance.AttendanceDashboardCard
import com.college.feature.library.LibraryDashboardCard

// Main dashboard home screen
@Composable
fun HomeScreen(
    user: User?,
    onNavigate: (String) -> Unit
) {
    var showAccountDialog by remember { mutableStateOf(false) }
    var isAboutExpanded by remember { mutableStateOf(false) }
    var showDevActingDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val colors = AppTheme.colors
    val featureItems = remember(user, colors) {
        buildList {
            add(
                FeatureItem("Attendance", Icons.AutoMirrored.Filled.FactCheck, colors.accentOrange) {
                    if (user != null) onNavigate(AttendanceDashboardCard.NAV_ROUTE) else onNavigate("auth")
                }
            )
            if (user?.canTakeAttendance() == true) {
                add(
                    FeatureItem("Older Sessions", Icons.Default.History, colors.primaryPurple) {
                        onNavigate("${AttendanceDashboardCard.NAV_ROUTE}/older")
                    }
                )
            }
            add(
                FeatureItem("Library", Icons.AutoMirrored.Filled.MenuBook, colors.primaryPurple) {
                    if (user != null) onNavigate(LibraryDashboardCard.NAV_ROUTE) else onNavigate("auth")
                }
            )
            add(
                FeatureItem("Account", Icons.Default.Person, colors.warningAmber) {
                    if (user != null) {
                        showAccountDialog = true
                    } else {
                        onNavigate("auth")
                    }
                }
            )
        }
    }

    val visibleFeatures = remember(searchQuery, featureItems) {
        if (searchQuery.isBlank()) {
            featureItems.filter { it.title != "Account" }
        } else {
            featureItems.filter {
                it.title.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            .systemBarsPadding()
    ) {
        val screenHeight = maxHeight
        val viewingAreaHeight = (screenHeight * 0.28f).coerceIn(180.dp, 250.dp)

        // Main content column shifted down for one-handed reach without scrolling
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Viewing area displaying College Hub title
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(viewingAreaHeight),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "College Hub",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.textPrimary,
                    letterSpacing = (-0.6).sp
                )
            }

            val activeRole = user?.primaryRole ?: user?.roles?.firstOrNull() ?: Role.STUDENT
            val activeRoleLabel = when (activeRole) {
                Role.TEACHER -> "Teacher"
                Role.STUDENT -> "Student"
                Role.STAFF -> "Staff"
                Role.DEVELOPER -> "Developer"
            }

            // User account profile card opening account modal when authenticated or auth screen when logged out
            AppProfileCard(
                name = user?.name ?: "Account",
                subtitle = if (user != null) {
                    "$activeRoleLabel • ${user.rollNumber ?: user.email}"
                } else {
                    "Sign in or create profile"
                },
                initial = user?.name,
                avatarBg = if (user != null) AppTheme.colors.accentOrange else AppTheme.colors.primaryPurple,
                border = null,
                onClick = {
                    if (user != null) {
                        showAccountDialog = true
                    } else {
                        onNavigate("auth")
                    }
                }
            )

            // Campus features card group (filters directly inside this island)
            AppCardGroup(border = null) {
                if (visibleFeatures.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No matching features",
                            fontSize = 14.sp,
                            color = AppTheme.colors.textSecondary
                        )
                    }
                } else {
                    visibleFeatures.forEachIndexed { index, item ->
                        if (index > 0) AppDivider()
                        AppCardItem(
                            icon = item.icon,
                            iconTint = Color.White,
                            iconBg = item.iconBg,
                            title = item.title,
                            onClick = item.onClick
                        )
                    }
                }
            }

            // Expandable application information group
            val aboutChevronRotation by animateFloatAsState(
                targetValue = if (isAboutExpanded) 180f else 0f,
                label = "about_chevron_rotation"
            )

            AppCardGroup(
                modifier = Modifier.animateContentSize(),
                border = null
            ) {
                AppCardItem(
                    icon = Icons.Default.Info,
                    iconTint = Color.White,
                    iconBg = Color(0xFF64748B),
                    title = "About",
                    subtitle = "v1.0",
                    onClick = { isAboutExpanded = !isAboutExpanded },
                    trailing = {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isAboutExpanded) "Collapse" else "Expand",
                            tint = AppTheme.colors.textSecondary,
                            modifier = Modifier
                                .size(22.dp)
                                .rotate(aboutChevronRotation)
                        )
                    }
                )

                AnimatedVisibility(
                    visible = isAboutExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 2.dp)
                    ) {
                        HorizontalDivider(
                            color = AppTheme.colors.surfaceBorder.copy(alpha = 0.5f),
                            thickness = 1.dp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        // Gratitude message
                        Text(
                            text = "Thank you for using our app",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppTheme.colors.textSecondary
                        )
                    }
                }
            }

            // Bottom clearance above floating search bar
            Spacer(modifier = Modifier.height(72.dp))
        }

        // Floating search pill pinned at bottom
        AppSearchPill(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = "Search",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp)
        )
    }

    // Modal dialogs
    if (showAccountDialog) {
        PokemonTrainerCardDialog(
            user = user,
            onDismiss = { showAccountDialog = false },
            onNavigateAuth = {
                showAccountDialog = false
                onNavigate("auth")
            },
            onSignOut = {
                SessionManager.default.endSession()
                showAccountDialog = false
            },
            onRoleClick = if (SessionManager.default.realUser?.isDeveloper() == true) {
                { showDevActingDialog = true }
            } else null
        )
    }

    if (showDevActingDialog) {
        DevActingDialog(
            onDismiss = { showDevActingDialog = false }
        )
    }
}

// Pokemon 2D Game inspired Trainer Card modal popup
@Composable
private fun PokemonTrainerCardDialog(
    user: User?,
    onDismiss: () -> Unit,
    onNavigateAuth: () -> Unit,
    onSignOut: () -> Unit,
    onRoleClick: (() -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PokemonTrainerCard(
                user = user,
                onClick = onDismiss,
                onRoleClick = onRoleClick
            )

            if (user != null) {
                Button(
                    onClick = onSignOut,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.dangerRed),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sign Out", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Button(
                    onClick = onNavigateAuth,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primaryPurple),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sign In", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}


// DEVELOPER-only acting-role selector: wears a role on the client lens so
// developer accounts can preview student, teacher and staff flows end to end.
// Server-side sees the real account (DEVELOPER clears require_* everywhere),
// so no student data holes open — this is purely a UI lens.
@Composable
private fun DevActingDialog(onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    val acting = SessionManager.default.actingRole
    val real = SessionManager.default.realUser
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textPrimary,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Dev: act as role") },
        text = {
            Column {
                Text(
                    "Local preview only — the server continues to authorize your real DEVELOPER account. A fresh login always presents the real role.",
                    fontSize = 11.sp,
                    color = colors.textSecondary
                )
                Spacer(Modifier.height(10.dp))
                listOf(Role.STUDENT, Role.TEACHER, Role.STAFF, Role.DEVELOPER).forEach { role ->
                    val isActive = acting == role
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                SessionManager.default.setActingRole(role)
                                onDismiss()
                            },
                        color = if (isActive) colors.primaryPurpleBg else colors.surface,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val label = role.name.lowercase().replaceFirstChar { it.uppercase() }
                            Text(
                                if (isActive) "Acting: $label ✓" else "Act as $label",
                                fontSize = 14.sp,
                                color = if (isActive) colors.primaryPurple else colors.textPrimary
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Real account: ${real?.primaryRole?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "unknown"}",
                    fontSize = 11.sp,
                    color = colors.textSecondary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                SessionManager.default.setActingRole(null)
                onDismiss()
            }) { Text("Back to real role") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

// Feature item for the campus features island
private data class FeatureItem(
    val title: String,
    val icon: ImageVector,
    val iconBg: Color,
    val onClick: () -> Unit
)
