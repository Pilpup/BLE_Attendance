package com.college.feature.library

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.college.core.ui.components.AppCardGroup
import com.college.core.ui.components.AppDivider
import com.college.core.ui.components.AppHeader
import com.college.core.ui.components.AppTextField
import com.college.core.ui.components.appScrollbar
import com.college.core.ui.components.appTextFieldColors
import com.college.core.ui.theme.AppTheme
import com.college.feature.library.model.Book
import com.college.feature.library.model.LibraryTab
import com.college.feature.library.scanner.BarcodeScannerModal
import com.college.feature.library.viewmodel.LibraryCatalogStore
import com.college.feature.library.viewmodel.LibraryViewModel

/**
 * The Library Explorer, styled after the home screen design aesthetic:
 *   1. Everyone — search, category filters, live availability, reserve copy.
 *   2. STAFF & DEVELOPER — Staff Inventory: add / edit / remove holdings.
 *   3. DEVELOPER — Dev Maintenance + top-bar Tools menu (seed / clear).
 * Consistent with HomeScreen's rounded surfaces, color tokens, and typography.
 */
@Composable
fun LibraryScreen(
    user: User,
    onBack: () -> Unit
) {
    val viewModel: LibraryViewModel = viewModel()

    // Role-dependent tab set (catalogue is for everyone, extra tiers are gated)
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top Navigation Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = AppTheme.colors.textPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                if (viewModel.isDeveloper) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = AppTheme.colors.primaryPurpleBg,
                            border = BorderStroke(1.dp, AppTheme.colors.primaryPurple.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = "DEV",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.colors.primaryPurple,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        Box {
                            IconButton(onClick = { toolsMenuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Tools",
                                    tint = AppTheme.colors.textSecondary
                                )
                            }
                            DropdownMenu(
                                expanded = toolsMenuExpanded,
                                onDismissRequest = { toolsMenuExpanded = false },
                                modifier = Modifier.background(AppTheme.colors.surface)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Seed Sample Books", color = AppTheme.colors.textPrimary) },
                                    onClick = {
                                        viewModel.seedSampleData()
                                        toolsMenuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Clear Catalogue Cache", color = AppTheme.colors.dangerRed) },
                                    onClick = {
                                        viewModel.clearCatalogueCache()
                                        toolsMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Header Title
            AppHeader(
                title = "Library",
                subtitle = "${user.name} • ${user.primaryRole?.name ?: "GUEST"}",
                modifier = Modifier.padding(top = 0.dp, bottom = 2.dp)
            )

            // Cloud status notice banner
            viewModel.cloudStatusMessage?.let { notice ->
                val isWarning = notice.contains("taken") || notice.contains("refused") || notice.contains("rejected")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = if (isWarning) AppTheme.colors.warningAmberBg else AppTheme.colors.infoBlueBg,
                    border = BorderStroke(
                        1.dp,
                        if (isWarning) AppTheme.colors.warningAmber.copy(alpha = 0.5f) else AppTheme.colors.infoBlue.copy(alpha = 0.5f)
                    )
                ) {
                    Text(
                        text = notice,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isWarning) AppTheme.colors.warningAmber else AppTheme.colors.infoBlue,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }

            // Segmented pill tab switcher
            if (tabsEnabled.size > 1) {
               Surface(
                   shape = RoundedCornerShape(22.dp),
                   color = AppTheme.colors.surface,
                   border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder),
                   modifier = Modifier.fillMaxWidth()
               ) {
                   Row(
                       modifier = Modifier
                           .fillMaxWidth()
                           .padding(4.dp),
                       horizontalArrangement = Arrangement.spacedBy(4.dp)
                   ) {
                       tabsEnabled.forEach { tab ->
                           val isSelected = activeTab == tab
                           Surface(
                               onClick = { currentTab = tab },
                               shape = RoundedCornerShape(18.dp),
                               color = if (isSelected) AppTheme.colors.primaryPurple else Color.Transparent,
                               modifier = Modifier.weight(1f)
                           ) {
                               Box(
                                   modifier = Modifier.padding(vertical = 10.dp),
                                   contentAlignment = Alignment.Center
                               ) {
                                   Text(
                                       text = when (tab) {
                                           LibraryTab.CATALOGUE -> "Catalogue"
                                           LibraryTab.STAFF_INVENTORY -> "Inventory"
                                           LibraryTab.DEV_MAINTENANCE -> "Maintenance"
                                       },
                                       fontSize = 13.sp,
                                       fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                       color = if (isSelected) Color.White else AppTheme.colors.textSecondary
                                   )
                               }
                           }
                       }
                   }
               }
            }

            // Content Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (activeTab) {
                    LibraryTab.CATALOGUE -> CatalogueTabContent(
                        viewModel = viewModel,
                        canAddBook = viewModel.canUseStaffTools
                    )
                    LibraryTab.STAFF_INVENTORY -> StaffInventoryTabContent(
                        viewModel = viewModel,
                        onEdit = { editingBook = it }
                    )
                    LibraryTab.DEV_MAINTENANCE -> DevMaintenanceTabContent(viewModel)
                }
            }
        }

        // Floating "Add Book" Pill pinned at bottom center
        if (viewModel.canUseStaffTools && activeTab != LibraryTab.DEV_MAINTENANCE) {
            Surface(
                onClick = { showAddBookDialog = true },
                shape = RoundedCornerShape(22.dp),
                color = AppTheme.colors.primaryPurple,
                shadowElevation = if (AppTheme.colors.isDark) 0.dp else 4.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Book",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Add Book",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Catalogue Tab Content
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CatalogueTabContent(
    viewModel: LibraryViewModel,
    canAddBook: Boolean
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Search bar
        AppTextField(
            value = viewModel.searchQuery,
            onValueChange = { viewModel.searchQuery = it },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = AppTheme.colors.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (viewModel.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.searchQuery = "" }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear",
                            tint = AppTheme.colors.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            placeholder = {
                Text(
                    text = "Search title, author or ISBN…",
                    fontSize = 14.sp,
                    color = AppTheme.colors.textSecondary
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(22.dp),
            colors = appTextFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )

        val books = viewModel.filteredBooks
        if (books.isEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(22.dp),
                color = AppTheme.colors.surface,
                border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = if (viewModel.searchQuery.isBlank())
                                "The catalogue is empty."
                            else
                                "No books match your search.",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.colors.textSecondary
                        )
                        if (viewModel.catalogueSize == 0 && viewModel.isDeveloper) {
                            Text(
                                text = "Use top-right Tools → Seed Sample Books to refill the test catalogue.",
                                fontSize = 12.sp,
                                color = AppTheme.colors.textMuted,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = if (canAddBook) 76.dp else 16.dp)
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

// ─────────────────────────────────────────────────────────────────────────────
// Staff Inventory Tab Content
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StaffInventoryTabContent(
    viewModel: LibraryViewModel,
    onEdit: (Book) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Inventory Overview Card
        AppCardGroup {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "INVENTORY OVERVIEW",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.textSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    InventoryStatItem(label = "Titles", value = "${viewModel.catalogueSize}")
                    InventoryStatItem(label = "On Shelf", value = "${viewModel.availableCount}")
                    InventoryStatItem(
                        label = "Borrowed",
                        value = "${viewModel.catalogueSize - viewModel.availableCount}"
                    )
                }
                HorizontalDivider(color = AppTheme.colors.divider)
                Text(
                    text = "Use ✎ to edit details/copies and 🗑 to remove a holding. New titles come in via 'Add Book'.",
                    fontSize = 12.sp,
                    color = AppTheme.colors.textMuted
                )
            }
        }

        val books = LibraryCatalogStore.books.toList()
        if (books.isEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(22.dp),
                color = AppTheme.colors.surface,
                border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Catalogue is empty. Use the 'Add Book' button to start intake.",
                        color = AppTheme.colors.textMuted,
                        fontSize = 13.5.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 76.dp)
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

@Composable
private fun InventoryStatItem(label: String, value: String) {
    Column {
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = AppTheme.colors.textPrimary
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = AppTheme.colors.textSecondary
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Dev Maintenance Tab Content
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DevMaintenanceTabContent(viewModel: LibraryViewModel) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .appScrollbar(scrollState)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Actions Card
        AppCardGroup {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = AppTheme.colors.primaryPurpleBg,
                    border = BorderStroke(1.dp, AppTheme.colors.primaryPurple.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "DEV · CATALOGUE MAINTENANCE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.primaryPurple,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Text(
                    text = "These actions rebuild the in-memory test catalogue. Everyday staff circulation is unaffected.",
                    fontSize = 12.5.sp,
                    color = AppTheme.colors.textSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { viewModel.seedSampleData() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primaryPurple)
                    ) {
                        Text(
                            text = "Seed Sample Books",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.clearCatalogueCache() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, AppTheme.colors.dangerRed.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = "Clear Cache",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.colors.dangerRed
                        )
                    }
                }
            }
        }

        // Diagnostics Card
        AppCardGroup {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "DIAGNOSTICS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                DiagnosticItem(label = "Books catalogued", value = "${viewModel.catalogueSize}")
                AppDivider()
                DiagnosticItem(label = "Copies on shelf", value = "${viewModel.availableCount}")
                AppDivider()
                DiagnosticItem(
                    label = "Copies checked out",
                    value = "${viewModel.catalogueSize - viewModel.availableCount}"
                )
                AppDivider()
                DiagnosticItem(
                    label = "Categories",
                    value = viewModel.categories.joinToString(", ").ifBlank { "—" }
                )
                AppDivider()
                DiagnosticItem(
                    label = "Backend endpoint",
                    value = "GET /api/v1/library/books",
                    isCode = true
                )
                AppDivider()
                DiagnosticItem(
                    label = "Fallback mode",
                    value = "In-memory catalogue · cloud sync best-effort"
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun DiagnosticItem(
    label: String,
    value: String,
    isCode: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = AppTheme.colors.textSecondary
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppTheme.colors.textPrimary,
            fontFamily = if (isCode) FontFamily.Monospace else FontFamily.Default,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Book Row Item Component
// ─────────────────────────────────────────────────────────────────────────────

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

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .clickable { expanded = !expanded }
            .animateContentSize(),
        shape = RoundedCornerShape(22.dp),
        color = AppTheme.colors.surface,
        border = BorderStroke(
            1.dp,
            if (expanded) AppTheme.colors.primaryPurple.copy(alpha = 0.6f) else AppTheme.colors.surfaceBorder
        ),
        shadowElevation = if (AppTheme.colors.isDark || !expanded) 0.dp else 2.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Row: Avatar on left, Title & Author in middle, Chevron on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Book Cover Tile / Initial Badge (Anchored at Top)
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(AppTheme.colors.primaryPurpleBg, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = book.title.take(1).uppercase(),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.primaryPurple
                    )
                }

                // Title & Author (fills all remaining width)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = book.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.colors.textPrimary,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = book.author,
                        fontSize = 12.5.sp,
                        color = AppTheme.colors.textSecondary,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Chevron expand/collapse indicator (Clean, takes minimal width)
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse details" else "Expand details",
                    tint = AppTheme.colors.textSecondary,
                    modifier = Modifier
                        .size(22.dp)
                        .padding(top = 2.dp)
                )
            }

            // Sub-row: Availability (clean text, NO BOX) & Category Pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Availability text - clean text without red/green/orange box
                Text(
                    text = if (book.isAvailable)
                        "${book.availableCopies} of ${book.totalCopies} Available"
                    else
                        "Checked Out",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (book.isAvailable) AppTheme.colors.successGreen else AppTheme.colors.warningAmber
                )

                // Category tag
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AppTheme.colors.primaryPurpleBg
                ) {
                    Text(
                        text = book.category,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.primaryPurple,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // Expanded Details Section
            if (expanded) {
                HorizontalDivider(color = AppTheme.colors.divider)

                // Metadata tags: Shelf, Edition, ISBN, Total Copies
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AppTheme.colors.background,
                            border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder)
                        ) {
                            Text(
                                text = "Shelf: ${book.shelfLocation}",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = AppTheme.colors.textPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AppTheme.colors.background,
                            border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder)
                        ) {
                            Text(
                                text = "ISBN: ${book.isbn}",
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                color = AppTheme.colors.textPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }

                    book.edition?.let { ed ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AppTheme.colors.background,
                            border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder)
                        ) {
                            Text(
                                text = "Edition: $ed",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = AppTheme.colors.textSecondary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }

                    Text(
                        text = "Inventory: ${book.availableCopies} available on shelf · ${book.totalCopies} total copies in collection",
                        fontSize = 12.sp,
                        color = AppTheme.colors.textSecondary
                    )
                }

                // Overview section
                if (!book.overview.isNullOrBlank()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Overview",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.primaryPurple
                        )
                        Text(
                            text = book.overview,
                            fontSize = 12.5.sp,
                            color = AppTheme.colors.textSecondary,
                            lineHeight = 17.sp
                        )
                    }
                }

                // Table of Contents section
                if (!book.tableOfContents.isNullOrBlank()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = null,
                                tint = AppTheme.colors.primaryPurple,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Table of Contents",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.colors.primaryPurple
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = AppTheme.colors.background,
                            border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                book.tableOfContents.lines().filter { it.isNotBlank() }.forEach { line ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Text(
                                            text = "•",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AppTheme.colors.primaryPurple
                                        )
                                        Text(
                                            text = line.trim(),
                                            fontSize = 12.sp,
                                            color = AppTheme.colors.textPrimary,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Actions Footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (staffControls) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onEdit(book) },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, AppTheme.colors.primaryPurple.copy(alpha = 0.6f)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = null,
                                    tint = AppTheme.colors.primaryPurple,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Edit",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppTheme.colors.primaryPurple
                                )
                            }
                            OutlinedButton(
                                onClick = { onDelete(book) },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, AppTheme.colors.dangerRed.copy(alpha = 0.6f)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = null,
                                    tint = AppTheme.colors.dangerRed,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Delete",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppTheme.colors.dangerRed
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    if (book.isAvailable) {
                        Button(
                            onClick = onReserve,
                            enabled = !isReserving,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppTheme.colors.primaryPurple,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            if (isReserving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Reserving…", fontSize = 12.sp, color = Color.White)
                            } else {
                                Text("Reserve Copy", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    } else {
                        Text(
                            text = "All Copies Checked Out",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.colors.warningAmber
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Add / Edit Book Dialog
// ─────────────────────────────────────────────────────────────────────────────

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
        containerColor = AppTheme.colors.surface,
        titleContentColor = AppTheme.colors.textPrimary,
        textContentColor = AppTheme.colors.textSecondary,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = if (isEdit) "Edit Book Details" else "Add Book to Catalogue",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = AppTheme.colors.textPrimary
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
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AppTheme.colors.primaryPurple.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = AppTheme.colors.primaryPurpleBg),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.QrCodeScanner,
                            contentDescription = null,
                            tint = AppTheme.colors.primaryPurple,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Scan Barcode (Auto-Fill Details)",
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.primaryPurple,
                            fontSize = 13.sp
                        )
                    }

                    lookupBanner?.let { banner ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (banner.startsWith("Populated")) AppTheme.colors.primaryPurpleBg else AppTheme.colors.warningAmberBg,
                            border = BorderStroke(
                                1.dp,
                                if (banner.startsWith("Populated")) AppTheme.colors.primaryPurple else AppTheme.colors.warningAmber
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = banner,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (banner.startsWith("Populated")) AppTheme.colors.primaryPurple else AppTheme.colors.warningAmber,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                // Book Title
                AppTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Book Name / Title *") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                // Author
                AppTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Author(s) *") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                // ISBN with Auto-Fill button
                AppTextField(
                    value = isbn,
                    onValueChange = { isbn = it },
                    label = { Text("ISBN * (10 or 13 digits)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        if (viewModel.isLookingUpBook) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = AppTheme.colors.primaryPurple
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
                                Text(
                                    text = "Auto-Fill",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppTheme.colors.primaryPurple
                                )
                            }
                        }
                    },
                    colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                // Edition
                AppTextField(
                    value = edition,
                    onValueChange = { edition = it },
                    label = { Text("Edition (e.g. 3rd Edition, 2022)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                // Index / Shelf Location
                AppTextField(
                    value = shelfText,
                    onValueChange = { shelfText = it },
                    label = { Text("Index / Shelf Location (e.g. Shelf 2-A, TK5105)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                // Category
                AppTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category * (e.g. CS, MATH, PHYSICS, BIO)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                // Overview
                AppTextField(
                    value = overview,
                    onValueChange = { overview = it },
                    label = { Text("Overview / Description") },
                    singleLine = false,
                    shape = RoundedCornerShape(12.dp),
                    colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                // Table of Contents
                AppTextField(
                    value = tableOfContents,
                    onValueChange = { tableOfContents = it },
                    label = { Text("Table of Contents (Chapters / Sections)") },
                    singleLine = false,
                    shape = RoundedCornerShape(12.dp),
                    colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                // Copies
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTextField(
                        value = copiesText,
                        onValueChange = { copiesText = it.filter { c -> c.isDigit() } },
                        label = { Text(if (isEdit) "Total Copies *" else "Copies *") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = appTextFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                    if (isEdit) {
                        AppTextField(
                            value = availableText,
                            onValueChange = { availableText = it.filter { c -> c.isDigit() } },
                            label = { Text("Available *") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = appTextFieldColors(),
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
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppTheme.colors.primaryPurple,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = if (isEdit) "Save" else "Add to Catalogue",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = AppTheme.colors.textSecondary)
            }
        }
    )
}
