package com.college.core.ui

import androidx.compose.runtime.mutableStateListOf
import com.college.core.model.User

object DashboardRegistry {

    private val cards = mutableStateListOf<DashboardCard>()
    private val lock = Any()

    fun register(card: DashboardCard) {
        synchronized(lock) {
            if (cards.none { it.id == card.id }) cards.add(card)
        }
    }

    fun cardsFor(user: User): List<DashboardCard> =
        synchronized(lock) {
            cards.filter { it.isVisibleFor(user) }.sortedBy { it.priority }
        }

    fun clear() {
        synchronized(lock) {
            cards.clear()
        }
    }
}
