package com.attendance.ble.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.attendance.ble.ui.theme.appBg
import com.attendance.ble.ui.theme.cardBg
import com.attendance.ble.ui.theme.cardBorder
import com.attendance.ble.ui.theme.childPurple
import com.attendance.ble.ui.theme.childPurpleBg
import com.attendance.ble.ui.theme.primaryBlue
import com.attendance.ble.ui.theme.primaryBlueBg
import com.attendance.ble.ui.theme.successGreen
import com.attendance.ble.ui.theme.successGreenBg
import com.attendance.ble.ui.theme.textMuted
import com.attendance.ble.ui.theme.textPrimary
import com.attendance.ble.ui.theme.textSecondary
import com.attendance.ble.ui.theme.warningOrange
import com.attendance.ble.ui.theme.warningOrangeBg
import com.college.core.model.Role
import com.college.core.model.User
import com.college.core.network.SessionManager
import com.college.core.ui.DashboardRegistry

/**
 * Home Dashboard of the :app shell. Renders NO feature-specific layout:
 * a profile header, the dev role-switcher, and whatever
 * [DashboardRegistry.cardsFor] contributes for the active user's roles.
 */
@Composable
fun HomeScreen(
    user: User,
    onNavigate: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(appBg)
            .systemBarsPadding()
    ) {
        // ---- Header: user profile + primary role chip -----------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(primaryBlue, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = user.name.take(1),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
            ) {
                Text(
                    text = user.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary
                )
                Text(
                    text = user.email,
                    fontSize = 12.sp,
                    color = textSecondary
                )
            }
            RoleChip(user.primaryRole ?: Role.STUDENT)
        }

        // ---- Dev/Test role switcher (1-tap mock accounts, no re-login) ------
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = "DEV · switch mock role (no re-login)",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                color = textMuted
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MockUsers.all.forEach { mock ->
                    MockRoleChip(
                        label = mock.name,
                        selected = mock.id == user.id,
                        accent = roleAccent(mock.primaryRole ?: Role.STUDENT),
                        onSelect = {
                            SessionManager.default.startSession(mock, MockUsers.MOCK_DEV_TOKEN)
                        }
                    )
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = cardBorder
        )

        // ---- Body: feature cards contributed via the registry ---------------
        val visibleCards = DashboardRegistry.cardsFor(user)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(visibleCards.size) { index ->
                visibleCards[index].Content(user, onNavigate)
            }
            if (visibleCards.isEmpty()) {
                item {
                    Text(
                        text = "No features available for your role set yet.",
                        fontSize = 13.sp,
                        color = textMuted,
                        modifier = Modifier.padding(top = 24.dp, start = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Entry screen while no session exists (LoggedOut/Loading): a mock
 * role-selector bar standing in for the real login form until the legacy
 * auth flow migrates into the shell.
 */
@Composable
internal fun MockRoleSelector(onSelect: (User) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(appBg)
            .systemBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "College Hub",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = textPrimary
        )
        Text(
            text = "Mock sign-in · pick an account to preview its role-gated dashboard.",
            fontSize = 13.sp,
            color = textSecondary
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(MockUsers.all.size) { index ->
                val mock = MockUsers.all[index]
                Surface(
                    onClick = { onSelect(mock) },
                    shape = RoundedCornerShape(16.dp),
                    color = cardBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = mock.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary
                        )
                        Text(
                            text = mock.email,
                            fontSize = 12.sp,
                            color = textSecondary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            mock.roles.forEach { role -> MiniRoleTag(role) }
                        }
                    }
                }
            }
        }

        Text(
            text = "Real backend login arrives when the legacy auth flow migrates.",
            fontSize = 11.sp,
            color = textMuted,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

// ---------------------------------------------------------------- helpers

private fun roleAccent(role: Role): Color = when (role) {
    Role.TEACHER -> primaryBlue
    Role.STUDENT -> successGreen
    Role.STAFF -> warningOrange
    Role.DEVELOPER -> childPurple
}

private fun roleAccentBg(role: Role): Color = when (role) {
    Role.TEACHER -> primaryBlueBg
    Role.STUDENT -> successGreenBg
    Role.STAFF -> warningOrangeBg
    Role.DEVELOPER -> childPurpleBg
}

@Composable
private fun RoleChip(role: Role) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = roleAccent(role)
    ) {
        Text(
            text = role.name,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun MiniRoleTag(role: Role) {
    Surface(shape = RoundedCornerShape(8.dp), color = roleAccentBg(role)) {
        Text(
            text = role.name,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = roleAccent(role),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun MockRoleChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onSelect: () -> Unit
) {
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) accent else cardBg,
        modifier = Modifier.border(
            width = 1.dp,
            color = if (selected) accent else cardBorder,
            shape = RoundedCornerShape(20.dp)
        )
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) Color.White else textPrimary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}
