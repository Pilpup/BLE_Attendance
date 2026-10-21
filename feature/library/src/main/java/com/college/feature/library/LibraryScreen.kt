package com.college.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.college.core.model.User
import com.college.feature.library.model.Book
import com.college.feature.library.model.LibraryTab
import com.college.feature.library.ui.theme.*
import com.college.feature.library.viewmodel.LibraryCatalogStore
import com.college.feature.library.viewmodel.LibraryViewModel

/**
 * The Library Explorer (Phase 3), three tiers over one shared catalogue:
 *   1. Everyone — search, category filters, live availability, reserve.
 *   2. STAFF & DEVELOPER — Staff Inventory tab: add / edit / remove holdings.
 *   3. DEVELOPER — Dev Maintenance tab + top-bar Tools menu (seed / clear).
 * Role gates are resolved from SessionManager.default inside LibraryViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    user: User,
    onBack: () -> Unit
) {
    val viewModel: LibraryViewModel = viewModel()

    // Role-dependent tab set (the catalogue is for everyone, extra tiers are gated)
    val tabsEnabled = buildList {
        add(LibraryTab.CATALOGUE)
        if (viewModel.canUseStaffTools) add(LibraryTab.STAFF_INVENTORY)
        if (viewModel.isDeveloper) add(LibraryTab.DEV_MAINTENANCE)
    }
    var currentTab by remember { mutableStateOf(LibraryTab.CATALOGUE) }
    val activeTab = tabsEnabled.firstOrNull { it == currentTab } ?: LibraryTab.CATALOGUE

    var showAddBookDialog by remember { mutableStateOf(false) }
    var editingBook by remember { mutableStateOf<Book?>(null) }
    var toolsMenuExpanded by remember { mutableStateOf(false) }

    if (showAddBookDialog) {
        AddEditBookDialog(
            initial = null,
            onDismiss = { showAddBookDialog = false },
            onConfirm = { title, author, isbn, category, totalCopies, _, _ ->
                viewModel.addBook(title, author, isbn, category, totalCopies)
                showAddBookDialog = false
            }
        )
    }
    editingBook?.let { target ->
        AddEditBookDialog(
            initial = target,
            onDismiss = { editingBook = null },
            onConfirm = { title, author, isbn, category, totalCopies, availableCopies, shelf ->
                viewModel.editBook(
                    target.copy(
                        title = title.trim(),
                        author = author.trim(),
                        isbn = isbn.trim().replace("-", "").uppercase(),
                        category = category.trim().uppercase(),
                        totalCopies = totalCopies,
                        availableCopies = availableCopies,
                        shelfLocation = shelf.trim()
                    )
                )
                editingBook = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = textPrimary
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Library Explorer",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "${user.name} · ${user.primaryRole?.name ?: "GUEST"}",
                            fontSize = 12.sp,
                            color = textSecondary
                        )
                    }
                },
                actions = {
                    if (viewModel.isDeveloper) {
                        // Clearly marked DEV chip — never confused with staff work.
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = childPurpleBg,
                            border = BorderStroke(1.dp, childPurple)
                        ) {
                            Text(
                                text = "DEV",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                color = childPurple,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        TextButton(
                            onClick = { toolsMenuExpanded = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Tools", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = textSecondary)
                        }
                        DropdownMenu(
                            expanded = toolsMenuExpanded,
                            onDismissRequest = { toolsMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Seed Sample Books") },
                                onClick = {
                                    viewModel.seedSampleData()
                                    toolsMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear Catalogue Cache") },
                                onClick = {
                                    viewModel.clearCatalogueCache()
                                    toolsMenuExpanded = false
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = cardBg,
                    titleContentColor = textPrimary
                )
            )
        },
        containerColor = appBg,
        floatingActionButton = {
            if (viewModel.canUseStaffTools && activeTab != LibraryTab.DEV_MAINTENANCE) {
                ExtendedFloatingActionButton(
                    onClick = { showAddBookDialog = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add Book", fontWeight = FontWeight.Bold) },
                    containerColor = primaryBlue,
                    contentColor = Color.White
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Cloud / offline fallback notice (library cloud sync is best-effort)
            viewModel.cloudStatusMessage?.let { notice ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = primaryBlueBg,
                    border = BorderStroke(1.dp, primaryBlue)
                ) {
                    Text(
                        text = notice,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = primaryBlue,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (tabsEnabled.size > 1) {
                TabRow(
                    selectedTabIndex = tabsEnabled.indexOf(activeTab),
                    containerColor = cardBg
                ) {
                    tabsEnabled.forEach { tab ->
                        Tab(
                            selected = activeTab == tab,
                            onClick = { currentTab = tab },
                            text = {
                                Text(
                                    text = when (tab) {
                                        LibraryTab.CATALOGUE -> "Catalogue"
                                        LibraryTab.STAFF_INVENTORY -> "Staff Inventory"
                                        LibraryTab.DEV_MAINTENANCE -> "Dev Maintenance"
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        )
                    }
                }
            }

            when (activeTab) {
                LibraryTab.CATALOGUE -> CatalogueTabContent(viewModel)
                LibraryTab.STAFF_INVENTORY ->
                    StaffInventoryTabContent(
                        viewModel = viewModel,
                        onEdit = { editingBook = it }
                    )
                LibraryTab.DEV_MAINTENANCE -> DevMaintenanceTabContent(viewModel)
            }
        }
    }
}

// ------------------------------------------------------------------ Catalogue

@Composable
private fun CatalogueTabContent(viewModel: LibraryViewModel) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Search bar
        OutlinedTextField(
            value = viewModel.searchQuery,
            onValueChange = { viewModel.searchQuery = it },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = textMuted) },
            placeholder = { Text("Search title, author or ISBN…", fontSize = 13.sp) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = primaryBlue,
                unfocusedBorderColor = cardBorder,
                focusedContainerColor = cardBg,
                unfocusedContainerColor = cardBg
            )
        )

        // Category filter chips (derived from what is actually catalogued)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = viewModel.selectedCategory == null,
                    onClick = { viewModel.selectedCategory = null },
                    label = { Text("All", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                )
            }
            items(viewModel.categories) { category ->
                FilterChip(
                    selected = viewModel.selectedCategory == category,
                    onClick = {
                        viewModel.selectedCategory =
                            if (viewModel.selectedCategory == category) null else category
                    },
                    label = { Text(category, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                )
            }
            item {
                FilterChip(
                    selected = viewModel.onlyAvailable,
                    onClick = { viewModel.onlyAvailable = !viewModel.onlyAvailable },
                    label = { Text("Available Only", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                )
            }
        }

        // Book list
        val books = viewModel.filteredBooks
        if (books.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(cardBg, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (viewModel.searchQuery.isBlank() && viewModel.selectedCategory == null)
                            "The catalogue is empty."
                        else
                            "No books match your filters.",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textSecondary
                    )
                    if (viewModel.catalogueSize == 0 && viewModel.isDeveloper) {
                        Text(
                            text = "Use the top-bar Tools → Seed Sample Books to refill the test catalogue.",
                            fontSize = 11.sp,
                            color = textMuted,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(books, key = { it.id }) { book ->
                    BookRow(
                        book = book,
                        staffControls = false,
                        onReserve = { viewModel.reserveBook(book.id) }
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------ Staff Inventory

@Composable
private fun StaffInventoryTabContent(
    viewModel: LibraryViewModel,
    onEdit: (Book) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Inventory summary card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "INVENTORY OVERVIEW",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = textSecondary
                )
                Text(
                    text = "${viewModel.catalogueSize} titles · ${viewModel.availableCount} copies on shelf · " +
                        "${viewModel.catalogueSize - viewModel.availableCount} out with readers",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textPrimary
                )
                Text(
                    text = "Use ✎ to edit details/copies and 🗑 to remove a holding. New titles come in via 'Add Book'.",
                    fontSize = 11.sp,
                    color = textMuted
                )
            }
        }

        val books = LibraryCatalogStore.books.toList()
        if (books.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(cardBg, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Catalogue is empty. Use the 'Add Book' button to start intake.",
                    color = textMuted,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(books, key = { it.id }) { book ->
                    BookRow(
                        book = book,
                        staffControls = true,
                        onReserve = { viewModel.reserveBook(book.id) },
                        onEdit = onEdit,
                        onDelete = { viewModel.removeBook(book.id) }
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------ Dev Maintenance

@Composable
private fun DevMaintenanceTabContent(viewModel: LibraryViewModel) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = childPurpleBg,
                    border = BorderStroke(1.dp, childPurple)
                ) {
                    Text(
                        text = "DEV · CATALOGUE MAINTENANCE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        color = childPurple,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Text(
                    text = "These actions rebuild the in-memory test catalogue. " +
                        "Everyday staff circulation is unaffected.",
                    fontSize = 12.sp,
                    color = textSecondary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.seedSampleData() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryBlue)
                    ) {
                        Text("Seed Sample Books", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { viewModel.clearCatalogueCache() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            "Clear Cache",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = alertRed
                        )
                    }
                }
            }
        }

        // Diagnostics
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "DIAGNOSTICS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = textSecondary
                )
                DiagnosticRow("Books catalogued", "${viewModel.catalogueSize}")
                DiagnosticRow("Copies on shelf", "${viewModel.availableCount}")
                DiagnosticRow("Copies checked out", "${viewModel.catalogueSize - viewModel.availableCount}")
                DiagnosticRow("Categories", viewModel.categories.joinToString(", ").ifBlank { "—" })
                DiagnosticRow("Backend endpoint", "GET /api/v1/library/books")
                DiagnosticRow("Fallback mode", "In-memory catalogue · cloud sync best-effort")
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    val isEndpoint = label == "Backend endpoint"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = textSecondary)
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = textPrimary,
            fontFamily = if (isEndpoint) FontFamily.Monospace else FontFamily.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

// ------------------------------------------------------------------- Book row

/**
 * One catalogue entry. [staffControls] renders the edit/delete affordances —
 * they are only ever passed from the Staff Inventory tab, ordinary members
 * never see them (the defaulted handlers are no-ops in the catalogue tab).
 */
@Composable
private fun BookRow(
    book: Book,
    staffControls: Boolean = false,
    onReserve: () -> Unit,
    onEdit: (Book) -> Unit = {},
    onDelete: (Book) -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, cardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Cover art tile: first letter
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(primaryBlueBg),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = book.title.take(1).uppercase(),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = primaryBlue
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = book.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = book.author,
                    fontSize = 12.sp,
                    color = textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(shape = RoundedCornerShape(6.dp), color = logBg) {
                        Text(
                            text = book.shelfLocation,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Surface(shape = RoundedCornerShape(6.dp), color = primaryBlueBg) {
                        Text(
                            text = book.category,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryBlue,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Availability status pill
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (book.isAvailable) successGreenBg else warningOrangeBg,
                    border = BorderStroke(
                        1.dp,
                        if (book.isAvailable) successGreen.copy(alpha = 0.5f) else warningOrange.copy(alpha = 0.5f)
                    )
                ) {
                    Text(
                        text = if (book.isAvailable)
                            "${book.availableCopies} of ${book.totalCopies} Available"
                        else
                            "Checked Out",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (book.isAvailable) successGreen else warningOrange,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Member action: reserve a copy
                if (book.isAvailable) {
                    OutlinedButton(
                        onClick = onReserve,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                    ) {
                        Text("Reserve", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = primaryBlue)
                    }
                }

                // Staff-only controls (never rendered for ordinary members)
                if (staffControls) {
                    Row {
                        IconButton(onClick = { onEdit(book) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Edit book",
                                tint = primaryBlue,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = { onDelete(book) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Remove book",
                                tint = alertRed,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------- Dialog

@Composable
private fun AddEditBookDialog(
    initial: Book?,
    onDismiss: () -> Unit,
    onConfirm: (title: String, author: String, isbn: String, category: String, totalCopies: Int, availableCopies: Int, shelfLocation: String) -> Unit
) {
    val isEdit = initial != null
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var author by remember { mutableStateOf(initial?.author ?: "") }
    var isbn by remember { mutableStateOf(initial?.isbn ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: "") }
    var copiesText by remember { mutableStateOf(initial?.totalCopies?.toString() ?: "") }
    var availableText by remember { mutableStateOf(initial?.availableCopies?.toString() ?: "") }
    var shelfText by remember { mutableStateOf(initial?.shelfLocation ?: "Main Hall") }

    val total = copiesText.toIntOrNull()
    val available = availableText.toIntOrNull()
    val inputsValid = title.isNotBlank() && author.isNotBlank() && isbn.isNotBlank() &&
        category.isNotBlank() && total != null &&
        ((isEdit && available != null) || !isEdit)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isEdit) "Edit Book Details" else "Add Book to Catalogue",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Author") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = isbn,
                    onValueChange = { isbn = it },
                    label = { Text("ISBN") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category (e.g. CS, MATH, PHYSICS, BIO)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = copiesText,
                        onValueChange = { copiesText = it.filter { c -> c.isDigit() } },
                        label = { Text(if (isEdit) "Total Copies" else "Copies") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    if (isEdit) {
                        OutlinedTextField(
                            value = availableText,
                            onValueChange = { availableText = it.filter { c -> c.isDigit() } },
                            label = { Text("Available") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (isEdit) {
                    OutlinedTextField(
                        value = shelfText,
                        onValueChange = { shelfText = it },
                        label = { Text("Shelf Location (e.g. Shelf 3-B)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        title,
                        author,
                        isbn,
                        category,
                        total ?: 0,
                        available ?: 0,
                        shelfText
                    )
                },
                enabled = inputsValid
            ) {
                Text(if (isEdit) "Save" else "Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
