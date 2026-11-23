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
import kotlin.math.min

/** One name in the credits heart, with a word or two on what they did. */
internal data class HeartCredit(val name: String, val role: String)

/**
 * A heart drawn only with the people in [credits], each named once and followed by a word or two on
 * what they did. The words flow row by row like text set inside the heart's outline: a name never
 * breaks, but its role words may continue on the next row. The text size is the largest at which
 * everything fits, so the heart is full whatever the number of people, and each row is justified to
 * the heart's edge so the outline stays sharp.
 */
@Composable
internal fun CreditsHeart(credits: List<HeartCredit>, modifier: Modifier = Modifier) {
    val c = Shell.colors
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // The shortest entry goes last: it lands in the narrow rows at the point, where it stays readable.
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
        val base = Shell.type.bodySmall
        val nameStyle = base.copy(color = c.ink, fontWeight = FontWeight.SemiBold)
        val roleStyle = base.copy(color = c.ink2, fontWeight = FontWeight.Normal)
        val placed = remember(unique, sidePx, gapPx, nameStyle, roleStyle) {
            val tokens = unique.flatMap { credit -> listOf(credit.name to nameStyle) + credit.role.split(' ').filter { it.isNotBlank() }.map { it to roleStyle } }
            fun measure(sizeSp: Float) = tokens.map { (text, style) ->
                measurer.measure(text, style.copy(fontSize = sizeSp.sp, lineHeight = (sizeSp * LINE_HEIGHT).sp), maxLines = 1, softWrap = false)
            }
            // How far down the heart a layout reaches; 1 means the words end at its point.
            fun fill(words: List<Placed>?, rowHeight: Float) = words?.maxOfOrNull { it.row + 1 }?.let { it * rowHeight / (sidePx * HEIGHT_RATIO) } ?: 0f
            // Whether the words fit jumps as rows come and go with the size, so sizes are scanned, not
            // bisected, and the one that reaches furthest down wins. The scan runs on widths scaled from
            // one measurement (text width grows with its size), then the best size is measured for real.
            val reference = measure(REFERENCE_SP)
            var best = MIN_SP / REFERENCE_SP
            var bestFill = -1f
            var scale = MAX_SP / REFERENCE_SP
            while (scale >= MIN_SP / REFERENCE_SP) {
                val rowHeight = reference.first().size.height * scale
                val f = fill(layoutHeart(reference.map { it.size.width * scale }, rowHeight, sidePx, gapPx * scale), rowHeight)
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
                val rowHeight = texts.first().size.height.toFloat()
                val words = layoutHeart(texts.map { it.size.width.toFloat() }, rowHeight, sidePx, gapPx * sizeSp / REFERENCE_SP)
                val f = fill(words, rowHeight)
                if (words != null && f > resultFill + 0.01f) {
                    result = words.map { texts[it.index] to it }
                    resultFill = f
                }
                if (resultFill >= GOOD_FILL) break
                sizeSp *= SIZE_STEP
            }
            result.orEmpty()
        }
        // The heart ends at its last line; rows below it that no words reached take no space.
        val bottom = placed.maxOfOrNull { (text, word) -> word.at.y + text.size.height * word.scale } ?: 0f
        Canvas(Modifier.size(side, with(density) { bottom.toDp() })) {
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

/**
 * Flows the words in order through the heart, row by row and span by span, or returns null when
 * they don't all fit. A span too narrow for the next word is left empty. Each span's words are
 * justified across it.
 */
private fun layoutHeart(widths: List<Float>, rowHeight: Float, side: Float, gap: Float): List<Placed>? {
    if (widths.isEmpty()) return emptyList()
    val rows = (side * HEIGHT_RATIO / rowHeight).toInt()
    val out = ArrayList<Placed>(widths.size)
    var next = 0
    for (row in 0 until rows) {
        val yMath = Y_MAX - (row + 0.5f) / rows * (Y_MAX - Y_MIN)
        for ((x0, x1) in spans(yMath)) {
            if (next == widths.size) return out
            val left = (x0 - X_MIN) / (X_MAX - X_MIN) * side
            val span = (x1 - x0) / (X_MAX - X_MIN) * side
            // Narrow rows set smaller, so the words reach the point of the heart.
            val scale = (span / (TAPER_FROM * side)).coerceIn(MIN_TAPER, 1f)
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
            // Smaller words sit on the row's baseline area rather than its top.
            val y = row * rowHeight + rowHeight * (1f - scale) / 2f
            for (w in words) {
                out += Placed(w, Offset(x, y), scale, row)
                x += widths[w] * scale + spacing
            }
        }
    }
    return if (next == widths.size) out else null
}

/** A word of the heart: which one, where its top-left goes, how much it is scaled down, and its row. */
private class Placed(val index: Int, val at: Offset, val scale: Float, val row: Int)

/** Where the row at height [y] lies inside the heart (x² + y² − 1)³ − x²y³ ≤ 0: one span, or two near the top. */
private fun spans(y: Float): List<Pair<Float, Float>> {
    val result = ArrayList<Pair<Float, Float>>()
    var start = Float.NaN
    val steps = 240
    for (i in 0..steps) {
        val x = X_MIN + (X_MAX - X_MIN) * i / steps
        val r = x * x + y * y - 1f
        val inside = r * r * r - x * x * y * y * y <= 0f
        if (inside && start.isNaN()) start = x
        if ((!inside || i == steps) && !start.isNaN()) {
            result += start to x
            start = Float.NaN
        }
    }
    return result
}

private const val MAX_SIDE_DP = 360f
private const val GAP_DP = 4f
private const val REFERENCE_SP = 12f
private const val MIN_SP = 6f
private const val MAX_SP = 22f
private const val SIZE_STEP = 0.995f

/** A layout reaching this far down the heart is full enough to stop looking for a better size. */
private const val GOOD_FILL = 0.93f
private const val LINE_HEIGHT = 1.25f

/** Rows narrower than this share of the heart's width are set smaller, down to [MIN_TAPER]. */
private const val TAPER_FROM = 0.45f
private const val MIN_TAPER = 0.6f
private const val X_MIN = -1.2f
private const val X_MAX = 1.2f
private const val Y_MIN = -1.05f
private const val Y_MAX = 1.3f

/** The heart's height over its width, from the bounds above. */
private const val HEIGHT_RATIO = (Y_MAX - Y_MIN) / (X_MAX - X_MIN)
