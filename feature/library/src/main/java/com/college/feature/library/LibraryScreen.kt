package com.college.feature.library

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import com.college.feature.library.scanner.BarcodeScannerModal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
            viewModel = viewModel,
            initial = null,
            onDismiss = { showAddBookDialog = false },
            onConfirm = { title, author, isbn, category, totalCopies, _, shelf, edition, overview, tableOfContents ->
                viewModel.addBook(
                    title = title,
                    author = author,
                    isbn = isbn,
                    category = category,
                    copies = totalCopies,
                    shelfLocation = shelf,
                    edition = edition,
                    overview = overview,
                    tableOfContents = tableOfContents
                )
                showAddBookDialog = false
            }
        )
    }
    editingBook?.let { target ->
        AddEditBookDialog(
            viewModel = viewModel,
            initial = target,
            onDismiss = { editingBook = null },
            onConfirm = { title, author, isbn, category, totalCopies, availableCopies, shelf, edition, overview, tableOfContents ->
                viewModel.editBook(
                    target.copy(
                        title = title.trim(),
                        author = author.trim(),
                        isbn = isbn.trim().replace("-", "").uppercase(),
                        category = category.trim().uppercase(),
                        totalCopies = totalCopies,
                        availableCopies = availableCopies,
                        shelfLocation = shelf.trim(),
                        edition = edition,
                        overview = overview,
                        tableOfContents = tableOfContents
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
                            fontSize = 18.sp,
                            color = textPrimary
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
                            onDismissRequest = { toolsMenuExpanded = false },
                            modifier = Modifier.background(cardBg)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Seed Sample Books", color = textPrimary) },
                                onClick = {
                                    viewModel.seedSampleData()
                                    toolsMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear Catalogue Cache", color = alertRed) },
                                onClick = {
                                    viewModel.clearCatalogueCache()
                                    toolsMenuExpanded = false
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = appBg,
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
                val isWarning = notice.contains("taken") || notice.contains("refused") || notice.contains("rejected")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = if (isWarning) warningOrangeBg else primaryBlueBg,
                    border = BorderStroke(1.dp, if (isWarning) warningOrange.copy(alpha = 0.5f) else primaryBlue)
                ) {
                    Text(
                        text = notice,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isWarning) warningOrange else primaryBlue,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (tabsEnabled.size > 1) {
                TabRow(
                    selectedTabIndex = tabsEnabled.indexOf(activeTab),
                    containerColor = cardBg,
                    contentColor = primaryBlue,
                    divider = { HorizontalDivider(color = cardBorder) }
                ) {
                    tabsEnabled.forEach { tab ->
                        val isSelected = activeTab == tab
                        Tab(
                            selected = isSelected,
                            onClick = { currentTab = tab },
                            text = {
                                Text(
                                    text = when (tab) {
                                        LibraryTab.CATALOGUE -> "Catalogue"
                                        LibraryTab.STAFF_INVENTORY -> "Staff Inventory"
                                        LibraryTab.DEV_MAINTENANCE -> "Dev Maintenance"
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (isSelected) primaryBlue else textSecondary
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
            placeholder = { Text("Search title, author or ISBN…", fontSize = 13.sp, color = textMuted) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = textPrimary,
                unfocusedTextColor = textPrimary,
                focusedBorderColor = primaryBlue,
                unfocusedBorderColor = cardBorder,
                focusedContainerColor = cardBg,
                unfocusedContainerColor = cardBg,
                cursorColor = primaryBlue
            )
        )

        // Book list
        val books = viewModel.filteredBooks
        if (books.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(cardBg, RoundedCornerShape(12.dp))
                    .border(1.dp, cardBorder, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (viewModel.searchQuery.isBlank())
                            "The catalogue is empty."
                        else
                            "No books match your search.",
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
                        isReserving = viewModel.isReserving(book.id),
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
                    .background(cardBg, RoundedCornerShape(12.dp))
                    .border(1.dp, cardBorder, RoundedCornerShape(12.dp)),
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
                        isReserving = viewModel.isReserving(book.id),
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
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryBlue,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Seed Sample Books", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { viewModel.clearCatalogueCache() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, alertRed.copy(alpha = 0.6f))
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
    isReserving: Boolean = false,
    onReserve: () -> Unit,
    onEdit: (Book) -> Unit = {},
    onDelete: (Book) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, if (expanded) primaryBlue.copy(alpha = 0.5f) else cardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = if (expanded) 3.dp else 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Summary header (always visible)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Cover art tile: first letter
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(primaryBlueBg)
                        .border(1.dp, primaryBlue.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
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
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = book.author,
                        fontSize = 12.sp,
                        color = textSecondary,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = logBg,
                            border = BorderStroke(1.dp, cardBorder)
                        ) {
                            Text(
                                text = book.shelfLocation,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textSecondary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = primaryBlueBg,
                            border = BorderStroke(1.dp, primaryBlue.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = book.category,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = primaryBlue,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        book.edition?.let { ed ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = logBg,
                                border = BorderStroke(1.dp, cardBorder)
                            ) {
                                Text(
                                    text = ed,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = textSecondary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
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

                    // Dropdown chevron indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = if (expanded) "Less" else "Details",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryBlue
                        )
                        Icon(
                            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (expanded) "Collapse details" else "Expand details",
                            tint = primaryBlue,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Dropdown expanded content showing all details
            if (expanded) {
                HorizontalDivider(color = cardBorder, modifier = Modifier.padding(vertical = 2.dp))

                // Metadata details: ISBN, Location, Total Inventory
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = logBg,
                            border = BorderStroke(1.dp, cardBorder)
                        ) {
                            Text(
                                text = "ISBN: ${book.isbn}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                color = textPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = logBg,
                            border = BorderStroke(1.dp, cardBorder)
                        ) {
                            Text(
                                text = "Shelf: ${book.shelfLocation}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = textPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        book.edition?.let { ed ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = logBg,
                                border = BorderStroke(1.dp, cardBorder)
                            ) {
                                Text(
                                    text = ed,
                                    fontSize = 11.sp,
                                    color = textSecondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = "Inventory: ${book.availableCopies} available on shelf · ${book.totalCopies} total copies in collection",
                        fontSize = 11.sp,
                        color = textMuted
                    )
                }

                // Overview section (if present)
                if (!book.overview.isNullOrBlank()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Overview",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryBlue
                        )
                        Text(
                            text = book.overview,
                            fontSize = 12.sp,
                            color = textSecondary,
                            lineHeight = 16.sp
                        )
                    }
                }

                // Table of Contents section (if present)
                if (!book.tableOfContents.isNullOrBlank()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = null,
                                tint = primaryBlue,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Table of Contents",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = primaryBlue
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = logBg,
                            border = BorderStroke(1.dp, cardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                book.tableOfContents.lines().filter { it.isNotBlank() }.forEach { line ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Text(
                                            text = "•",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = primaryBlue
                                        )
                                        Text(
                                            text = line.trim(),
                                            fontSize = 11.sp,
                                            color = textPrimary,
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Actions footer in expanded drop-down
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (staffControls) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onEdit(book) },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, primaryBlue.copy(alpha = 0.5f)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = null,
                                    tint = primaryBlue,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Edit", fontSize = 11.sp, color = primaryBlue)
                            }
                            OutlinedButton(
                                onClick = { onDelete(book) },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, alertRed.copy(alpha = 0.5f)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = null,
                                    tint = alertRed,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Delete", fontSize = 11.sp, color = alertRed)
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    if (book.isAvailable) {
                        Button(
                            onClick = onReserve,
                            enabled = !isReserving,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primaryBlue,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            if (isReserving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Reserving…", fontSize = 11.sp, color = Color.White)
                            } else {
                                Text("Reserve Copy", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    } else {
                        Text(
                            text = "All Copies Checked Out",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = warningOrange
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------- Dialog

@Composable
private fun AddEditBookDialog(
    viewModel: LibraryViewModel,
    initial: Book?,
    onDismiss: () -> Unit,
    onConfirm: (
        title: String,
        author: String,
        isbn: String,
        category: String,
        totalCopies: Int,
        availableCopies: Int,
        shelfLocation: String,
        edition: String?,
        overview: String?,
        tableOfContents: String?
    ) -> Unit
) {
    val isEdit = initial != null
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var author by remember { mutableStateOf(initial?.author ?: "") }
    var isbn by remember { mutableStateOf(initial?.isbn ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: "") }
    var copiesText by remember { mutableStateOf(initial?.totalCopies?.toString() ?: "1") }
    var availableText by remember { mutableStateOf(initial?.availableCopies?.toString() ?: "1") }
    var shelfText by remember { mutableStateOf(initial?.shelfLocation ?: "Main Hall") }
    var edition by remember { mutableStateOf(initial?.edition ?: "") }
    var overview by remember { mutableStateOf(initial?.overview ?: "") }
    var tableOfContents by remember { mutableStateOf(initial?.tableOfContents ?: "") }

    var showBarcodeScanner by remember { mutableStateOf(false) }
    var lookupBanner by remember { mutableStateOf<String?>(null) }

    val total = copiesText.toIntOrNull()
    val available = availableText.toIntOrNull()
    val inputsValid = title.isNotBlank() && author.isNotBlank() && isbn.isNotBlank() &&
        category.isNotBlank() && total != null &&
        ((isEdit && available != null) || !isEdit)

    if (showBarcodeScanner) {
        BarcodeScannerModal(
            onDismiss = { showBarcodeScanner = false },
            onBarcodeScanned = { scannedIsbn ->
                isbn = scannedIsbn
                showBarcodeScanner = false
                viewModel.lookupBook(scannedIsbn) { result ->
                    if (result != null) {
                        title = result.title
                        author = result.author
                        edition = result.edition ?: edition
                        shelfText = result.index ?: shelfText
                        category = result.category
                        overview = result.overview ?: overview
                        tableOfContents = result.tableOfContents ?: ""
                        val tocInfo = if (!result.tableOfContents.isNullOrBlank()) {
                            val count = result.tableOfContents.lines().count { it.isNotBlank() }
                            " · $count TOC chapters found"
                        } else {
                            " · No TOC available online (left empty)"
                        }
                        lookupBanner = "Populated via ${result.source ?: "API"}$tocInfo"
                    } else {
                        lookupBanner = "Barcode: $scannedIsbn (Enter details manually or tap Auto-Fill)"
                    }
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cardBg,
        titleContentColor = textPrimary,
        textContentColor = textSecondary,
        title = {
            Text(
                text = if (isEdit) "Edit Book Details" else "Add Book to Catalogue",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = textPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Barcode scanner trigger at top of intake dialog
                if (!isEdit) {
                    OutlinedButton(
                        onClick = { showBarcodeScanner = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, primaryBlue.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = primaryBlueBg),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.QrCodeScanner,
                            contentDescription = null,
                            tint = primaryBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Scan Barcode (Auto-Fill Details)",
                            fontWeight = FontWeight.Bold,
                            color = primaryBlue,
                            fontSize = 13.sp
                        )
                    }

                    lookupBanner?.let { banner ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (banner.startsWith("Populated")) primaryBlueBg else warningOrangeBg,
                            border = BorderStroke(1.dp, if (banner.startsWith("Populated")) primaryBlue else warningOrange),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = banner,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (banner.startsWith("Populated")) primaryBlue else warningOrange,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                val textFieldColors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = primaryBlue,
                    unfocusedBorderColor = cardBorder,
                    focusedTextColor = textPrimary,
                    unfocusedTextColor = textPrimary,
                    focusedLabelColor = primaryBlue,
                    unfocusedLabelColor = textSecondary,
                    focusedContainerColor = logBg,
                    unfocusedContainerColor = logBg,
                    cursorColor = primaryBlue
                )

                // 1. Book Name
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Book Name / Title *") },
                    singleLine = true,
                    colors = textFieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                // 2. Author
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Author(s) *") },
                    singleLine = true,
                    colors = textFieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                // 3. ISBN with Auto-Fill Action Button
                OutlinedTextField(
                    value = isbn,
                    onValueChange = { isbn = it },
                    label = { Text("ISBN * (10 or 13 digits)") },
                    singleLine = true,
                    trailingIcon = {
                        if (viewModel.isLookingUpBook) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = primaryBlue
                            )
                        } else {
                            TextButton(
                                onClick = {
                                    viewModel.lookupBook(isbn) { result ->
                                        if (result != null) {
                                            title = result.title
                                            author = result.author
                                            edition = result.edition ?: edition
                                            shelfText = result.index ?: shelfText
                                            category = result.category
                                            overview = result.overview ?: overview
                                            tableOfContents = result.tableOfContents ?: ""
                                            val tocInfo = if (!result.tableOfContents.isNullOrBlank()) {
                                                val count = result.tableOfContents.lines().count { it.isNotBlank() }
                                                " · $count TOC chapters found"
                                            } else {
                                                " · No TOC available online (left empty)"
                                            }
                                            lookupBanner = "Populated via ${result.source ?: "API"}$tocInfo"
                                        } else {
                                            lookupBanner = "No metadata found for ISBN $isbn"
                                        }
                                    }
                                },
                                enabled = isbn.trim().length >= 8
                            ) {
                                Text("Auto-Fill", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = primaryBlue)
                            }
                        }
                    },
                    colors = textFieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                // 4. Edition
                OutlinedTextField(
                    value = edition,
                    onValueChange = { edition = it },
                    label = { Text("Edition (e.g. 3rd Edition, 2022)") },
                    singleLine = true,
                    colors = textFieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                // 5. Index / Shelf Location
                OutlinedTextField(
                    value = shelfText,
                    onValueChange = { shelfText = it },
                    label = { Text("Index / Shelf Location (e.g. Shelf 2-A, TK5105)") },
                    singleLine = true,
                    colors = textFieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                // 6. Category
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category * (e.g. CS, MATH, PHYSICS, BIO)") },
                    singleLine = true,
                    colors = textFieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                // 7. Overview
                OutlinedTextField(
                    value = overview,
                    onValueChange = { overview = it },
                    label = { Text("Overview / Description") },
                    minLines = 2,
                    maxLines = 4,
                    colors = textFieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                // 8. Table of Contents
                OutlinedTextField(
                    value = tableOfContents,
                    onValueChange = { tableOfContents = it },
                    label = { Text("Table of Contents (Chapters / Sections)") },
                    minLines = 2,
                    maxLines = 4,
                    colors = textFieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                // Copies
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = copiesText,
                        onValueChange = { copiesText = it.filter { c -> c.isDigit() } },
                        label = { Text(if (isEdit) "Total Copies *" else "Copies *") },
                        singleLine = true,
                        colors = textFieldColors,
                        modifier = Modifier.weight(1f)
                    )
                    if (isEdit) {
                        OutlinedTextField(
                            value = availableText,
                            onValueChange = { availableText = it.filter { c -> c.isDigit() } },
                            label = { Text("Available *") },
                            singleLine = true,
                            colors = textFieldColors,
                            modifier = Modifier.weight(1f)
                        )
                    }
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
                        shelfText,
                        edition.ifBlank { null },
                        overview.ifBlank { null },
                        tableOfContents.ifBlank { null }
                    )
                },
                enabled = inputsValid,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = primaryBlue,
                    contentColor = Color.White
                )
            ) {
                Text(if (isEdit) "Save" else "Add to Catalogue", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = textSecondary)
            }
        }
    )
}
