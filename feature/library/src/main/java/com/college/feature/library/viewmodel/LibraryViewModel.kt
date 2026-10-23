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
import com.college.feature.library.network.BookDto
import retrofit2.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /** Replaces the store with server truth (called after a successful cloud fetch). */
    fun replaceAll(remote: List<Book>) {
        books.clear()
        books.addAll(remote)
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

    init {
        // Live Neon hydration on module entry (Phase 4): real sign-ins pull the
        // server catalogue; mock/guest sessions keep the local fallback sample.
        refreshFromCloud()
    }

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

    /** Active in-flight book reservations, to prevent duplicate clicks and show loading feedback. */
    val inFlightReservations = mutableStateListOf<String>()

    fun isReserving(bookId: String): Boolean = inFlightReservations.contains(bookId)

    /** Any signed-in member can reserve an available copy. */
    fun reserveBook(bookId: String) {
        if (inFlightReservations.contains(bookId)) return
        val index = LibraryCatalogStore.books.indexOfFirst { it.id == bookId }
        if (index == -1) return
        val book = LibraryCatalogStore.books[index]
        if (!book.isAvailable) return

        inFlightReservations.add(bookId)
        // Optimistic local decrement
        LibraryCatalogStore.books[index] = book.copy(availableCopies = book.availableCopies - 1)

        syncAfterMutation(
            onFailure = { failureCode ->
                // On any HTTP failure (e.g. 409 Conflict, 400, etc.), rollback the optimistic decrement
                if (failureCode != -1) {
                    val idx = LibraryCatalogStore.books.indexOfFirst { it.id == bookId }
                    if (idx != -1) {
                        val curr = LibraryCatalogStore.books[idx]
                        LibraryCatalogStore.books[idx] = curr.copy(
                            availableCopies = (curr.availableCopies + 1).coerceAtMost(curr.totalCopies)
                        )
                    }
                }
            },
            onComplete = {
                inFlightReservations.remove(bookId)
            }
        ) {
            it.reserveBook(bookId)
        }
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

    /**
     * Every mutation has already committed locally; this reports the outcome
     * of the matching cloud call and, on success (or on 409 conflict), refetches
     * the catalogue so the store converges to Neon truth (authoritative copy counters).
     */
    private fun syncAfterMutation(
        onFailure: (suspend (failureCode: Int) -> Unit)? = null,
        onComplete: (suspend () -> Unit)? = null,
        block: suspend (LibraryApiService) -> Response<*>
    ) {
        val service = LibraryApiClient.service
        viewModelScope.launch(Dispatchers.IO) {
            val failureCode: Int? = try {
                val res = block(service)
                if (res.isSuccessful) null else res.code()
            } catch (_: Exception) {
                -1 // transport failure (offline / DNS / timeout)
            }

            withContext(Dispatchers.Main) {
                if (failureCode != null) {
                    onFailure?.invoke(failureCode)
                }
                onComplete?.invoke()

                cloudStatusMessage = when {
                    failureCode == null -> null
                    failureCode == -1 ->
                        "Could not reach the backend — changes kept in the in-memory catalogue"
                    failureCode == 401 ->
                        "Live persistence needs a real backend sign-in — change kept locally for now"
                    failureCode == 403 ->
                        "The backend rejected this role for cloud persistence — change kept locally for now"
                    failureCode == 409 ->
                        "This book was just taken by another reader — catalogue updated"
                    else ->
                        "Backend refused the change (HTTP $failureCode) — kept in the in-memory catalogue"
                }
            }

            if (failureCode == null || failureCode == 409) {
                refreshFromCloud()
            }
        }
    }

    /**
     * Hydrates the catalogue from the live backend (every authenticated role
     * may GET). A successful fetch replaces the local store with server truth
     * and clears the fallback banner; failures leave local data untouched.
     */
    fun refreshFromCloud() {
        if (!SessionManager.default.isLoggedIn) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = LibraryApiClient.service.listBooks()
                if (response.isSuccessful) {
                    val remoteBooks = (response.body() ?: emptyList()).map { dto -> dto.toDomain() }
                    withContext(Dispatchers.Main) {
                        LibraryCatalogStore.replaceAll(remoteBooks)
                        // Do not clear the 409 notice if we just alerted the user about a concurrency race
                        if (cloudStatusMessage?.contains("just taken") != true) {
                            cloudStatusMessage = null
                        }
                    }
                } else if (response.code() == 401) {
                    withContext(Dispatchers.Main) {
                        cloudStatusMessage =
                            "Live catalogue needs a real backend sign-in — showing the local sample"
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        cloudStatusMessage =
                            "Backend unreachable (HTTP ${response.code()}) — showing the in-memory catalogue"
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    cloudStatusMessage = "Could not reach the backend — showing the in-memory catalogue"
                }
            }
        }
    }

    /** Wire format → domain (server rows carry the authoritative copy counts). */
    private fun BookDto.toDomain(): Book = Book(
        id = id,
        title = title,
        author = author,
        isbn = isbn,
        category = category,
        totalCopies = totalCopies,
        availableCopies = availableCopies,
        shelfLocation = shelfLocation ?: "Main Hall"
    )
}
