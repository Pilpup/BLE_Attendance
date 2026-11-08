package com.college.feature.library

import android.content.res.Configuration
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.college.core.model.User
import com.college.core.ui.components.AppCardGroup
import com.college.core.ui.components.AppDivider
import com.college.core.ui.components.AppHeader
import com.college.core.ui.components.AppSearchPill
import com.college.core.ui.components.AppTextField
import com.college.core.ui.components.appTextFieldColors
import com.college.core.ui.theme.AppTheme
import com.college.feature.library.model.Book
import com.college.feature.library.model.LibraryTab
import com.college.feature.library.scanner.BarcodeScannerModal
import com.college.feature.library.viewmodel.LibraryCatalogStore
import com.college.feature.library.viewmodel.LibraryViewModel

// Library explorer supporting catalogue browsing, staff inventory, and developer maintenance
@Composable
fun LibraryScreen(
    @Suppress("UNUSED_PARAMETER") user: User,
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

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(if (isLandscape) 8.dp else 12.dp)
        ) {
            // Top Navigation Bar (responsive for portrait & landscape)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (isLandscape) 4.dp else 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = AppTheme.colors.textPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // In landscape, combine title directly in top bar to save vertical space
                    if (isLandscape) {
                        Text(
                            text = "Library",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.textPrimary
                        )
                    }
                }

                if (viewModel.isDeveloper) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = AppTheme.colors.surface,
                            border = null
                        ) {
                            Text(
                                text = "DEV",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.colors.textSecondary,
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

            // Header Title (rendered prominent in portrait for staff/dev with tabs, compact in landscape)
            if (!isLandscape && viewModel.canUseStaffTools) {
                AppHeader(
                    title = "Library",
                    modifier = Modifier.padding(top = 0.dp, bottom = 4.dp)
                )
            }

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
                   border = null,
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
                        isLandscape = isLandscape
                    )
                    LibraryTab.STAFF_INVENTORY -> StaffInventoryTabContent(
                        viewModel = viewModel,
                        onEdit = { editingBook = it }
                    )
                    LibraryTab.DEV_MAINTENANCE -> DevMaintenanceTabContent(viewModel)
                }
            }
        }

        // Floating search bar pinned at bottom center in Catalogue (types directly, aligns with keyboard)
        if (activeTab == LibraryTab.CATALOGUE) {
            AppSearchPill(
                value = viewModel.searchQuery,
                onValueChange = { viewModel.searchQuery = it },
                placeholder = "Search library",
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // Floating "Add Book" Pill pinned at bottom center in Staff Inventory
        if (viewModel.canUseStaffTools && activeTab == LibraryTab.STAFF_INVENTORY) {
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

// Catalogue tab content displaying available books in a card island
@Composable
private fun CatalogueTabContent(
    viewModel: LibraryViewModel,
    isLandscape: Boolean
) {
    val scrollState = rememberScrollState()
    val books = viewModel.filteredBooks

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Prominent title scrolls with the catalogue for student/teacher view
        if (!viewModel.canUseStaffTools && !isLandscape) {
            AppHeader(
                title = "Library",
                modifier = Modifier.padding(top = 0.dp, bottom = 4.dp)
            )
        }
        if (books.isEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                shape = RoundedCornerShape(22.dp),
                color = AppTheme.colors.surface,
                border = null
            ) {
                Box(
                    modifier = Modifier.padding(32.dp),
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
            // Single big island for all books separated by background color divider
            AppCardGroup {
                books.forEachIndexed { index, book ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = AppTheme.colors.background,
                            thickness = 1.dp
                        )
                    }
                    val serialNo = LibraryCatalogStore.books.indexOfFirst { it.id == book.id }
                        .let { if (it >= 0) it + 1 else index + 1 }
                    BookRow(
                        book = book,
                        serialNo = serialNo,
                        staffControls = false,
                        isReserving = viewModel.isReserving(book.id),
                        onReserve = { viewModel.reserveBook(book.id) }
                    )
                }
            }
        }

        // Space to clear floating search pill
        Spacer(modifier = Modifier.height(76.dp))
    }
}

// Staff inventory tab content with holding stats and book management
@Composable
private fun StaffInventoryTabContent(
    viewModel: LibraryViewModel,
    onEdit: (Book) -> Unit
) {
    val scrollState = rememberScrollState()
    val books = LibraryCatalogStore.books.toList()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Inventory Overview Card
        AppCardGroup {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                InventoryStatItem(label = "Titles", value = "${viewModel.catalogueSize}")
                InventoryStatItem(label = "On Shelf", value = "${viewModel.availableCount}")
                InventoryStatItem(
                    label = "Borrowed",
                    value = "${viewModel.catalogueSize - viewModel.availableCount}"
                )
            }
        }

        if (books.isEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                shape = RoundedCornerShape(22.dp),
                color = AppTheme.colors.surface,
                border = null
            ) {
                Box(
                    modifier = Modifier.padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Catalogue is empty. Use '+ Add Book' below to start intake.",
                        color = AppTheme.colors.textMuted,
                        fontSize = 13.5.sp
                    )
                }
            }
        } else {
            // Single big island for all inventory books
            AppCardGroup {
                books.forEachIndexed { index, book ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = AppTheme.colors.background,
                            thickness = 1.dp
                        )
                    }
                    val serialNo = index + 1
                    BookRow(
                        book = book,
                        serialNo = serialNo,
                        staffControls = true,
                        isReserving = viewModel.isReserving(book.id),
                        onReserve = { viewModel.reserveBook(book.id) },
                        onEdit = onEdit,
                        onDelete = { viewModel.removeBook(book.id) }
                    )
                }
            }
        }

        // Space to clear floating Add Book button
        Spacer(modifier = Modifier.height(76.dp))
    }
}

@Composable
private fun InventoryStatItem(label: String, value: String) {
    Column {
        Text(
            text = value,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = AppTheme.colors.textPrimary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = AppTheme.colors.textSecondary
        )
    }
}

// Developer maintenance tab content for seeding sample data and diagnostics
@Composable
private fun DevMaintenanceTabContent(viewModel: LibraryViewModel) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                    color = AppTheme.colors.surface,
                    border = null
                ) {
                    Text(
                        text = "DEV · CATALOGUE MAINTENANCE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.textSecondary,
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

// Single book row displaying serial number, details, and action controls
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookRow(
    book: Book,
    @Suppress("UNUSED_PARAMETER") serialNo: Int = 0,
    staffControls: Boolean = false,
    isReserving: Boolean = false,
    onReserve: () -> Unit,
    onEdit: (Book) -> Unit = {},
    onDelete: (Book) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .animateContentSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top Row: Available copies badge on left, Title & Author in middle, Chevron on right
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Book copies badge: purple if available, orange if 0 copies
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = if (book.availableCopies > 0) AppTheme.colors.primaryPurple else AppTheme.colors.accentOrange,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${book.availableCopies}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            // Title & Author (fills remaining space)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = book.title,
                    fontSize = 15.5.sp,
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

            // Expand/collapse chevron indicator
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse details" else "Expand details",
                tint = AppTheme.colors.textSecondary,
                modifier = Modifier.size(20.dp)
            )
        }

        // Expanded Details Section ("collapse box")
        if (expanded) {
            HorizontalDivider(color = AppTheme.colors.background, thickness = 1.dp)

            // Metadata Section: Category, Shelf, ISBN, Edition (responsive FlowRow)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Category Pill (subtle neutral surface style)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = AppTheme.colors.background,
                        border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder)
                    ) {
                        Text(
                            text = book.category,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.textPrimary,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }

                    // Shelf Location Pill
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = AppTheme.colors.background,
                        border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder)
                    ) {
                        Text(
                            text = "Shelf: ${book.shelfLocation}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppTheme.colors.textPrimary,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }

                    // ISBN Pill
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = AppTheme.colors.background,
                        border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder)
                    ) {
                        Text(
                            text = "ISBN: ${book.isbn}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = AppTheme.colors.textPrimary,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }

                    // Edition Pill (if present)
                    book.edition?.let { ed ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AppTheme.colors.background,
                            border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder)
                        ) {
                            Text(
                                text = "Edition: $ed",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = AppTheme.colors.textSecondary,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
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
                        color = AppTheme.colors.textPrimary
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
                    Text(
                        text = "Table of Contents",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.textPrimary
                    )

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = AppTheme.colors.background,
                        border = null,
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
                                        color = AppTheme.colors.textSecondary
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
                            border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = null,
                                tint = AppTheme.colors.textPrimary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Edit",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppTheme.colors.textPrimary
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
                        color = AppTheme.colors.accentOrange
                    )
                }
            }
        }
    }
}

// Dialog for adding or editing library book details and scanning barcodes
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
    var shelfText by remember { mutableStateOf(initial?.shelfLocation ?: "") }
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
                text = if (isEdit) "Edit Book Details" else "Add Book",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = AppTheme.colors.textPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val requiredBorderColor = AppTheme.colors.primaryPurple.copy(alpha = 0.8f)

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
                            text = "Scan Barcode",
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

                // ISBN with Auto-Fill button (placed above title for fast intake)
                AppTextField(
                    value = isbn,
                    onValueChange = { isbn = it },
                    placeholder = { Text("ISBN *") },
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
                    colors = appTextFieldColors(unfocusedBorderColor = requiredBorderColor),
                    modifier = Modifier.fillMaxWidth()
                )

                // Book Title
                AppTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text("Book Title *") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = appTextFieldColors(unfocusedBorderColor = requiredBorderColor),
                    modifier = Modifier.fillMaxWidth()
                )

                // Author
                AppTextField(
                    value = author,
                    onValueChange = { author = it },
                    placeholder = { Text("Author *") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = appTextFieldColors(unfocusedBorderColor = requiredBorderColor),
                    modifier = Modifier.fillMaxWidth()
                )

                // Category
                AppTextField(
                    value = category,
                    onValueChange = { category = it },
                    placeholder = { Text("Category *") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = appTextFieldColors(unfocusedBorderColor = requiredBorderColor),
                    modifier = Modifier.fillMaxWidth()
                )

                // Copies (placed above edition)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTextField(
                        value = copiesText,
                        onValueChange = { copiesText = it.filter { c -> c.isDigit() } },
                        placeholder = { Text(if (isEdit) "Total Copies *" else "Copies *") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = appTextFieldColors(unfocusedBorderColor = requiredBorderColor),
                        modifier = Modifier.weight(1f)
                    )
                    if (isEdit) {
                        AppTextField(
                            value = availableText,
                            onValueChange = { availableText = it.filter { c -> c.isDigit() } },
                            placeholder = { Text("Available *") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = appTextFieldColors(unfocusedBorderColor = requiredBorderColor),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Edition
                AppTextField(
                    value = edition,
                    onValueChange = { edition = it },
                    placeholder = { Text("Edition") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = appTextFieldColors(unfocusedBorderColor = AppTheme.colors.surfaceBorder),
                    modifier = Modifier.fillMaxWidth()
                )

                // Index / Shelf Location
                AppTextField(
                    value = shelfText,
                    onValueChange = { shelfText = it },
                    placeholder = { Text("Shelf Location") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = appTextFieldColors(unfocusedBorderColor = AppTheme.colors.surfaceBorder),
                    modifier = Modifier.fillMaxWidth()
                )

                // Overview
                AppTextField(
                    value = overview,
                    onValueChange = { overview = it },
                    placeholder = { Text("Overview") },
                    singleLine = false,
                    shape = RoundedCornerShape(12.dp),
                    colors = appTextFieldColors(unfocusedBorderColor = AppTheme.colors.surfaceBorder),
                    modifier = Modifier.fillMaxWidth()
                )

                // Table of Contents
                AppTextField(
                    value = tableOfContents,
                    onValueChange = { tableOfContents = it },
                    placeholder = { Text("Table of Contents") },
                    singleLine = false,
                    shape = RoundedCornerShape(12.dp),
                    colors = appTextFieldColors(unfocusedBorderColor = AppTheme.colors.surfaceBorder),
                    modifier = Modifier.fillMaxWidth()
                )
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
                    text = if (isEdit) "Save" else "Add Book",
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

