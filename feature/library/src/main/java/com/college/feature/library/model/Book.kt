package com.college.feature.library.model

/**
 * A catalogue entry. Kept module-local (the shared :core:model contract stays
 * attendance/library-agnostic); the wire format lives in network/LibraryApiService.kt.
 */
data class Book(
    val id: String,
    val title: String,
    val author: String,
    val isbn: String,
    val category: String,
    val totalCopies: Int,
    val availableCopies: Int,
    val shelfLocation: String = "Main Hall"
) {
    val isAvailable: Boolean get() = availableCopies > 0
}
