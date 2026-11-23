package app.turtleplayer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.rememberHover
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellPlatform
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.menu.trackPressPosition
import com.metrolist.shared.ui.settings.SettingsControl
import com.metrolist.shared.ui.settings.SettingsGroupRegistration
import com.metrolist.shared.ui.settings.SettingsItemRegistration
import com.metrolist.shared.ui.settings.SettingsPageId
import com.metrolist.shared.ui.settings.SettingsPageRegistration
import com.metrolist.shared.ui.settings.SharedSettingsHost
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/*
 * One settings page drawn by Shell from upstream's registry: numbered groups, rows with hairlines and
 * every control type (switch, main switch, choice, slider, text, navigation, action) plus upstream's
 * custom items passed through in a card. Behaviour follows upstream's SettingsPageList: a main switch
 * that is off hides the other groups and shows its description, disabled groups disable their items,
 * a search hit scrolls into view and flashes, and dialogs re-read the latest item before acting.
 */

private sealed class SettingsEntry(val key: String, val type: String) {
    class Header(val group: SettingsGroupRegistration, val number: Int, val first: Boolean) : SettingsEntry("h:${group.id}", "header")

    class Item(val group: SettingsGroupRegistration, val item: SettingsItemRegistration, val first: Boolean) :
        SettingsEntry("${group.id}:${item.id}", item.control.name)

    class Footer(val group: SettingsGroupRegistration) : SettingsEntry("f:${group.id}", "footer")

    class Note(val text: String) : SettingsEntry("main-switch-note", "note")
}

/**
 * Upstream's Lyrics page pins its live preview. Turtle's Lyrics page keeps only the text size and spacing
 * under another group id, so it shows no preview of upstream's renderer.
 */
internal fun SettingsPageRegistration.pinsLyricsPreview(): Boolean = id == SettingsPageId.LYRICS && groups.any { it.id == "display" }

private fun settingsEntries(groups: List<SettingsGroupRegistration>, mainSwitch: SettingsItemRegistration?): List<SettingsEntry> {
    val mainOff = mainSwitch?.checked == false
    val entries = mutableListOf<SettingsEntry>()
    var number = 0
    groups.forEachIndexed { groupIndex, group ->
        if (mainOff && groupIndex > 0) return@forEachIndexed
        if (group.title.isNotBlank() || !group.description.isNullOrBlank()) {
            entries += SettingsEntry.Header(group, number, first = entries.isEmpty())
            if (group.title.isNotBlank()) number++
        }
        group.items.forEachIndexed { index, item ->
            entries += SettingsEntry.Item(group, if (group.enabled) item else item.copy(enabled = false), first = index == 0)
        }
        group.footer?.takeIf { it.isNotBlank() }?.let { entries += SettingsEntry.Footer(group) }
        if (groupIndex == 0 && mainSwitch != null && mainOff) {
            mainSwitch.description?.takeIf { it.isNotBlank() }?.let { entries += SettingsEntry.Note(it) }
        }
    }
    return entries
}

/**
 * A page's rows in a LazyColumn. [header] adds at most one item before the rows (the large title), so a
 * highlighted search hit scrolls to the right place.
 */
@Composable
internal fun ShellSettingsPageList(
    page: SettingsPageRegistration,
    listState: LazyListState,
    host: SharedSettingsHost,
    highlightedItemId: String?,
    onNavigate: (SettingsPageId) -> Unit,
    onSettingValueChanged: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    header: (LazyListScope.() -> Unit)? = null,
) {
    val c = Shell.colors
    var dialogItem by remember { mutableStateOf<SettingsItemRegistration?>(null) }
    val groups = page.groups.filter { it.items.isNotEmpty() }
    val mainSwitch = groups.firstOrNull()?.items?.singleOrNull()?.takeIf { it.control == SettingsControl.MAIN_SWITCH }
    val mainOff = mainSwitch?.checked == false
    val entries = settingsEntries(groups, mainSwitch)
    val headerCount = if (header != null) 1 else 0
    val highlightIndex = entries.indexOfFirst { it is SettingsEntry.Item && it.item.id == highlightedItemId }
    LaunchedEffect(page.id, highlightedItemId, highlightIndex) {
        if (highlightIndex >= 0) listState.animateScrollToItem((highlightIndex + headerCount - 1).coerceAtLeast(0))
    }
    val hasPreview = page.pinsLyricsPreview()
    val pinned = hasPreview && lyricsPreviewPinned()
    val list: @Composable (Modifier, Dp) -> Unit = { listModifier, previewHeight ->
        LazyColumn(state = listState, modifier = listModifier, contentPadding = contentPadding) {
            header?.invoke(this)
            items(entries, key = { it.key }, contentType = { it.type }) { entry ->
                Box(Modifier.animateItem()) {
                    when (entry) {
                        is SettingsEntry.Header -> {
                            ShellSettingsSectionLabel(entry.number, entry.group.title, entry.first, entry.group.description, entry.group.enabled)
                        }

                        is SettingsEntry.Item -> {
                            ShellSettingsItemRow(
                                item = entry.item,
                                first = entry.first,
                                highlighted = entry.item.id == highlightedItemId,
                                onSettingValueChanged = onSettingValueChanged,
                                onNavigate = onNavigate,
                                onOpenDialog = { dialogItem = it },
                            )
                        }

                        is SettingsEntry.Footer -> {
                            Text(
                                entry.group.footer.orEmpty(),
                                style = Shell.type.bodySmall,
                                color = c.ink2,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            )
                        }

                        is SettingsEntry.Note -> {
                            Text(entry.text, style = Shell.type.bodySmall, color = c.ink2, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                        }
                    }
                }
            }
            if (hasPreview && !pinned) {
                item(key = "lyrics-preview", contentType = "preview") {
                    host.lyricsPreview(Modifier.fillMaxWidth().padding(top = 16.dp).height(previewHeight).padding(horizontal = 16.dp))
                }
            }
        }
    }
    if (hasPreview) {
        Box(modifier.fillMaxSize()) {
            ShellLyricsPreviewFrame(host, pinned) { previewHeight -> list(Modifier.fillMaxSize(), previewHeight) }
        }
    } else {
        list(modifier.fillMaxSize(), 300.dp)
    }

    val activeDialogItem =
        dialogItem?.let { dialog ->
            groups
                .firstOrNull { group -> group.enabled && group.items.any { it.id == dialog.id && it.enabled } }
                ?.items
                ?.firstOrNull { it.id == dialog.id }
                ?.takeUnless { mainOff }
        }
    LaunchedEffect(activeDialogItem == null) { if (activeDialogItem == null) dialogItem = null }
    activeDialogItem?.let { item ->
        when (item.control) {
            SettingsControl.TEXT -> ShellSettingsTextDialog(item, onDismiss = { dialogItem = null }, onSettingValueChanged = onSettingValueChanged)
            SettingsControl.ACTION -> ShellSettingsConfirmDialog(item, onDismiss = { dialogItem = null }, onSettingValueChanged = onSettingValueChanged)
            else -> {}
        }
    }
}

/** A search hit flashes lime for a moment after the page opens on it. */
@Composable
private fun rememberFlash(highlighted: Boolean): Boolean {
    var flash by remember(highlighted) { mutableStateOf(highlighted) }
    LaunchedEffect(highlighted) {
        if (highlighted) {
            delay(2200)
            flash = false
        }
    }
    return flash
}

@Composable
private fun ShellSettingsItemRow(
    item: SettingsItemRegistration,
    first: Boolean,
    highlighted: Boolean,
    onSettingValueChanged: () -> Unit,
    onNavigate: (SettingsPageId) -> Unit,
    onOpenDialog: (SettingsItemRegistration) -> Unit,
) {
    when (item.control) {
        SettingsControl.MAIN_SWITCH -> MainSwitchCard(item, onSettingValueChanged)
        SettingsControl.CUSTOM -> CustomItemCard(item, onSettingValueChanged)
        SettingsControl.SLIDER -> SliderRow(item, first, rememberFlash(highlighted), onSettingValueChanged)
        else -> StandardRow(item, first, rememberFlash(highlighted), onSettingValueChanged, onNavigate, onOpenDialog)
    }
}

/** Switch, choice, text, navigation and action rows: title and summary on the left, the control on the right. */
@Composable
private fun StandardRow(
    item: SettingsItemRegistration,
    first: Boolean,
    flash: Boolean,
    onSettingValueChanged: () -> Unit,
    onNavigate: (SettingsPageId) -> Unit,
    onOpenDialog: (SettingsItemRegistration) -> Unit,
) {
    val c = Shell.colors
    val haptics = rememberMetrolistHaptics()
    val (source, hovered) = rememberHover()
    val bg = shellRowBackground(hovered && item.enabled, highlighted = flash)
    var choiceOpen by remember(item.id) { mutableStateOf(false) }
    var anchor by remember(item.id) { mutableStateOf(IntOffset.Zero) }
    val alpha = if (item.enabled) 1f else 0.38f

    fun activate() {
        when (item.control) {
            SettingsControl.SWITCH -> {
                val next = item.checked != true
                haptics.toggle(next)
                item.onCheckedChange?.invoke(next)
                onSettingValueChanged()
            }

            SettingsControl.CHOICE -> choiceOpen = true

            SettingsControl.TEXT -> onOpenDialog(item)

            SettingsControl.NAVIGATION -> item.destination?.let(onNavigate)

            SettingsControl.ACTION -> {
                if (item.confirmationMessage == null) {
                    item.onClick?.invoke()
                    onSettingValueChanged()
                } else {
                    onOpenDialog(item)
                }
            }

            else -> {}
        }
    }

    val role =
        when (item.control) {
            SettingsControl.SWITCH -> Role.Switch
            SettingsControl.CHOICE -> Role.DropdownList
            else -> Role.Button
        }
    Column(Modifier.fillMaxWidth()) {
        if (!first) ShellRowDivider()
        Box(
            Modifier.fillMaxWidth().then(
                if (item.control == SettingsControl.CHOICE) Modifier.trackPressPosition { anchor = it.round() } else Modifier,
            ),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 60.dp).clip(RoundedCornerShape(10.dp)).background(bg)
                    .hoverable(source, enabled = item.enabled)
                    .then(
                        if (item.enabled) {
                            Modifier.shellPressable({ activate() }, role = role, focusShape = RoundedCornerShape(10.dp))
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (item.control == SettingsControl.SWITCH) {
                            Modifier.semantics { toggleableState = ToggleableState(item.checked == true) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = Shell.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.ink.copy(alpha = alpha))
                    (item.annotatedDescription ?: item.description?.let(::AnnotatedString))?.takeIf { it.isNotEmpty() }?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(it, style = Shell.type.bodySmall, color = c.ink2.copy(alpha = alpha))
                    }
                }
                Spacer(Modifier.width(16.dp))
                when (item.control) {
                    SettingsControl.SWITCH -> ShellSwitch(item.checked == true, enabled = item.enabled)

                    SettingsControl.CHOICE -> {
                        val label = item.valueLabel ?: item.choices.firstOrNull { it.id == item.selectedChoiceId }?.label
                        TrailingValue(label, accent = false, alpha = alpha)
                        Glyph(Glyphs.ChevronUpDown, c.ink2.copy(alpha = alpha), 16.dp)
                    }

                    SettingsControl.TEXT -> {
                        val value = item.textValue?.takeIf { it.isNotEmpty() }?.let { if (item.secret) "••••••" else it } ?: item.valueLabel
                        TrailingValue(value, accent = false, alpha = alpha)
                        Glyph(Glyphs.ChevronRight, c.ink2.copy(alpha = alpha), 18.dp)
                    }

                    SettingsControl.NAVIGATION -> {
                        TrailingValue(item.valueLabel, accent = false, alpha = alpha)
                        Glyph(Glyphs.ChevronRight, c.ink2.copy(alpha = alpha), 18.dp)
                    }

                    SettingsControl.ACTION -> TrailingValue(item.valueLabel?.takeIf { it != item.title }, accent = true, alpha = alpha, last = true)

                    else -> {}
                }
            }
            if (item.control == SettingsControl.CHOICE) {
                ShellChoicePopup(
                    expanded = choiceOpen,
                    anchor = anchor,
                    choices = item.choices,
                    selectedId = item.selectedChoiceId,
                    onSelect = { choice ->
                        item.onChoiceSelected?.invoke(choice.id)
                        onSettingValueChanged()
                    },
                    onDismiss = { choiceOpen = false },
                )
            }
        }
    }
}

@Composable
private fun TrailingValue(value: String?, accent: Boolean, alpha: Float, last: Boolean = false) {
    if (value.isNullOrBlank()) return
    val c = Shell.colors
    Text(
        value,
        style = Shell.type.label.copy(fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Medium),
        color = (if (accent) c.accent else c.ink2).copy(alpha = alpha),
        textAlign = TextAlign.End,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 148.dp),
    )
    if (!last) Spacer(Modifier.width(6.dp))
}

/** A slider row: title, live value in lime, Shell's slider (applied on release) and upstream's preview. */
@Composable
private fun SliderRow(item: SettingsItemRegistration, first: Boolean, flash: Boolean, onSettingValueChanged: () -> Unit) {
    val c = Shell.colors
    val range = item.sliderRange ?: return
    val initial = item.sliderValue ?: return
    var value by remember(item.id, initial) { mutableStateOf(initial) }
    val alpha = if (item.enabled) 1f else 0.38f
    val bg = shellRowBackground(hovered = false, highlighted = flash)
    val valueText = item.sliderValueFormatter?.invoke(value) ?: (if (value == initial) item.valueLabel else null) ?: value.roundToInt().toString()
    Column(Modifier.fillMaxWidth()) {
        if (!first) ShellRowDivider()
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(10.dp)).background(bg)
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = Shell.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.ink.copy(alpha = alpha))
                    (item.annotatedDescription ?: item.description?.let(::AnnotatedString))?.takeIf { it.isNotEmpty() }?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(it, style = Shell.type.bodySmall, color = c.ink2.copy(alpha = alpha))
                    }
                }
                Spacer(Modifier.width(16.dp))
                Text(valueText, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = (if (item.enabled) c.accent else c.ink2).copy(alpha = alpha))
            }
            Spacer(Modifier.height(4.dp))
            ShellSlider(
                value = value,
                range = range,
                steps = item.sliderSteps,
                enabled = item.enabled,
                label = item.title,
                onChange = { value = it },
                onCommit = {
                    item.onSliderChanged?.invoke(it)
                    onSettingValueChanged()
                },
            )
            item.sliderPreview?.let { preview ->
                Spacer(Modifier.height(8.dp))
                preview(value)
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/** An integration's master switch (Discord, Last.fm...): a large card that turns lime-dark when on. */
@Composable
private fun MainSwitchCard(item: SettingsItemRegistration, onSettingValueChanged: () -> Unit) {
    val c = Shell.colors
    val haptics = rememberMetrolistHaptics()
    val checked = item.checked == true
    val bg by animateColorAsState(if (checked) c.accentContainer else c.surface2, ShellMotion.emphasized())
    val fg by animateColorAsState(if (checked) c.accent else c.ink, ShellMotion.emphasized())
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 68.dp).clip(shape).background(bg)
            .then(
                if (item.enabled) {
                    Modifier.shellPressable({
                        haptics.toggle(!checked)
                        item.onCheckedChange?.invoke(!checked)
                        onSettingValueChanged()
                    }, role = Role.Switch, label = item.title, focusShape = shape)
                } else {
                    Modifier
                },
            )
            .semantics { toggleableState = ToggleableState(checked) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.title,
            style = Shell.type.headline.copy(fontSize = 19.sp, lineHeight = 24.sp),
            color = fg.copy(alpha = if (item.enabled) 1f else 0.38f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        ShellSwitch(checked, enabled = item.enabled)
    }
}

/** Upstream's own setting content (colour pickers, provider order, previews, device lists) in a Shell card. */
@Composable
private fun CustomItemCard(item: SettingsItemRegistration, onSettingValueChanged: () -> Unit) {
    val c = Shell.colors
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(shape)
            .background(if (ShellPlatform.desktop) c.surface2 else c.surface1).border(1.dp, c.hairlineSoft, shape)
            .graphicsLayer { alpha = if (item.enabled) 1f else 0.38f },
    ) {
        item.customContent?.invoke(onSettingValueChanged)
    }
}
