@file:OptIn(PlatformSpecificExecApi::class)

package one.wabbit.exec

import java.io.InputStream
import java.io.OutputStream
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.asOutputStream

internal fun InputStream.asKxSource(): Source =
    object : RawSource {
        private var closed = false

        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            check(!closed) { "source is closed" }
            require(byteCount >= 0) { "byteCount: $byteCount < 0" }
            if (byteCount == 0L) return 0L
            val buf = ByteArray(minOf(8192L, byteCount).toInt())
            val read = this@asKxSource.read(buf, 0, buf.size)
            if (read < 0) return -1L
            sink.write(buf, 0, read)
            return read.toLong()
        }

        override fun close() {
            if (!closed) {
                closed = true
                this@asKxSource.close()
            }
        }
    }.buffered()

internal fun OutputStream.asKxSink(): Sink =
    object : RawSink {
        private var closed = false

        override fun write(source: Buffer, byteCount: Long) {
            check(!closed) { "sink is closed" }
            require(byteCount >= 0) { "byteCount: $byteCount < 0" }
            var remaining = byteCount
            val buf = ByteArray(8192)
            while (remaining > 0) {
                val chunk = minOf(buf.size.toLong(), remaining).toInt()
                val read = source.readAtMostTo(buf, 0, chunk)
                require(read >= 0) { "buffer exhausted before writing $byteCount bytes" }
                this@asKxSink.write(buf, 0, read)
                remaining -= read.toLong()
            }
        }

        override fun flush() {
            check(!closed) { "sink is closed" }
            this@asKxSink.flush()
        }

        override fun close() {
            if (!closed) {
                closed = true
                this@asKxSink.close()
            }
        }
    }.buffered()

/**
 * Adapt a JVM [InputStream] factory to portable [ExecSpec.Input].
 *
 * The returned input opens the stream when stdin pumping starts, copies it to child stdin, and closes
 * it after copying. Use this adapter when call sites want to keep the common [ExecSpec] API but their
 * data source is JVM stream-based.
 *
 * @param open stream factory.
 * @return portable stdin source backed by a JVM stream.
 */
@PlatformSpecificExecApi
fun execInputFromStream(open: () -> InputStream): ExecSpec.Input =
    ExecSpec.Input.Source { open().asKxSource() }

/**
 * Adapt a JVM [OutputStream] writer callback to portable [ExecSpec.Input].
 *
 * The callback receives child stdin as an [OutputStream]. It must write the complete payload and
 * return; `kotlin-exec` closes the underlying stream after the callback completes.
 *
 * @param write callback that writes child stdin.
 * @return portable stdin writer backed by a JVM output stream.
 */
@PlatformSpecificExecApi
fun execInputWriteTo(write: (OutputStream) -> Unit): ExecSpec.Input =
    ExecSpec.Input.WriteTo { sink ->
        write(sink.asOutputStream())
    }
