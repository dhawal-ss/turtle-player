package app.turtleplayer.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_credits_zoom_hint
import app.turtleplayer.ui.generated.resources.shell_np_close
import app.turtleplayer.ui.theme.Shell
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** One person in the credits heart; the creators also carry their [role]. */
internal data class HeartCredit(val name: String, val role: String? = null)

/**
 * A fat heart drawn only with the names in [credits], each written once and never split. The names
 * are set at one size, the largest at which everyone fits, and each row is spread from edge to edge
 * of the heart, so the names themselves draw its outline.
 */
@Composable
internal fun CreditsHeart(credits: List<HeartCredit>, modifier: Modifier = Modifier, maxSide: Dp = MAX_SIDE) {
    val c = Shell.colors
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // The shortest entry goes last: it lands in the narrow rows at the point.
    val unique = remember(credits) {
        val distinct = credits.distinctBy { it.name.lowercase() }
        val shortest = distinct.minByOrNull { it.name.length + (it.role?.length ?: 0) }
        distinct.filter { it != shortest } + listOfNotNull(shortest)
    }
    val description = remember(unique) { unique.joinToString(", ") { credit -> listOfNotNull(credit.name, credit.role).joinToString(", ") } }
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 12.dp).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        // Pixels straight from the layout: a dialog's window can report another density than the app's.
        val sidePx = min(constraints.maxWidth.toFloat(), with(density) { maxSide.toPx() })
        val gapPx = with(density) { GAP_DP.dp.toPx() }
        val base = Shell.type.bodySmall
        // Colour and weight only: the size comes from the search below.
        val nameStyle = SpanStyle(color = c.ink, fontWeight = FontWeight.Medium)
        val creatorStyle = SpanStyle(color = c.ink, fontWeight = FontWeight.Bold)
        val roleStyle = SpanStyle(color = c.ink2, fontWeight = FontWeight.Normal)
        val placed = remember(unique, sidePx, gapPx, base, nameStyle, creatorStyle, roleStyle) {
            val area = FatHeart.Area(sidePx)
            val entries = unique.map { credit ->
                buildAnnotatedString {
                    withStyle(if (credit.role != null) creatorStyle else nameStyle) { append(credit.name) }
                    credit.role?.let { role ->
                        append(' ')
                        withStyle(roleStyle) { append(role) }
                    }
                }
            }
            fun measure(sizeSp: Float) = entries.map { text ->
                measurer.measure(text, base.copy(fontSize = sizeSp.sp, lineHeight = (sizeSp * LINE_HEIGHT).sp), maxLines = 1, softWrap = false)
            }
            // Text width and height both grow with its size, so the text's area grows with its square:
            // start a little above the size whose text would cover the heart, then step down.
            val reference = measure(REFERENCE_SP)
            val referenceInk = reference.sumOf { (it.size.width + gapPx).toDouble() } * reference.first().size.height
            var sizeSp = min(MAX_SP, REFERENCE_SP * sqrt(area.areaPx / referenceInk).toFloat() * START_ABOVE)
            // Whether everyone fits jumps as rows come and go, so sizes are stepped, not bisected,
            // and the first that fits and reaches near the point is kept.
            var result: List<Pair<TextLayoutResult, Placed>>? = null
            var resultFill = -1f
            while (sizeSp >= MIN_SP) {
                val texts = measure(sizeSp)
                val layout = layoutHeart(texts.map { it.size.width.toFloat() }, texts.first().size.height.toFloat(), area, gapPx * sizeSp / REFERENCE_SP)
                if (layout != null && layout.fill > resultFill) {
                    result = layout.words.map { texts[it.index] to it }
                    resultFill = layout.fill
                }
                if (resultFill >= GOOD_FILL) break
                sizeSp *= SIZE_STEP
            }
            result.orEmpty()
        }
        Canvas(
            Modifier.layout { measurable, _ ->
                val width = sidePx.toInt()
                val height = (sidePx * FatHeart.heightRatio).toInt()
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) { placeable.place(0, 0) }
            },
        ) {
            placed.forEach { (text, word) -> drawText(text, topLeft = word.at) }
        }
    }
}

/**
 * The credits heart across the whole screen, to read every name: pinch to zoom and drag to move, or
 * double-tap to zoom in and back out.
 */
@Composable
internal fun CreditsHeartFullScreen(credits: List<HeartCredit>, onClose: () -> Unit) {
    val c = Shell.colors
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var zoom by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().background(c.ground).safeDrawingPadding()) {
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            zoom = (zoom * gestureZoom).coerceIn(1f, MAX_ZOOM)
                            offset = if (zoom == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { tap ->
                            if (zoom > 1f) {
                                zoom = 1f
                                offset = Offset.Zero
                            } else {
                                // Zoom in on the tapped spot.
                                zoom = DOUBLE_TAP_ZOOM
                                offset = (Offset(size.width / 2f, size.height / 2f) - tap) * DOUBLE_TAP_ZOOM
                            }
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                CreditsHeart(
                    credits,
                    Modifier.padding(horizontal = 12.dp).graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = offset.x
                        translationY = offset.y
                    },
                    maxSide = Dp.Infinity,
                )
            }
            // Solid backings keep the controls readable over the names when zoomed in.
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).clip(CircleShape).background(c.surface2)) {
                GlyphButton(Glyphs.Close, stringResource(Res.string.shell_np_close), onClose, c.ink)
            }
            Text(
                stringResource(Res.string.shell_credits_zoom_hint),
                style = Shell.type.bodySmall,
                color = c.ink2,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).clip(RoundedCornerShape(50))
                    .background(c.surface2).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** The entries placed in the heart, and how far down it they reach (1 is the bottom). */
private class HeartLayout(val words: List<Placed>, val fill: Float)

/** An entry of the heart: which one, where its top-left goes, and its row. */
private class Placed(val index: Int, val at: Offset, val row: Int)

/**
 * Flows the entries in order through the heart [area], row by row and span by span, all at one size,
 * or returns null when they don't all fit. A span too narrow for the next entry is left empty. A
 * span's entries are spread across it, edge to edge, so the rows trace the heart; a lone entry is
 * centred.
 */
private fun layoutHeart(widths: List<Float>, rowHeight: Float, area: FatHeart.Area, gap: Float): HeartLayout? {
    if (widths.isEmpty()) return HeartLayout(emptyList(), 1f)
    val rows = (area.heightPx / rowHeight).toInt()
    val out = ArrayList<Placed>(widths.size)
    var next = 0
    var lastRow = 0
    for (row in 0 until rows) {
        for ((left, right) in area.spans((row + 0.5f) * rowHeight)) {
            if (next == widths.size) break
            val first = next
            var used = 0f
            while (next < widths.size) {
                val needed = used + (if (next == first) 0f else gap) + widths[next]
                if (needed > right - left) break
                used = needed
                next++
            }
            if (next == first) continue
            val count = next - first
            val textWidth = (first until next).sumOf { widths[it].toDouble() }.toFloat()
            val spacing = if (count > 1) (right - left - textWidth) / (count - 1) else 0f
            var x = if (count > 1) left else left + (right - left - textWidth) / 2f
            for (w in first until next) {
                out += Placed(w, Offset(x, row * rowHeight), row)
                x += widths[w] + spacing
            }
            lastRow = row
        }
    }
    return if (next == widths.size) HeartLayout(out, (lastRow + 1f) / rows) else null
}

/**
 * The fat heart of a heart emoji: a square turned 45° with a round lobe on each upper side. Sizes are
 * in units of the heart's width, with x centred and y pointing down.
 */
private object FatHeart {
    /** The turned square's side: two lobes plus the square span exactly one width. */
    private val side = (1.0 / (1.0 + 1.0 / sqrt(2.0))).toFloat()
    private val halfDiagonal = side / sqrt(2f)
    private val lobeRadius = side / 2f
    private val lobeOffset = side / (2f * sqrt(2f))
    private val top = -(lobeOffset + lobeRadius)

    /** The heart's height over its width. */
    val heightRatio = halfDiagonal - top

    private fun inside(x: Float, y: Float): Boolean {
        fun inLobe(cx: Float) = (x - cx) * (x - cx) + (y + lobeOffset) * (y + lobeOffset) <= lobeRadius * lobeRadius
        return abs(x) + abs(y) <= halfDiagonal || inLobe(-lobeOffset) || inLobe(lobeOffset)
    }

    /** The heart [widthPx] pixels wide, measured from its top. */
    class Area(private val widthPx: Float) {
        val heightPx = heightRatio * widthPx

        /** The heart's area in square pixels. */
        val areaPx: Double = (0 until AREA_ROWS).sumOf { i ->
            spans((i + 0.5f) * heightPx / AREA_ROWS).sumOf { (l, r) -> (r - l).toDouble() }
        } * heightPx / AREA_ROWS

        /** The stretches of the row at pixel height [yPx] inside the heart: one, or two between the lobes. */
        fun spans(yPx: Float): List<Pair<Float, Float>> {
            val y = yPx / widthPx + top
            val result = ArrayList<Pair<Float, Float>>()
            var start = Float.NaN
            for (i in 0..SAMPLES) {
                val x = -0.5f + i.toFloat() / SAMPLES
                val inside = inside(x, y)
                if (inside && start.isNaN()) start = x
                if ((!inside || i == SAMPLES) && !start.isNaN()) {
                    result += (start + 0.5f) * widthPx to (x + 0.5f) * widthPx
                    start = Float.NaN
                }
            }
            return result
        }
    }

    private const val SAMPLES = 400
    private const val AREA_ROWS = 200
}

private val MAX_SIDE = 360.dp
private const val GAP_DP = 9f
private const val REFERENCE_SP = 12f
private const val MIN_SP = 2f
private const val MAX_SP = 22f
private const val SIZE_STEP = 0.99f
private const val LINE_HEIGHT = 1.15f

/** How far above the size whose text would exactly cover the heart the search starts. */
private const val START_ABOVE = 1.15f

/** A layout reaching this far down the heart is full enough to stop looking for a better size. */
private const val GOOD_FILL = 0.94f

private const val MAX_ZOOM = 12f
private const val DOUBLE_TAP_ZOOM = 4f
