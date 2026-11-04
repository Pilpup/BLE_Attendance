package com.attendance.ble.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.college.core.model.Role
import com.college.core.model.User
import com.college.core.network.SessionManager
import com.college.core.ui.components.AppCardGroup
import com.college.core.ui.components.AppCardItem
import com.college.core.ui.components.AppDivider
import com.college.core.ui.components.AppHeader
import com.college.core.ui.components.AppProfileCard
import com.college.core.ui.components.AppSearchPill
import com.college.core.ui.components.AppTextField
import com.college.core.ui.components.appScrollbar
import com.college.core.ui.components.appTextFieldColors
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
    var showAboutDialog by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }

    val activeRole = user?.primaryRole ?: user?.roles?.firstOrNull() ?: Role.STUDENT
    val activeRoleLabel = when (activeRole) {
        Role.TEACHER -> "Teacher"
        Role.STUDENT -> "Student"
        Role.STAFF -> "Staff"
        Role.DEVELOPER -> "Developer"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            .systemBarsPadding()
    ) {
        val homeScrollState = rememberScrollState()

        // Scrollable content stack
        Column(
            modifier = Modifier
                .fillMaxSize()
                .appScrollbar(homeScrollState)
                .verticalScroll(homeScrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header title
            AppHeader(
                title = "College Hub"
            )

            // User account profile card opening auth screen or details modal
            AppProfileCard(
                name = user?.name ?: "Account",
                subtitle = if (user != null) {
                    "$activeRoleLabel • ${user.rollNumber ?: user.email}"
                } else {
                    "Sign in or create profile"
                },
                initial = user?.name,
                avatarBg = if (user != null) AppTheme.colors.accentOrange else AppTheme.colors.primaryPurple,
                onClick = {
                    if (user != null) {
                        showAccountDialog = true
                    } else {
                        onNavigate("auth")
                    }
                }
            )

            // Campus features card group
            AppCardGroup {
                AppCardItem(
                    icon = Icons.AutoMirrored.Filled.FactCheck,
                    iconTint = Color.White,
                    iconBg = AppTheme.colors.infoBlue,
                    title = "Attendance",
                    onClick = {
                        if (user != null) {
                            onNavigate(AttendanceDashboardCard.NAV_ROUTE)
                        } else {
                            onNavigate("auth")
                        }
                    }
                )

                AppDivider()

                AppCardItem(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    iconTint = Color.White,
                    iconBg = AppTheme.colors.successGreen,
                    title = "Library",
                    onClick = {
                        if (user != null) {
                            onNavigate(LibraryDashboardCard.NAV_ROUTE)
                        } else {
                            onNavigate("auth")
                        }
                    }
                )
            }

            // Application information group
            AppCardGroup {
                AppCardItem(
                    icon = Icons.Default.Info,
                    iconTint = Color.White,
                    iconBg = Color(0xFF64748B),
                    title = "About",
                    subtitle = "Version 2.0",
                    onClick = { showAboutDialog = true }
                )
            }

            // Bottom clearance above floating search bar
            Spacer(modifier = Modifier.height(72.dp))
        }

        // Floating search pill pinned at bottom
        AppSearchPill(
            placeholder = "Search",
            onSearchClick = { showSearchDialog = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
        )
    }

    // Modal dialogs
    if (showAccountDialog && user != null) {
        UserDetailsDialog(
            user = user,
            onDismiss = { showAccountDialog = false }
        )
    }

    if (showAboutDialog) {
        AboutDialog(
            onDismiss = { showAboutDialog = false }
        )
    }

    if (showSearchDialog) {
        SearchDialog(
            user = user,
            onNavigate = { route ->
                showSearchDialog = false
                onNavigate(route)
            },
            onOpenAccount = {
                showSearchDialog = false
                if (user != null) {
                    showAccountDialog = true
                } else {
                    onNavigate("auth")
                }
            },
            onOpenAbout = {
                showSearchDialog = false
                showAboutDialog = true
            },
            onDismiss = { showSearchDialog = false }
        )
    }
}

// User details dialog showing profile data and sign out button
@Composable
private fun UserDetailsDialog(
    user: User,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppTheme.colors.surface,
        titleContentColor = AppTheme.colors.textPrimary,
        textContentColor = AppTheme.colors.textPrimary,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(AppTheme.colors.accentOrange, CircleShape),
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
                        color = AppTheme.colors.textPrimary
                    )
                    Text(
                        text = user.email,
                        fontSize = 12.sp,
                        color = AppTheme.colors.textSecondary
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
                HorizontalDivider(color = AppTheme.colors.divider)

                Text(
                    text = "ACTIVE ROLES",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.textSecondary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    user.roles.forEach { role ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
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

                // Academic details
                if (!user.rollNumber.isNullOrBlank() || !user.department.isNullOrBlank() || !user.year.isNullOrBlank()) {
                    HorizontalDivider(color = AppTheme.colors.divider)

                    Text(
                        text = "ACADEMIC DETAILS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.textSecondary
                    )

                    user.program?.let {
                        Text(text = "Course: $it", fontSize = 13.sp, color = AppTheme.colors.textPrimary)
                    }
                    user.rollNumber?.let {
                        Text(text = "ID / Roll No: $it", fontSize = 13.sp, color = AppTheme.colors.textPrimary)
                    }
                    user.department?.let {
                        Text(text = "Branch: $it", fontSize = 13.sp, color = AppTheme.colors.textPrimary)
                    }
                    user.semester?.let {
                        Text(text = "Semester: $it", fontSize = 13.sp, color = AppTheme.colors.textPrimary)
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
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.dangerRed),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Sign Out", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = AppTheme.colors.textSecondary)
            }
        }
    )
}

// Application information modal
@Composable
private fun AboutDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppTheme.colors.surface,
        titleContentColor = AppTheme.colors.textPrimary,
        textContentColor = AppTheme.colors.textPrimary,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "College Hub",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "A lightweight campus management application for student attendance and library services.",
                    fontSize = 13.sp,
                    color = AppTheme.colors.textSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AppTheme.colors.primaryPurpleBg,
                    border = BorderStroke(1.dp, AppTheme.colors.primaryPurple.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Version 2.0",
                        color = AppTheme.colors.primaryPurple,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primaryPurple),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Close", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    )
}

// Search dialog for quick navigation across features
@Composable
private fun SearchDialog(
    user: User?,
    onNavigate: (String) -> Unit,
    onOpenAccount: () -> Unit,
    onOpenAbout: () -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val colors = AppTheme.colors
    val allActions = remember(user, colors) {
        listOf(
            SearchAction("Attendance", "Mark attendance and view session records", Icons.AutoMirrored.Filled.FactCheck, colors.primaryPurple) {
                if (user != null) onNavigate(AttendanceDashboardCard.NAV_ROUTE) else onOpenAccount()
            },
            SearchAction("Library", "Book loans and library catalog", Icons.AutoMirrored.Filled.MenuBook, colors.accentOrange) {
                if (user != null) onNavigate(LibraryDashboardCard.NAV_ROUTE) else onOpenAccount()
            },
            SearchAction("Account Profile", if (user != null) "View account details and roles" else "Sign in or create profile", Icons.Default.Person, colors.warningAmber) {
                onOpenAccount()
            },
            SearchAction("About", "Application version and details", Icons.Default.Info, colors.primaryPurple) {
                onOpenAbout()
            }
        )
    }

    val filteredActions = remember(searchQuery, allActions) {
        if (searchQuery.isBlank()) allActions
        else allActions.filter {
            it.title.contains(searchQuery, ignoreCase = true) || it.subtitle.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppTheme.colors.surface,
        titleContentColor = AppTheme.colors.textPrimary,
        textContentColor = AppTheme.colors.textPrimary,
        shape = RoundedCornerShape(24.dp),
        title = {
            AppTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search...") },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = "Search", tint = AppTheme.colors.textSecondary)
                },
                singleLine = true,
                colors = appTextFieldColors(),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (filteredActions.isEmpty()) {
                    Text(
                        text = "No matching features found",
                        fontSize = 13.sp,
                        color = AppTheme.colors.textSecondary,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    filteredActions.forEach { action ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { action.onClick() }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(action.color.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = action.icon,
                                    contentDescription = action.title,
                                    tint = action.color,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = action.title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppTheme.colors.textPrimary
                                )
                                Text(
                                    text = action.subtitle,
                                    fontSize = 11.sp,
                                    color = AppTheme.colors.textSecondary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = AppTheme.colors.textSecondary)
            }
        }
    )
}

// Data holder for searchable app action items
private data class SearchAction(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val color: Color,
    val onClick: () -> Unit
)

// Active role badge text color
@Composable
@ReadOnlyComposable
private fun roleBadgeColor(role: Role): Color = when (role) {
    Role.TEACHER -> AppTheme.colors.infoBlue
    Role.STUDENT -> AppTheme.colors.successGreen
    Role.STAFF -> AppTheme.colors.warningAmber
    Role.DEVELOPER -> AppTheme.colors.primaryPurple
}

// Active role badge background container color
@Composable
@ReadOnlyComposable
private fun roleBadgeBg(role: Role): Color = when (role) {
    Role.TEACHER -> AppTheme.colors.infoBlueBg
    Role.STUDENT -> AppTheme.colors.successGreenBg
    Role.STAFF -> AppTheme.colors.warningAmberBg
    Role.DEVELOPER -> AppTheme.colors.primaryPurpleBg
}
