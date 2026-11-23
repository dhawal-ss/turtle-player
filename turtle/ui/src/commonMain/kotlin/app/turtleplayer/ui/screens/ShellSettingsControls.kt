package app.turtleplayer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.rememberHover
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_set_hide
import app.turtleplayer.ui.generated.resources.shell_set_selected
import app.turtleplayer.ui.generated.resources.shell_set_show
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.clear
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.done
import com.metrolist.shared.generated.resources.settings_hide_secret
import com.metrolist.shared.generated.resources.settings_search_placeholder
import com.metrolist.shared.generated.resources.settings_show_secret
import com.metrolist.shared.turtle.DialogSlot
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.settings.SettingsChoice
import com.metrolist.shared.ui.settings.SettingsItemRegistration
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import com.metrolist.shared.generated.resources.Res as SharedRes

/*
 * Shell's settings controls (turtle/HOOKS.md, settings): the lime switch, the inline slider, the
 * search field, the choice dropdown and the text and confirmation dialogs. Upstream's settings
 * registry drives them; see ShellSettings.kt.
 */

/**
 * Shell's switch: a pill track that fills lime when on and a thumb that springs across (H-Motion).
 * Visual only; the row around it owns the toggle, so the whole row is the touch target.
 */
@Composable
internal fun ShellSwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Shell.colors
    val track by animateColorAsState(if (checked) c.accent else c.surface3, ShellMotion.emphasized())
    val edge by animateColorAsState(if (checked) c.accent else c.hairline, ShellMotion.emphasized())
    val thumb by animateColorAsState(if (checked) c.onAccent else c.ink2, ShellMotion.emphasized())
    val x by animateDpAsState(if (checked) 18.dp else 0.dp, ShellMotion.spring())
    val grow by animateDpAsState(if (checked) 22.dp else 18.dp, ShellMotion.spring())
    Box(
        modifier.size(46.dp, 28.dp).graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .clip(CircleShape).background(track).border(1.dp, edge, CircleShape).padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.size(22.dp).offset(x = x), contentAlignment = Alignment.Center) {
            Box(Modifier.size(grow).shadow(if (checked) 2.dp else 0.dp, CircleShape).clip(CircleShape).background(thumb))
        }
    }
}

/**
 * Shell's slider: a thin track that fills lime, a knob that grows while dragging or hovered. [onChange]
 * follows the finger; [onCommit] runs once on release or tap, so heavy settings apply once.
 */
@Composable
internal fun ShellSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    label: String,
    onChange: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Shell.colors
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    fun snap(v: Float): Float {
        val clamped = v.coerceIn(range.start, range.endInclusive)
        if (steps <= 0) return clamped
        val step = span / (steps + 1)
        return (range.start + ((clamped - range.start) / step).roundToInt() * step).coerceIn(range.start, range.endInclusive)
    }
    val latest by rememberUpdatedState(value)
    val emit by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    val haptics = rememberMetrolistHaptics()
    var dragging by remember { mutableStateOf(false) }
    val (source, hovered) = rememberHover()
    val knob by animateDpAsState(if (dragging || hovered) 18.dp else 14.dp, ShellMotion.spring())
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
    BoxWithConstraints(
        modifier.fillMaxWidth().height(36.dp).hoverable(source)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(value, range, steps)
                if (enabled) {
                    setProgress { target ->
                        val v = snap(target)
                        emit(v)
                        commit(v)
                        true
                    }
                }
            }
            .then(
                if (enabled) {
                    Modifier
                        .pointerInput(range, steps) {
                            detectTapGestures { o ->
                                val v = snap(range.start + o.x / size.width.coerceAtLeast(1) * span)
                                haptics.perform(HapticEvent.PRESS)
                                emit(v)
                                commit(v)
                            }
                        }
                        .pointerInput(range, steps) {
                            detectHorizontalDragGestures(
                                onDragStart = { o ->
                                    dragging = true
                                    emit(snap(range.start + o.x / size.width.coerceAtLeast(1) * span))
                                },
                                onDragEnd = {
                                    dragging = false
                                    commit(latest)
                                },
                                onDragCancel = {
                                    dragging = false
                                    commit(latest)
                                },
                            ) { change, _ ->
                                change.consume()
                                val v = snap(range.start + change.position.x / size.width.coerceAtLeast(1) * span)
                                if (v != latest && steps in 1..40) haptics.perform(HapticEvent.SEGMENT_TICK)
                                emit(v)
                            }
                        }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        val fill = if (enabled) c.accent else c.ink2.copy(alpha = 0.5f)
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(c.surface3))
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(CircleShape).background(fill))
        val travel: Dp = maxWidth - knob
        Box(
            Modifier.offset(x = travel * fraction).size(knob).shadow(if (dragging) 4.dp else 1.dp, CircleShape)
                .clip(CircleShape).background(if (enabled) c.ink else c.ink2),
        )
    }
}

/** Settings search in Shell's search style: an ink field with a lime ring while you type. */
@Composable
internal fun ShellSettingsSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Shell.colors
    var focused by remember { mutableStateOf(false) }
    val ring by animateColorAsState(if (query.isNotEmpty() || focused) c.accent else c.accent.copy(alpha = 0f), ShellMotion.emphasized())
    val textStyle = Shell.type.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium)
    val focusManager = LocalFocusManager.current
    val hint = stringResource(SharedRes.string.settings_search_placeholder)
    Box(modifier.fillMaxWidth().border(2.dp, ring, RoundedCornerShape(10.dp)).padding(2.dp)) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp)).background(c.ink)
                .padding(start = 12.dp, end = if (query.isEmpty()) 12.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(Glyphs.Search, c.ground, 20.dp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(hint, style = textStyle, color = c.ground.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = textStyle.copy(color = c.ground),
                    cursorBrush = SolidColor(c.ground),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.semantics { contentDescription = hint },
                )
            }
            if (query.isNotEmpty()) {
                GlyphButton(Glyphs.Close, stringResource(SharedRes.string.clear), { onQueryChange("") }, c.ground, size = 44.dp, iconSize = 14.dp)
            }
        }
    }
}

/** The top bar of a settings surface: back chevron, a title that fades in once the big title scrolls away, close. */
@Composable
internal fun ShellSettingsTopBar(
    title: String,
    titleVisible: Boolean,
    onBack: (() -> Unit)?,
    onClose: (() -> Unit)?,
    insetStatusBar: Boolean = true,
) {
    val c = Shell.colors
    val shown by animateFloatAsState(if (titleVisible) 1f else 0f, ShellMotion.emphasized())
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().then(if (insetStatusBar) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
                .height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                GlyphButton(Glyphs.ArrowLeft, stringResource(SharedRes.string.back), onBack, c.ink, iconSize = 22.dp)
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Text(
                title,
                style = Shell.type.headline.copy(fontSize = 18.sp, lineHeight = 22.sp),
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp).graphicsLayer {
                    alpha = shown
                    translationY = (1f - shown) * 8.dp.toPx()
                },
            )
            if (onClose != null) GlyphButton(Glyphs.Close, stringResource(SharedRes.string.close), onClose, c.ink, iconSize = 16.dp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).graphicsLayer { alpha = shown }.background(c.hairlineSoft))
    }
}

/** Large Space Grotesk page title, Spotify style. */
@Composable
internal fun ShellSettingsLargeTitle(title: String, subtitle: String? = null) {
    val c = Shell.colors
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp)) {
        Text(title, style = Shell.type.display, color = c.ink)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(subtitle, style = Shell.type.bodySmall, color = c.ink2)
        }
    }
}

/**
 * The choice dropdown for a setting: Shell's floating panel at the press position, the current choice
 * in lime with a check, upstream's previews (theme, slider style) beside each option. Long lists
 * (languages, countries) scroll lazily and open on the current choice.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BoxScope.ShellChoicePopup(
    expanded: Boolean,
    anchor: IntOffset,
    choices: List<SettingsChoice>,
    selectedId: String?,
    onSelect: (SettingsChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier.matchParentSize().layout { measurable, constraints ->
            val placeable = measurable.measure(Constraints())
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(anchor.x, anchor.y) }
        },
    ) {
        DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismiss) {
            val c = Shell.colors
            val selectedIndex = choices.indexOfFirst { it.id == selectedId }
            // A plain scrolling Column, not a LazyColumn: Material's popup measures its content's
            // intrinsic size, which lazy lists refuse (it crashed the app on desktop).
            val scroll = androidx.compose.foundation.rememberScrollState()
            val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.roundToPx() }
            androidx.compose.runtime.LaunchedEffect(Unit) { scroll.scrollTo((selectedIndex - 3).coerceAtLeast(0) * rowPx) }
            val haptics = rememberMetrolistHaptics()
            val selectedLabel = stringResource(Res.string.shell_set_selected)
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.width(300.dp).heightIn(max = 420.dp).shadow(16.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp)).background(c.surface2).border(1.dp, c.hairline, RoundedCornerShape(12.dp))
                    .then(Modifier.verticalScroll(scroll)).padding(4.dp),
            ) {
                choices.forEachIndexed { index, choice -> androidx.compose.runtime.key("$index:${choice.id}") {
                    val selected = choice.id == selectedId
                    val (hover, hovered) = rememberHover()
                    val bg by animateColorAsState(
                        when {
                            selected -> c.accentSoft
                            hovered -> c.ink.copy(alpha = 0.06f)
                            else -> Color.Transparent
                        },
                        ShellMotion.emphasized(),
                    )
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).background(bg).hoverable(hover)
                            .shellPressable({
                                haptics.perform(HapticEvent.PRESS)
                                onSelect(choice)
                                onDismiss()
                            }, label = choice.label)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        choice.preview?.let { preview ->
                            Box(contentAlignment = Alignment.Center) { preview() }
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            choice.label,
                            style = Shell.type.body.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                            color = if (selected) c.accent else c.ink,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) Glyph(Glyphs.Check, c.accent, 16.dp, contentDescription = selectedLabel)
                    }
                } }
            }
        }
    }
}

/** A dialog action: a lime pill for the primary action, plain ink text otherwise. */
@Composable
internal fun ShellDialogButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    val c = Shell.colors
    Box(
        Modifier.heightIn(min = 40.dp).clip(CircleShape).background(if (primary) c.accent else Color.Transparent)
            .shellPressable(onClick, focusShape = CircleShape).padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = if (primary) c.onAccent else c.ink)
    }
}

/** Edit a text setting (API keys, URLs, prompts) in Shell's dialog card. Secret values can be revealed. */
@Composable
internal fun ShellSettingsTextDialog(item: SettingsItemRegistration, onDismiss: () -> Unit, onSettingValueChanged: () -> Unit) {
    var text by remember(item.id, item.textValue) { mutableStateOf(item.textValue.orEmpty()) }
    var revealed by remember(item.id) { mutableStateOf(false) }
    val done = stringResource(SharedRes.string.done)
    val cancel = stringResource(SharedRes.string.cancel)
    val save = {
        item.onTextChanged?.invoke(text)
        onSettingValueChanged()
        onDismiss()
    }
    ShellDialog(
        DialogSlot(
            onDismissRequest = onDismiss,
            confirmButton = { ShellDialogButton(done, primary = true, onClick = save) },
            dismissButton = { ShellDialogButton(cancel, onClick = onDismiss) },
            icon = null,
            title = { Text(item.title) },
            text = {
                val c = Shell.colors
                val style = Shell.type.body.copy(color = c.ink)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface3)
                        .border(1.dp, c.hairline, RoundedCornerShape(10.dp)).padding(start = 14.dp, end = 6.dp),
                    verticalAlignment = if (item.multiline) Alignment.Top else Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).padding(vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) item.textPlaceholder?.let { Text(it, style = style, color = c.ink2) }
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = !item.multiline,
                            minLines = if (item.multiline) 4 else 1,
                            maxLines = if (item.multiline) 12 else 1,
                            textStyle = style,
                            cursorBrush = SolidColor(c.accent),
                            visualTransformation = if (item.secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(imeAction = if (item.multiline) ImeAction.Default else ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { save() }),
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = item.title },
                        )
                    }
                    if (item.secret) {
                        val label = stringResource(if (revealed) SharedRes.string.settings_hide_secret else SharedRes.string.settings_show_secret)
                        Text(
                            stringResource(if (revealed) Res.string.shell_set_hide else Res.string.shell_set_show),
                            style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold),
                            color = c.accent,
                            modifier = Modifier.padding(vertical = 8.dp).clip(RoundedCornerShape(6.dp))
                                .shellPressable({ revealed = !revealed }, label = label).padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            },
            properties = DialogProperties(),
        ),
    )
}

/** Confirm a destructive setting action (clear history, reset) in Shell's dialog card. */
@Composable
internal fun ShellSettingsConfirmDialog(item: SettingsItemRegistration, onDismiss: () -> Unit, onSettingValueChanged: () -> Unit) {
    val confirm = item.valueLabel ?: stringResource(SharedRes.string.clear)
    val cancel = stringResource(SharedRes.string.cancel)
    val message = item.confirmationMessage?.takeIf { it.isNotBlank() }
    val body: (@Composable () -> Unit)? = message?.let { m -> @Composable { Text(m) } }
    ShellDialog(
        DialogSlot(
            onDismissRequest = onDismiss,
            confirmButton = {
                ShellDialogButton(confirm, primary = true) {
                    item.onClick?.invoke()
                    onSettingValueChanged()
                    onDismiss()
                }
            },
            dismissButton = { ShellDialogButton(cancel, onClick = onDismiss) },
            icon = null,
            title = { Text(item.title) },
            text = body,
            properties = DialogProperties(),
        ),
    )
}

/** Width cap for settings content on wide screens, so rows stay readable. */
internal val ShellSettingsMaxWidth: Dp = 760.dp

/** The rounded icon tile beside a settings page (Tesla-style): neutral, lime when selected. */
@Composable
internal fun ShellSettingsIconTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    customIcon: (@Composable (Color) -> Unit)?,
    selected: Boolean = false,
    size: Dp = 40.dp,
) {
    val c = Shell.colors
    val bg by animateColorAsState(if (selected) c.accent else c.surface2, ShellMotion.emphasized())
    val tint by animateColorAsState(if (selected) c.onAccent else c.ink, ShellMotion.emphasized())
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(bg), contentAlignment = Alignment.Center) {
        if (customIcon != null) {
            Box(Modifier.size(size * 0.55f), contentAlignment = Alignment.Center) { customIcon(tint) }
        } else {
            Glyph(icon, tint, size * 0.5f)
        }
    }
}

/** A short "E" tag for explicit content. */
@Composable
internal fun ShellExplicitTag(label: String, description: String) {
    val c = Shell.colors
    Box(
        Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(c.ink2.copy(alpha = 0.25f)).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Shell.type.index.copy(fontSize = 9.sp, lineHeight = 10.sp), color = c.ink)
    }
}
