package com.college.core.ui

import androidx.compose.runtime.Composable
import com.college.core.model.User

/**
 * Contract a feature module fulfills to contribute a widget to the Home
 * Dashboard. :app never hardcodes card layouts — it only renders whatever
 * [DashboardRegistry] returns for the active user's role set.
 */
interface DashboardCard {

    /** Stable identifier, e.g. "attendance_card". Also used for registration de-duplication. */
    val id: String

    /** Ordering hint: lower sorts closer to the top (10 = everyday tool, 50 = secondary). */
    val priority: Int

    /** Whether this card should be rendered for [user]'s role set. */
    fun isVisibleFor(user: User): Boolean

    /**
     * The card's UI.
     * [onNavigate] receives the feature route (e.g. "feature_attendance") so the
     * shell maps it to a destination — the card never navigates by itself.
     */
    @Composable
    fun Content(user: User, onNavigate: (String) -> Unit)
}
