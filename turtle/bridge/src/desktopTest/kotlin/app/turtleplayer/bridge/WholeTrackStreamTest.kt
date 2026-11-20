package app.turtleplayer.bridge

import com.metrolist.shared.playback.ffmpeg.HttpRangeInputStream
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards our whole-track patch to upstream's HttpRangeInputStream (turtle/HOOKS.md #15): an
 * audio-sized stream is fetched whole in the background and kept, so seeks are served locally.
 */
class WholeTrackStreamTest {
    private val media = Random(7).nextBytes(2 * 1024 * 1024 + 12_345)
    private val requests = AtomicInteger()
    private var refuseFrom = Long.MAX_VALUE
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/videoplayback") { ex ->
            requests.incrementAndGet()
            val (start, end) = Regex("""bytes=(\d+)-(\d+)""").find(ex.requestHeaders.getFirst("Range"))!!
                .destructured.let { (a, b) -> a.toLong() to minOf(b.toLong(), media.size - 1L) }
            if (end >= refuseFrom) {
                ex.sendResponseHeaders(403, -1)
            } else {
                ex.responseHeaders.add("Content-Range", "bytes $start-$end/${media.size}")
                ex.sendResponseHeaders(206, end - start + 1)
                ex.responseBody.use { it.write(media, start.toInt(), (end - start + 1).toInt()) }
            }
            ex.close()
        }
        start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun stream() = HttpRangeInputStream(
        httpClient = OkHttpClient(),
        url = "http://127.0.0.1:${server.address.port}/videoplayback",
        requestHeaders = emptyMap(),
        mediaSize = media.size.toLong(),
        chunkSize = 128 * 1024,
        prefetchAhead = true,
    )

    private fun HttpRangeInputStream.readFully(at: Long, length: Int): ByteArray {
        seekToMediaPosition(at)
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val n = read(out, done, length - done)
            if (n < 0) break
            done += n
        }
        return out
    }

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }

    @Test
    fun wholeTrackIsFetchedAndSeeksAreServedLocally() {
        stream().use { s ->
            assertContentEquals(media.copyOfRange(0, 4096), s.readFully(0, 4096))
            // Background fetch completes: the buffered region from the start reaches the end.
            s.seekToMediaPosition(0)
            waitUntil { s.bufferedBytePosition >= media.size }
            val afterPrefetch = requests.get()

            // Seeks forward, backward and to the end: correct bytes, no new requests.
            val far = 1_900_000L
            assertContentEquals(media.copyOfRange(far.toInt(), far.toInt() + 50_000), s.readFully(far, 50_000))
            assertContentEquals(media.copyOfRange(300_000, 340_000), s.readFully(300_000, 40_000))
            val tail = media.size - 1000
            assertContentEquals(media.copyOfRange(tail, media.size), s.readFully(tail.toLong(), 1000))
            assertEquals(afterPrefetch, requests.get(), "seeks after the whole track was fetched must not hit the network")
        }
    }

    @Test
    fun refusedRangeStopsTheBackgroundFetch() {
        refuseFrom = 1024L * 1024 // a client that only serves the first MiB
        stream().use { s ->
            s.readFully(0, 4096)
            Thread.sleep(1_500)
            // Without the stop, every remaining chunk (about 8 more) would be requested and retried.
            assertTrue(requests.get() <= 12, "background fetch kept requesting after a refusal: ${requests.get()}")
        }
    }
}
