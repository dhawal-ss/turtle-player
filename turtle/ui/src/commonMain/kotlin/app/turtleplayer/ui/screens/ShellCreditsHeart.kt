package app.turtleplayer.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.turtleplayer.ui.theme.Shell
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** One name in the credits heart, with a word or two on what they did. */
internal data class HeartCredit(val name: String, val role: String)

/**
 * A heart outline with the people in [credits] set inside it, each named once and followed by a word
 * or two on what they did. The words flow row by row: a name never breaks, but its role words may
 * continue on the next row. The text size is the largest at which everyone fits, so the heart is
 * full whatever the number of people, and each row is justified to the heart's shape.
 */
@Composable
internal fun CreditsHeart(credits: List<HeartCredit>, modifier: Modifier = Modifier) {
    val c = Shell.colors
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // The shortest entry goes last: it lands in the narrow rows near the point, where it stays readable.
    val unique = remember(credits) {
        val distinct = credits.distinctBy { it.name }
        val shortest = distinct.minByOrNull { it.name.length + it.role.length }
        distinct.filter { it != shortest } + listOfNotNull(shortest)
    }
    val description = remember(unique) { unique.joinToString(", ") { "${it.name}, ${it.role}" } }
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 12.dp).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        val side = min(maxWidth.value, MAX_SIDE_DP).dp
        val sidePx = with(density) { side.toPx() }
        val gapPx = with(density) { GAP_DP.dp.toPx() }
        val strokePx = with(density) { OUTLINE_DP.dp.toPx() }
        val base = Shell.type.bodySmall
        val nameStyle = base.copy(color = c.ink, fontWeight = FontWeight.SemiBold)
        val roleStyle = base.copy(color = c.ink2, fontWeight = FontWeight.Normal)
        val inner = remember(sidePx) { HeartShape.inner.map { Offset(it.x * sidePx, it.y * sidePx) } }
        val placed = remember(unique, inner, gapPx, nameStyle, roleStyle) {
            val tokens = unique.flatMap { credit -> listOf(credit.name to nameStyle) + credit.role.split(' ').filter { it.isNotBlank() }.map { it to roleStyle } }
            fun measure(sizeSp: Float) = tokens.map { (text, style) ->
                measurer.measure(text, style.copy(fontSize = sizeSp.sp, lineHeight = (sizeSp * LINE_HEIGHT).sp), maxLines = 1, softWrap = false)
            }
            // Whether the words fit jumps as rows come and go with the size, so sizes are scanned, not
            // bisected, and the one that reaches furthest down wins. The scan runs on widths scaled from
            // one measurement (text width grows with its size), then the best size is measured for real.
            val reference = measure(REFERENCE_SP)
            var best = MIN_SP / REFERENCE_SP
            var bestFill = -1f
            var scale = MAX_SP / REFERENCE_SP
            while (scale >= MIN_SP / REFERENCE_SP) {
                val f = layoutHeart(reference.map { it.size.width * scale }, reference.first().size.height * scale, inner, gapPx * scale)?.fill ?: 0f
                if (f > bestFill + 0.01f) {
                    best = scale
                    bestFill = f
                }
                if (bestFill >= GOOD_FILL) break
                scale *= SIZE_STEP
            }
            var result: List<Pair<TextLayoutResult, Placed>>? = null
            var resultFill = -1f
            var sizeSp = REFERENCE_SP * best * 1.02f
            while (sizeSp >= REFERENCE_SP * best * 0.9f && sizeSp >= MIN_SP) {
                val texts = measure(sizeSp)
                val layout = layoutHeart(texts.map { it.size.width.toFloat() }, texts.first().size.height.toFloat(), inner, gapPx * sizeSp / REFERENCE_SP)
                if (layout != null && layout.fill > resultFill + 0.01f) {
                    result = layout.words.map { texts[it.index] to it }
                    resultFill = layout.fill
                }
                if (resultFill >= GOOD_FILL) break
                sizeSp *= SIZE_STEP
            }
            result.orEmpty()
        }
        val outlineColor = c.accent
        Canvas(Modifier.size(side, side * HeartShape.heightRatio)) {
            val inset = strokePx / 2f
            val reach = size.width - strokePx
            val outline = Path().apply {
                HeartShape.outline.forEachIndexed { i, p ->
                    val x = inset + p.x * reach
                    val y = inset + p.y * reach
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            drawPath(outline, outlineColor, style = Stroke(width = strokePx, join = StrokeJoin.Round))
            placed.forEach { (text, word) ->
                if (word.scale == 1f) {
                    drawText(text, topLeft = word.at)
                } else {
                    scale(word.scale, pivot = word.at) { drawText(text, topLeft = word.at) }
                }
            }
        }
    }
}

/** The words placed in the heart, and how far down its text area they reach (1 is the bottom). */
private class HeartLayout(val words: List<Placed>, val fill: Float)

/** A word of the heart: which one, where its top-left goes, how much it is scaled down, and its row. */
private class Placed(val index: Int, val at: Offset, val scale: Float, val row: Int)

/**
 * Flows the words in order through [area], row by row and span by span, or returns null when they
 * don't all fit. A span too narrow for the next word is left empty. Each span's words are justified
 * across it.
 */
private fun layoutHeart(widths: List<Float>, rowHeight: Float, area: List<Offset>, gap: Float): HeartLayout? {
    if (widths.isEmpty()) return HeartLayout(emptyList(), 1f)
    val top = area.minOf { it.y }
    val bottom = area.maxOf { it.y }
    val widest = area.maxOf { it.x } - area.minOf { it.x }
    val rows = ((bottom - top) / rowHeight).toInt()
    val out = ArrayList<Placed>(widths.size)
    var next = 0
    var lastRow = 0
    for (row in 0 until rows) {
        for ((left, right) in spans(area, top + (row + 0.5f) * rowHeight)) {
            if (next == widths.size) break
            val span = right - left
            // Narrow rows set a little smaller, so the words reach further into the point.
            val scale = (span / (TAPER_FROM * widest)).coerceIn(MIN_TAPER, 1f)
            val first = next
            var used = 0f
            while (next < widths.size) {
                val needed = used + (if (next == first) 0f else gap * scale) + widths[next] * scale
                if (needed > span) break
                used = needed
                next++
            }
            if (next == first) continue
            val words = first until next
            val textWidth = words.sumOf { widths[it].toDouble() }.toFloat() * scale
            val spacing = if (next - first > 1) (span - textWidth) / (next - first - 1) else 0f
            var x = if (next - first > 1) left else left + (span - textWidth) / 2f
            val y = top + row * rowHeight + rowHeight * (1f - scale) / 2f
            for (w in words) {
                out += Placed(w, Offset(x, y), scale, row)
                x += widths[w] * scale + spacing
            }
            lastRow = row
        }
    }
    return if (next == widths.size) HeartLayout(out, (lastRow + 1f) / rows) else null
}

/** The stretches of the row at height [y] that lie inside the polygon [area]: one, or two between the lobes. */
private fun spans(area: List<Offset>, y: Float): List<Pair<Float, Float>> {
    val xs = ArrayList<Float>()
    for (i in area.indices) {
        val p = area[i]
        val q = area[(i + 1) % area.size]
        if ((p.y <= y && y < q.y) || (q.y <= y && y < p.y)) xs += p.x + (y - p.y) / (q.y - p.y) * (q.x - p.x)
    }
    xs.sort()
    return xs.chunked(2).filter { it.size == 2 }.map { it[0] to it[1] }
}

/**
 * The classic heart curve x = 16 sin³t, y = 13 cos t − 5 cos 2t − 2 cos 3t − cos 4t, scaled to a
 * width of 1 with y pointing down. [inner] is the same heart shrunk toward its centre: the text area,
 * which keeps the words clear of the outline.
 */
private object HeartShape {
    val outline: List<Offset>
    val inner: List<Offset>
    val heightRatio: Float

    init {
        val raw = (0 until POINTS).map { i ->
            val t = 2.0 * PI * i / POINTS
            val s = sin(t)
            Offset(
                (16.0 * s * s * s).toFloat(),
                -(13.0 * cos(t) - 5.0 * cos(2 * t) - 2.0 * cos(3 * t) - cos(4 * t)).toFloat(),
            )
        }
        val minX = raw.minOf { it.x }
        val minY = raw.minOf { it.y }
        val width = raw.maxOf { it.x } - minX
        outline = raw.map { Offset((it.x - minX) / width, (it.y - minY) / width) }
        heightRatio = outline.maxOf { it.y }
        val centre = Offset(0.5f, heightRatio / 2f)
        inner = outline.map { centre + (it - centre) * TEXT_INSET }
    }

    private const val POINTS = 360
}

private const val MAX_SIDE_DP = 360f
private const val GAP_DP = 4f
private const val OUTLINE_DP = 1.5f
private const val REFERENCE_SP = 12f
private const val MIN_SP = 6f
private const val MAX_SP = 22f
private const val SIZE_STEP = 0.995f

/** A layout reaching this far down the text area is full enough to stop looking for a better size. */
private const val GOOD_FILL = 0.9f
private const val LINE_HEIGHT = 1.25f

/** How much of the heart the text may use; the rest is clear space inside the outline. */
private const val TEXT_INSET = 0.84f

/** Rows narrower than this share of the text area's width are set smaller, down to [MIN_TAPER]. */
private const val TAPER_FROM = 0.4f
private const val MIN_TAPER = 0.7f
