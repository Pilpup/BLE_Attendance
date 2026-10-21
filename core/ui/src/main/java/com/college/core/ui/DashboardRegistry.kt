package com.college.core.ui

import androidx.compose.runtime.mutableStateListOf
import com.college.core.model.User

/**
 * Process-wide collector of [DashboardCard] contributions.
 *
 * Feature modules contribute their card here; :app only asks
 * [cardsFor] which cards match the active user's role set —
 * the shell has zero layout code specific to any feature.
 */
object DashboardRegistry {

    private val cards = mutableStateListOf<DashboardCard>()

    /**
     * Registers a feature card contribution. Safe to call repeatedly
     * (same id is registered once), so feature init code can be idempotent.
     */
    fun register(card: DashboardCard) {
        if (cards.none { it.id == card.id }) cards.add(card)
    }

    /** Registered cards visible to [user], ordered by [DashboardCard.priority] (ascending). */
    fun cardsFor(user: User): List<DashboardCard> =
        cards.filter { it.isVisibleFor(user) }.sortedBy { it.priority }

    /** Test/development helper: clears all registrations. NOT meant for production code. */
    fun clear() = cards.clear()
}
