package app.turtleplayer.taste

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Compact pack of 8-bit quantized embeddings: how the server-trained ListenBrainz co-listening
 * vectors ship to devices (one pack for artists, one for top tracks). Signature verification is
 * done by the downloader before parsing; this file only defines the bytes.
 *
 * Layout, all integers little-endian:
 * ```
 * offset size  field
 * 0      4     magic "TTEP" (Turtle Taste Embedding Pack)
 * 4      2     format version (1)
 * 6      1     kind (EmbeddingKind.code: 1 artist, 2 track)
 * 7      1     flags (0; reserved)
 * 8      2     dim
 * 10     2     reserved (0)
 * 12     4     count (number of vectors)
 * 16     8     buildId (server build identifier, e.g. dump date as yyyymmdd or a counter)
 * 24     ...   count entries:
 *                u16 idLength, idLength bytes UTF-8 id, f32 scale, dim x i8 codes
 * end-4  4     CRC-32 (IEEE) of every preceding byte
 * ```
 * A vector is reconstructed as `code[i] * scale`. Quantization is symmetric per vector:
 * `scale = max|v| / 127`, so the int8 range -127..127 is used and -128 never appears.
 */
class EmbeddingPack internal constructor(
    val kind: EmbeddingKind,
    val dim: Int,
    val buildId: Long,
    val ids: List<String>,
    private val scales: FloatArray,
    private val codes: ByteArray,
) {
    val size: Int get() = ids.size

    fun scale(i: Int): Float = scales[i]

    /** Dequantized vector [i]. */
    fun vector(i: Int): FloatArray {
        val s = scales[i]
        val base = i * dim
        return FloatArray(dim) { codes[base + it] * s }
    }

    companion object {
        const val VERSION = 1
        const val HEADER_SIZE = 24
        const val MAX_DIM = 1024
        const val MAX_ID_BYTES = 1024
        internal val MAGIC = byteArrayOf('T'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte(), 'P'.code.toByte())
    }
}

class PackFormatException(message: String) : Exception(message)

object EmbeddingPackWriter {
    /** Quantizes and serializes [entries]. Ids must be unique and non-empty. */
    fun write(kind: EmbeddingKind, dim: Int, entries: List<Pair<String, FloatArray>>, buildId: Long = 0): ByteArray {
        require(dim in 1..EmbeddingPack.MAX_DIM) { "dim out of range: $dim" }
        val seen = HashSet<String>()
        val sink = ByteSink(EmbeddingPack.HEADER_SIZE + entries.size * (dim + 16) + 4)
        sink.bytes(EmbeddingPack.MAGIC)
        sink.u16(EmbeddingPack.VERSION)
        sink.u8(kind.code)
        sink.u8(0)
        sink.u16(dim)
        sink.u16(0)
        sink.u32(entries.size.toLong())
        sink.i64(buildId)
        for ((id, v) in entries) {
            require(id.isNotEmpty()) { "Empty id" }
            require(seen.add(id)) { "Duplicate id '$id'" }
            require(v.size == dim) { "Vector for '$id' has dim ${v.size}, expected $dim" }
            require(v.isFiniteVector()) { "Vector for '$id' is not finite" }
            val idBytes = id.encodeToByteArray()
            require(idBytes.size <= EmbeddingPack.MAX_ID_BYTES) { "Id too long: '$id'" }
            sink.u16(idBytes.size)
            sink.bytes(idBytes)
            val maxAbs = v.maxOf { abs(it) }
            val scale = if (maxAbs == 0f) 0f else maxAbs / 127f
            sink.u32(scale.toRawBits().toLong() and 0xFFFFFFFFL)
            for (x in v) {
                val q = if (scale == 0f) 0 else (x / scale).roundToInt().coerceIn(-127, 127)
                sink.u8(q and 0xFF)
            }
        }
        sink.u32(Crc32.of(sink.buffer, 0, sink.size))
        return sink.toByteArray()
    }
}

object EmbeddingPackReader {
    /**
     * Parses and validates a pack. Throws [PackFormatException] on any corruption, truncation,
     * unknown version or kind, dimension mismatch with [expectedDim], or kind mismatch with
     * [expectedKind]. Never allocates more than the input size justifies.
     */
    fun read(bytes: ByteArray, expectedDim: Int? = EMBEDDING_DIM, expectedKind: EmbeddingKind? = null): EmbeddingPack {
        if (bytes.size < EmbeddingPack.HEADER_SIZE + 4) throw PackFormatException("Pack too short: ${bytes.size} bytes")
        for (i in 0 until 4) if (bytes[i] != EmbeddingPack.MAGIC[i]) throw PackFormatException("Bad magic")
        val bodyEnd = bytes.size - 4
        val src = ByteSource(bytes, 4, bodyEnd)
        val storedCrc = ByteSource(bytes, bodyEnd, bytes.size).u32()
        if (Crc32.of(bytes, 0, bodyEnd) != storedCrc) throw PackFormatException("Checksum mismatch")

        val version = src.u16()
        if (version != EmbeddingPack.VERSION) throw PackFormatException("Unsupported version $version")
        val kind = EmbeddingKind.fromCode(src.u8()) ?: throw PackFormatException("Unknown kind")
        src.u8() // flags, reserved
        val dim = src.u16()
        if (dim !in 1..EmbeddingPack.MAX_DIM) throw PackFormatException("Bad dim $dim")
        if (expectedDim != null && dim != expectedDim) throw PackFormatException("Dim $dim, expected $expectedDim")
        if (expectedKind != null && kind != expectedKind) throw PackFormatException("Kind $kind, expected $expectedKind")
        src.u16() // reserved
        val count = src.u32()
        val buildId = src.i64()
        // Smallest possible entry: 2 (id length) + 1 (id) + 4 (scale) + dim codes.
        val minEntry = 7L + dim
        if (count * minEntry > src.remaining) throw PackFormatException("Count $count does not fit in ${src.remaining} bytes")
        val n = count.toInt()

        val ids = ArrayList<String>(n)
        val seen = HashSet<String>(n * 2)
        val scales = FloatArray(n)
        val codes = ByteArray(n * dim)
        for (i in 0 until n) {
            val idLen = src.u16()
            if (idLen == 0 || idLen > EmbeddingPack.MAX_ID_BYTES) throw PackFormatException("Bad id length $idLen at entry $i")
            val id = try {
                src.bytes(idLen).decodeToString(throwOnInvalidSequence = true)
            } catch (e: CharacterCodingException) {
                throw PackFormatException("Invalid UTF-8 id at entry $i")
            }
            if (!seen.add(id)) throw PackFormatException("Duplicate id '$id'")
            val scale = Float.fromBits(src.u32().toInt())
            if (!scale.isFinite() || scale < 0f) throw PackFormatException("Bad scale for '$id'")
            ids += id
            scales[i] = scale
            src.copyInto(codes, i * dim, dim)
        }
        if (src.remaining != 0L) throw PackFormatException("${src.remaining} trailing bytes")
        return EmbeddingPack(kind, dim, buildId, ids, scales, codes)
    }
}

private class ByteSink(initial: Int) {
    var buffer = ByteArray(maxOf(initial, 16))
        private set
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(size + extra, buffer.size * 2))
    }

    fun u8(v: Int) {
        ensure(1)
        buffer[size++] = v.toByte()
    }

    fun u16(v: Int) {
        u8(v and 0xFF)
        u8((v ushr 8) and 0xFF)
    }

    fun u32(v: Long) {
        for (i in 0 until 4) u8(((v ushr (8 * i)) and 0xFF).toInt())
    }

    fun i64(v: Long) {
        for (i in 0 until 8) u8(((v ushr (8 * i)) and 0xFF).toInt())
    }

    fun bytes(b: ByteArray) {
        ensure(b.size)
        b.copyInto(buffer, size)
        size += b.size
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

/** Bounds-checked little-endian reader over `[start, end)`; truncation becomes [PackFormatException]. */
private class ByteSource(private val data: ByteArray, start: Int, private val end: Int) {
    private var pos = start

    val remaining: Long get() = (end - pos).toLong()

    private fun need(n: Int) {
        if (n < 0 || pos + n > end) throw PackFormatException("Truncated pack at byte $pos")
    }

    fun u8(): Int {
        need(1)
        return data[pos++].toInt() and 0xFF
    }

    fun u16(): Int = u8() or (u8() shl 8)

    fun u32(): Long {
        var v = 0L
        for (i in 0 until 4) v = v or (u8().toLong() shl (8 * i))
        return v
    }

    fun i64(): Long {
        var v = 0L
        for (i in 0 until 8) v = v or (u8().toLong() shl (8 * i))
        return v
    }

    fun bytes(n: Int): ByteArray {
        need(n)
        return data.copyOfRange(pos, pos + n).also { pos += n }
    }

    fun copyInto(dest: ByteArray, offset: Int, n: Int) {
        need(n)
        data.copyInto(dest, offset, pos, pos + n)
        pos += n
    }
}

/** CRC-32 (IEEE 802.3, reflected polynomial 0xEDB88320), as used by zip and PNG. */
internal object Crc32 {
    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    fun of(data: ByteArray, from: Int, to: Int): Long {
        var c = -1
        for (i in from until to) c = table[(c xor data[i].toInt()) and 0xFF] xor (c ushr 8)
        return (c.inv().toLong()) and 0xFFFFFFFFL
    }
}
