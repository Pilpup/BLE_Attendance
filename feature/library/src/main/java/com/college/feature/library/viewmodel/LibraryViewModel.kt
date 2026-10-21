package com.college.feature.library.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.college.core.network.SessionManager
import com.college.feature.library.model.Book
import com.college.feature.library.network.BookCreateRequest
import com.college.feature.library.network.BookUpdateRequest
import com.college.feature.library.network.LibraryApiClient
import com.college.feature.library.network.LibraryApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Single in-memory catalogue state — an object so the dashboard card can show
 * LIVE counts without owning a ViewModel. [LibraryViewModel] is the only
 * writer; seedSample()/clearCache() are restricted to developers via it.
 */
object LibraryCatalogStore {

    /** Realistic college titles across the catalogue chip categories. */
    private val sampleCatalogue = listOf(
        Book(
            id = "book-001",
            title = "Computer Networking: A Top-Down Approach",
            author = "James F. Kurose, Keith W. Ross",
            isbn = "9780133594140",
            category = "CS",
            totalCopies = 5,
            availableCopies = 3,
            shelfLocation = "Shelf 1-A"
        ),
        Book(
            id = "book-002",
            title = "Introduction to Algorithms",
            author = "Thomas H. Cormen et al.",
            isbn = "9780262042829",
            category = "CS",
            totalCopies = 3,
            availableCopies = 2,
            shelfLocation = "Shelf 1-B"
        ),
        Book(
            id = "book-003",
            title = "Operating System Concepts",
            author = "Abraham Silberschatz",
            isbn = "9781118878762",
            category = "CS",
            totalCopies = 4,
            availableCopies = 0,
            shelfLocation = "Shelf 1-C"
        ),
        Book(
            id = "book-004",
            title = "Linear Algebra Done Right",
            author = "Sheldon Axler",
            isbn = "9781441987153",
            category = "MATH",
            totalCopies = 3,
            availableCopies = 1,
            shelfLocation = "Shelf 2-A"
        ),
        Book(
            id = "book-005",
            title = "Calculus: Early Transcendentals",
            author = "James Stewart",
            isbn = "9781285741550",
            category = "MATH",
            totalCopies = 6,
            availableCopies = 4,
            shelfLocation = "Shelf 2-B"
        ),
        Book(
            id = "book-006",
            title = "University Physics with Modern Physics",
            author = "Hugh D. Young, Roger A. Freedman",
            isbn = "9780321973610",
            category = "PHYSICS",
            totalCopies = 4,
            availableCopies = 2,
            shelfLocation = "Shelf 3-A"
        ),
        Book(
            id = "book-007",
            title = "Organic Chemistry",
            author = "Jonathan Clayden",
            isbn = "9780199642778",
            category = "CHEMISTRY",
            totalCopies = 2,
            availableCopies = 1,
            shelfLocation = "Shelf 3-B"
        ),
        Book(
            id = "book-008",
            title = "Molecular Biology of the Cell",
            author = "Bruce Alberts et al.",
            isbn = "9780815344322",
            category = "BIO",
            totalCopies = 3,
            availableCopies = 0,
            shelfLocation = "Shelf 4-A"
        )
    )

    /** Seeded eagerly so the dashboard card has counts on first launch. */
    val books = mutableStateListOf<Book>().apply { addAll(sampleCatalogue) }

    fun seedSample() {
        books.clear()
        books.addAll(sampleCatalogue)
    }

    fun clearCache() {
        books.clear()
    }
}

/**
 * Library Explorer engine (Phase 3):
 *
 *  - Reads the active user from SessionManager.default for ALL role gates —
 *    students get browsing/reserving, staff get inventory CRUD, developers
 *    additionally get catalogue seeding/maintenance.
 *  - Search & filter state drives the catalogue tab.
 *  - The backend /api/v1/library routes are not deployed yet: every
 *    mutation is applied to the in-memory catalogue first (offline mock
 *    fallback by design), then a best-effort cloud sync is attempted through
 *    LibraryApiClient; a notice surfaces when cloud is unreachable.
 */
class LibraryViewModel : ViewModel() {

    // ---- Search & filter state ------------------------------------------
    var searchQuery by mutableStateOf("")
    var selectedCategory by mutableStateOf<String?>(null) // null = "All"
    var onlyAvailable by mutableStateOf(false)

    /** Set when a cloud sync fails; null while the view reflects reality. */
    var cloudStatusMessage by mutableStateOf<String?>(null)
        private set

    // ---- Role gates (from the shared session, not injected props) --------
    private val currentUser
        get() = SessionManager.default.currentUser

    /** STAFF & DEVELOPER: inventory CRUD in catalogue & inventory tabs. */
    val canUseStaffTools: Boolean
        get() = currentUser?.canAccessStaffTools() == true

    /** DEVELOPER only: the distinct maintenance tier. */
    val isDeveloper: Boolean
        get() = currentUser?.isDeveloper() == true

    // ---- Diagnostics (used by the dashboard card & dev tab) --------------
    val catalogueSize: Int get() = LibraryCatalogStore.books.size
    val availableCount: Int get() = LibraryCatalogStore.books.count { it.isAvailable }
    val categories: List<String>
        get() = LibraryCatalogStore.books.map { it.category }.distinct()

    val filteredBooks: List<Book>
        get() {
            val query = searchQuery.trim().lowercase()
            return LibraryCatalogStore.books.filter { book ->
                val matchesQuery = query.isEmpty() ||
                    book.title.lowercase().contains(query) ||
                    book.author.lowercase().contains(query) ||
                    book.isbn.contains(query)
                val matchesCategory = selectedCategory == null || book.category == selectedCategory
                val matchesAvailability = !onlyAvailable || book.isAvailable
                matchesQuery && matchesCategory && matchesAvailability
            }
        }

    // ---- Ordinary member actions ----------------------------------------

    /** Any signed-in member can reserve an available copy. */
    fun reserveBook(bookId: String) {
        val index = LibraryCatalogStore.books.indexOfFirst { it.id == bookId }
        if (index == -1) return
        val book = LibraryCatalogStore.books[index]
        if (!book.isAvailable) return

        LibraryCatalogStore.books[index] = book.copy(availableCopies = book.availableCopies - 1)
        syncAfterMutation { it.reserveBook(bookId) }
    }

    // ---- Staff / developer inventory actions ------------------------------

    /** Staff update information or copies for an existing book. */
    fun editBook(updated: Book) {
        if (!canUseStaffTools) return
        val index = LibraryCatalogStore.books.indexOfFirst { it.id == updated.id }
        if (index == -1) return

        // Preserve the catalogue invariant: 0 <= available <= total
        val total = updated.totalCopies.coerceAtLeast(0)
        val available = updated.availableCopies.coerceIn(0, total)
        val fixed = updated.copy(totalCopies = total, availableCopies = available)

        LibraryCatalogStore.books[index] = fixed
        syncAfterMutation {
            it.updateBook(
                fixed.id,
                BookUpdateRequest(
                    title = fixed.title,
                    author = fixed.author,
                    isbn = fixed.isbn,
                    category = fixed.category,
                    totalCopies = fixed.totalCopies,
                    availableCopies = fixed.availableCopies,
                    shelfLocation = fixed.shelfLocation
                )
            )
        }
    }

    /** Staff add a title to the catalogue (available == total on intake). */
    fun addBook(title: String, author: String, isbn: String, category: String, copies: Int) {
        if (!canUseStaffTools) return
        if (title.isBlank() || author.isBlank() || isbn.isBlank() || category.isBlank()) return

        val total = copies.coerceAtLeast(0)
        val book = Book(
            id = "book-" + UUID.randomUUID().toString().replace("-", "").take(6),
            title = title.trim(),
            author = author.trim(),
            isbn = isbn.trim().replace("-", "").uppercase(),
            category = category.trim().uppercase(),
            totalCopies = total,
            availableCopies = total,
            shelfLocation = "Main Hall"
        )

        LibraryCatalogStore.books.add(book)
        syncAfterMutation {
            it.addBook(
                BookCreateRequest(
                    title = book.title,
                    author = book.author,
                    isbn = book.isbn,
                    category = book.category,
                    totalCopies = book.totalCopies,
                    shelfLocation = book.shelfLocation
                )
            )
        }
    }

    /** Staff remove a title from the catalogue entirely. */
    fun removeBook(bookId: String) {
        if (!canUseStaffTools) return
        val index = LibraryCatalogStore.books.indexOfFirst { it.id == bookId }
        if (index == -1) return
        val removed = LibraryCatalogStore.books.removeAt(index)
        syncAfterMutation { it.deleteBook(removed.id) }
    }

    // ---- Developer maintenance tier ---------------------------------------

    /** DEVELOPER only: rebuild the in-memory test catalogue from the sample set. */
    fun seedSampleData() {
        if (!isDeveloper) return
        LibraryCatalogStore.seedSample()
        cloudStatusMessage = null
    }

    /** DEVELOPER only: empty the in-memory cache (Seed refills it). */
    fun clearCatalogueCache() {
        if (!isDeveloper) return
        LibraryCatalogStore.clearCache()
        cloudStatusMessage = "In-memory catalogue cache cleared"
    }

    // ---- Cloud sync (best-effort; in-memory list is the fallback truth) ----

    private fun syncAfterMutation(block: suspend (LibraryApiService) -> Unit) {
        val service = LibraryApiClient.service
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block(service)
                cloudStatusMessage = null
            } catch (_: Exception) {
                // The endpoint isn't deployed yet (or we're offline): the local
                // mutation stands. Surface the fallback state to the user.
                cloudStatusMessage = "Backend offline — changes kept in the in-memory catalogue"
            }
        }
    }
}
