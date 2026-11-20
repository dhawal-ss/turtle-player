package app.turtleplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellShapes
import coil3.compose.AsyncImage

/** Press feedback shared by every tappable Shell element: scale to 0.96 over the tap duration. */
@Composable
fun Modifier.shellPressable(
    onClick: () -> Unit,
    role: Role = Role.Button,
    label: String? = null,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(ShellMotion.TAP_MS, easing = ShellMotion.Emphasized))
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, role = role, onClick = onClick)
        .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
}

/** Artwork with a tonal placeholder while it loads. */
@Composable
fun ShellArt(url: String?, modifier: Modifier = Modifier, shape: Shape = ShellShapes.Art, fallback: Color = Shell.colors.surface3) {
    Box(modifier.clip(shape).background(fallback)) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun ShellChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Shell.colors
    val bg by animateColorAsState(if (selected) c.accent else c.surface2, ShellMotion.emphasized())
    val fg by animateColorAsState(if (selected) c.onAccent else c.ink, ShellMotion.emphasized())
    Box(
        modifier.height(32.dp).clip(CircleShape).background(bg).shellPressable(onClick, Role.Tab).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Shell.type.label, color = fg) }
}

@Composable
fun IndexLabel(text: String, modifier: Modifier = Modifier, color: Color = Shell.colors.index) {
    Text(text.uppercase(), style = Shell.type.index, color = color, modifier = modifier)
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = Shell.type.sectionTitle, color = Shell.colors.ink, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(action, style = Shell.type.label, color = Shell.colors.ink2, modifier = Modifier.shellPressable(onAction).padding(4.dp))
        }
    }
}

/** Three bars bouncing out of phase: the "this is playing" mark. */
@Composable
fun Equalizer(color: Color = Shell.colors.accent, height: Dp = 14.dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition()
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        repeat(3) { i ->
            val s by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(400, delayMillis = i * 130, easing = ShellMotion.Emphasized), RepeatMode.Reverse))
            Box(Modifier.width(3.dp).height(height).graphicsLayer { scaleY = s; transformOrigin = TransformOrigin(0.5f, 1f) }.background(color))
        }
    }
}

/** Spotify-style shortcut tile: art on the left, bold name, equalizer when it is what's playing. */
@Composable
fun ShortcutTile(
    title: String,
    art: @Composable (Modifier) -> Unit,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.height(56.dp).clip(ShellShapes.Tile).background(Shell.colors.surface2).shellPressable(onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        art(Modifier.size(56.dp))
        Text(title, style = Shell.type.title, color = Shell.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        AnimatedVisibility(playing, enter = fadeIn(ShellMotion.emphasized()), exit = fadeOut(ShellMotion.exit())) {
            Equalizer(modifier = Modifier.padding(end = 10.dp))
        }
    }
}

/** Square mix card with the name set on the artwork and a "who's in it" line below. */
@Composable
fun MixCard(title: String, subtitle: String, artUrl: String?, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.shellPressable(onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(ShellShapes.Art).background(tint)) {
            if (artUrl != null) {
                AsyncImage(model = artUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f })
            }
            Text(title, style = Shell.type.headline.copy(fontSize = Shell.type.headline.fontSize * 0.68f), color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
        }
        Text(subtitle, style = Shell.type.bodySmall, color = Shell.colors.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Play/pause that morphs: the outgoing glyph rotates and shrinks away as the new one arrives. */
@Composable
fun PlayPauseButton(playing: Boolean, onClick: () -> Unit, size: Dp, background: Color, contentColor: Color, modifier: Modifier = Modifier, playLabel: String, pauseLabel: String) {
    Box(
        modifier.size(size).clip(CircleShape).background(background).shellPressable(onClick, label = if (playing) pauseLabel else playLabel),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(playing, animationSpec = tween(ShellMotion.EMPHASIZED_MS, easing = ShellMotion.Emphasized)) { isPlaying ->
            if (isPlaying) PauseGlyph(contentColor, size * 0.42f) else PlayGlyph(contentColor, size * 0.42f)
        }
    }
}

/** The Infinity Mix's cover: a 3×3 cube of the mix's artwork that springs in tile by tile. */
@Composable
fun InfinityCube(artUrls: List<String?>, playing: Boolean, modifier: Modifier = Modifier) {
    val fallbacks = listOf(0xFFC4577A, 0xFF3E6FD8, 0xFFE8B04B, 0xFF2E9E7A, 0xFFD4F55A, 0xFF8A4FD1, 0xFFD9704A, 0xFF2E7E9E, 0xFFF2F2F2).map { Color(it) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (col in 0 until 3) {
                    val i = row * 3 + col
                    val s by animateFloatAsState(if (playing) 1f else 0.88f, ShellMotion.spring(i * ShellMotion.STAGGER_MS))
                    ShellArt(artUrls.getOrNull(i), Modifier.weight(1f).aspectRatio(1f).scale(s), shape = RoundedCornerShape(0.dp), fallback = fallbacks[i])
                }
            }
        }
    }
}

/** Snackbar-style confirmation that springs up from the bottom. */
@Composable
fun ShellToast(message: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        message != null,
        enter = slideInVertically(ShellMotion.spring()) { it * 2 } + fadeIn(ShellMotion.emphasized()),
        exit = slideOutVertically(ShellMotion.exit()) { it * 2 } + fadeOut(ShellMotion.exit()),
        modifier = modifier,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(10.dp)).background(Shell.colors.ink).padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(Shell.colors.accent), contentAlignment = Alignment.Center) { CheckGlyph(Shell.colors.onAccent, 14.dp) }
            Spacer(Modifier.width(12.dp))
            Text(message.orEmpty(), style = Shell.type.label, color = Shell.colors.ground)
        }
    }
}
