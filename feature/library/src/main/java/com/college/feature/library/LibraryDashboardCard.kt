package com.college.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.college.core.model.User
import com.college.core.ui.DashboardCard
import com.college.core.ui.theme.AppTheme
import com.college.feature.library.viewmodel.LibraryCatalogStore

// Displays library catalogue card on home dashboard with live book count
object LibraryDashboardCard : DashboardCard {

    override val id: String = "library_card"
    override val priority: Int = 20 // secondary to the everyday attendance tool

    const val NAV_ROUTE: String = "feature_library"

    override fun isVisibleFor(user: User): Boolean = true

    @Composable
    override fun Content(user: User, onNavigate: (String) -> Unit) {
        val totalBooks = LibraryCatalogStore.books.size
        val totalAvailable = LibraryCatalogStore.books.count { it.isAvailable }
        val subtitle = if (totalBooks == 0)
            "Catalogue is empty — staff intake pending"
        else
            "$totalBooks book${if (totalBooks == 1) "" else "s"} catalogued · $totalAvailable available"

        Surface(
            onClick = { onNavigate(NAV_ROUTE) },
            shape = RoundedCornerShape(22.dp),
            color = AppTheme.colors.surface,
            border = null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(AppTheme.colors.successGreen, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.LocalLibrary,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(
                        text = "Library Catalogue",
                        fontSize = 17.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.colors.textPrimary
                    )
                    Text(
                        text = subtitle,
                        fontSize = 13.sp,
                        color = AppTheme.colors.textSecondary
                    )
                    if (user.canAccessStaffTools()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AppTheme.colors.primaryPurpleBg,
                            border = BorderStroke(0.5.dp, AppTheme.colors.primaryPurple.copy(alpha = 0.5f)),
                            modifier = Modifier.padding(top = 6.dp)
                        ) {
                            Text(
                                text = "Staff Inventory Access",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppTheme.colors.primaryPurple,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
