package com.college.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.college.core.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// Centered header area displaying page title and optional subtitle
@Composable
fun AppHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 22.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = AppTheme.colors.textPrimary,
            letterSpacing = (-0.6).sp
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = AppTheme.colors.textSecondary
            )
        }
    }
}

// User account card with title on left and avatar initial badge on right
@Composable
fun AppProfileCard(
    name: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    initial: String? = null,
    avatarBg: Color = AppTheme.colors.accentOrange,
    avatarIcon: ImageVector? = null
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = AppTheme.colors.surface,
        border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontSize = 17.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.textPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = AppTheme.colors.textSecondary
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Circular avatar container
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(avatarBg, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (!initial.isNullOrBlank()) {
                    Text(
                        text = initial.take(1).uppercase(),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(
                        imageVector = avatarIcon ?: Icons.Default.Person,
                        contentDescription = name,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

// Rounded container for grouping menu rows
@Composable
fun AppCardGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = AppTheme.colors.surface,
        border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}

// Menu row item with icon badge, title, subtitle, and chevron
@Composable
fun AppCardItem(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (subtitle != null) 12.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon badge with tinted background
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(iconBg, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = iconTint,
                modifier = Modifier.size(19.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Title and optional subtitle
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.colors.textPrimary
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = AppTheme.colors.textSecondary
                )
            }
        }

        // Trailing content or arrow chevron
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = AppTheme.colors.textMuted.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// Inset divider separating row items using the main background color
@Composable
fun AppDivider(
    modifier: Modifier = Modifier
) {
    HorizontalDivider(
        color = AppTheme.colors.background,
        thickness = 1.dp,
        modifier = modifier.padding(start = 66.dp, end = 0.dp)
    )
}

// Floating search pill centered for quick actions
@Composable
fun AppSearchPill(
    placeholder: String = "Search",
    onSearchClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onSearchClick,
        shape = RoundedCornerShape(22.dp),
        color = AppTheme.colors.searchPillBg,
        border = BorderStroke(1.dp, AppTheme.colors.searchPillBorder),
        shadowElevation = if (AppTheme.colors.isDark) 0.dp else 3.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 22.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = AppTheme.colors.textSecondary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = placeholder,
                color = AppTheme.colors.textSecondary,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// Text field color styling matching active theme
@Composable
fun appTextFieldColors(
    unfocusedBorderColor: Color = AppTheme.colors.surfaceBorder,
    focusedBorderColor: Color = AppTheme.colors.primaryPurple
) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AppTheme.colors.textPrimary,
    unfocusedTextColor = AppTheme.colors.textPrimary,
    focusedContainerColor = AppTheme.colors.surface,
    unfocusedContainerColor = AppTheme.colors.surface,
    focusedBorderColor = focusedBorderColor,
    unfocusedBorderColor = unfocusedBorderColor,
    focusedLabelColor = AppTheme.colors.primaryPurple,
    unfocusedLabelColor = AppTheme.colors.textSecondary,
    cursorColor = AppTheme.colors.primaryPurple
)

// Reusable dropdown popup field for selection forms
@Composable
fun AppDropdownField(
    label: String,
    selectedValue: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Select $label",
    allowEmpty: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidthPx by remember { mutableStateOf(0) }

    val allOptions = remember(options, allowEmpty) {
        if (allowEmpty && !options.contains("")) listOf("") + options else options
    }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            anchorWidthPx = coordinates.size.width
        }
    ) {
        OutlinedTextField(
            value = selectedValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text(placeholder, color = AppTheme.colors.textSecondary) },
            colors = appTextFieldColors(),
            shape = RoundedCornerShape(12.dp),
            trailingIcon = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = "Select $label",
                        tint = AppTheme.colors.textSecondary,
                        modifier = Modifier.rotate(if (expanded) 180f else 0f)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        // Transparent overlay consuming clicks
        Surface(
            onClick = { expanded = !expanded },
            color = Color.Transparent,
            modifier = Modifier.matchParentSize()
        ) {}

        if (expanded) {
            val density = LocalDensity.current
            val positionProvider = remember(density) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize
                    ): IntOffset {
                        val offsetDown = with(density) { 4.dp.roundToPx() }
                        return IntOffset(
                            x = anchorBounds.left,
                            y = anchorBounds.bottom + offsetDown
                        )
                    }
                }
            }

            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                val anchorWidthDp = with(density) { anchorWidthPx.toDp() }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AppTheme.colors.surface,
                    shadowElevation = if (AppTheme.colors.isDark) 0.dp else 6.dp,
                    border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder),
                    modifier = Modifier.width(if (anchorWidthDp > 0.dp) anchorWidthDp else 280.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp)
                    ) {
                        allOptions.forEach { option ->
                            val isSelected = option == selectedValue
                            Surface(
                                onClick = {
                                    onSelect(option)
                                    expanded = false
                                },
                                color = if (isSelected) AppTheme.colors.primaryPurpleBg else Color.Transparent,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (option.isEmpty()) "— None (Empty) —" else option,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (option.isEmpty()) {
                                            AppTheme.colors.textSecondary
                                        } else if (isSelected) {
                                            AppTheme.colors.primaryPurple
                                        } else {
                                            AppTheme.colors.textPrimary
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Text input field with smooth edge auto-scrolling when dragging cursor
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current.copy(
        color = AppTheme.colors.textPrimary,
        fontSize = 15.5.sp
    ),
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    shape: Shape = RoundedCornerShape(12.dp),
    colors: TextFieldColors = appTextFieldColors()
) {
    // Track cursor selection state
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }

    // Synchronize selection when incoming text changes
    if (textFieldValue.text != value) {
        val newEnd = textFieldValue.selection.end.coerceIn(0, value.length)
        val newStart = textFieldValue.selection.start.coerceIn(0, value.length)
        textFieldValue = textFieldValue.copy(
            text = value,
            selection = TextRange(newStart, newEnd)
        )
    }

    val scrollState = rememberScrollState()
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val edgeThresholdPx = with(density) { 36.dp.toPx() }

    // Auto-scroll when dragging cursor or typing near edges
    LaunchedEffect(textFieldValue.selection, textFieldValue.text, viewportWidthPx, textLayoutResult) {
        val layout = textLayoutResult ?: return@LaunchedEffect
        if (viewportWidthPx <= 0) return@LaunchedEffect

        // Guard against stale text layout result when typing fast
        val transformed = visualTransformation.filter(AnnotatedString(textFieldValue.text))
        val layoutLength = layout.layoutInput.text.length
        if (layoutLength != transformed.text.length) {
            return@LaunchedEffect
        }

        val rawCursor = textFieldValue.selection.start.coerceIn(0, textFieldValue.text.length)
        val transformedCursor = try {
            transformed.offsetMapping.originalToTransformed(rawCursor).coerceIn(0, layoutLength)
        } catch (_: Throwable) {
            rawCursor.coerceIn(0, layoutLength)
        }

        while (isActive) {
            val cursorRect = try {
                layout.getCursorRect(transformedCursor)
            } catch (_: Throwable) {
                break
            }
            val cursorX = cursorRect.left
            val viewStart = scrollState.value.toFloat()
            val viewEnd = viewStart + viewportWidthPx

            if (cursorX > viewEnd - edgeThresholdPx && scrollState.canScrollForward) {
                val distance = (cursorX - (viewEnd - edgeThresholdPx)).coerceAtLeast(1f)
                val step = (distance / edgeThresholdPx * 18f).coerceIn(6f, 28f)
                scrollState.scrollBy(step)
                delay(16L)
            } else if (cursorX < viewStart + edgeThresholdPx && scrollState.canScrollBackward) {
                val distance = ((viewStart + edgeThresholdPx) - cursorX).coerceAtLeast(1f)
                val step = (distance / edgeThresholdPx * 18f).coerceIn(6f, 28f)
                scrollState.scrollBy(-step)
                delay(16L)
            } else {
                break
            }
        }
    }

    // Input component wrapped in horizontal scroller
    BasicTextField(
        value = textFieldValue,
        onValueChange = { newValue ->
            val sanitized = if (singleLine) {
                newValue.copy(text = newValue.text.replace("\n", "").replace("\r", ""))
            } else {
                newValue
            }
            textFieldValue = sanitized
            if (sanitized.text != value) {
                onValueChange(sanitized.text)
            }
        },
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = false,
        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
        visualTransformation = visualTransformation,
        onTextLayout = { textLayoutResult = it },
        interactionSource = interactionSource,
        cursorBrush = SolidColor(AppTheme.colors.primaryPurple),
        decorationBox = @Composable { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = textFieldValue.text,
                innerTextField = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coordinates ->
                                viewportWidthPx = coordinates.size.width
                            }
                            .horizontalScroll(scrollState)
                    ) {
                        innerTextField()
                    }
                },
                enabled = enabled,
                singleLine = singleLine,
                visualTransformation = visualTransformation,
                interactionSource = interactionSource,
                isError = isError,
                label = label,
                placeholder = placeholder,
                leadingIcon = leadingIcon,
                trailingIcon = trailingIcon,
                colors = colors,
                container = {
                    OutlinedTextFieldDefaults.ContainerBox(
                        enabled = enabled,
                        isError = isError,
                        interactionSource = interactionSource,
                        colors = colors,
                        shape = shape
                    )
                }
            )
        }
    )
}

// Subtle vertical scrollbar indicator drawn on right edge when scrollable
fun Modifier.appScrollbar(
    state: ScrollState,
    color: Color = Color.Gray.copy(alpha = 0.35f),
    width: Dp = 4.dp
): Modifier = drawWithContent {
    drawContent()
    if (state.maxValue > 0) {
        val totalHeight = size.height
        val barHeight = (totalHeight * (totalHeight / (state.maxValue + totalHeight))).coerceAtLeast(32.dp.toPx())
        val barOffset = (state.value.toFloat() / state.maxValue) * (totalHeight - barHeight)
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - width.toPx() - 2.dp.toPx(), barOffset),
            size = Size(width.toPx(), barHeight),
            cornerRadius = CornerRadius(width.toPx() / 2, width.toPx() / 2)
        )
    }
}
