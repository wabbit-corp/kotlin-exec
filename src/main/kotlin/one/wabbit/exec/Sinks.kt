package one.wabbit.exec

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import one.wabbit.throwables.Throwables

internal data class SinkFinished(
    val captured: ExecResult.Captured?,
    val stats: ExecResult.OutputStats,
    val error: ExecError? = null,
)

internal sealed interface Sink {
    fun offer(buf: ByteArray, off: Int, len: Int): ExecError?
    fun finish(): SinkFinished
    fun close() {}
}

/** Build a sink instance for a given SinkSpec. */
internal fun sinkFor(spec: ExecSpec.SinkSpec, meta: ExecResult.Meta, stream: StreamId): Sink =
    when (spec) {
        is ExecSpec.SinkSpec.Capture ->
            when (spec.keep) {
                ExecSpec.Keep.Head -> HeadCaptureSink(meta, stream, spec.maxBytes, spec.overflow)
                ExecSpec.Keep.Tail -> TailCaptureSink(meta, stream, spec.maxBytes, spec.overflow)
            }
        is ExecSpec.SinkSpec.Stream ->
            StreamSink(meta, stream, spec.onChunk, spec.copyChunks, spec.maxBytes, spec.overflow)
        is ExecSpec.SinkSpec.File -> FileSink(meta, stream, spec.path, spec.write, spec.maxBytes, spec.overflow)
        is ExecSpec.SinkSpec.Tee -> TeeSink(
            primary = sinkFor(spec.primary, meta, stream),
            branches = spec.branches.map { sinkFor(it, meta, stream) }
        )
    }

/** Keep first N bytes. */
internal class HeadCaptureSink(
    private val meta: ExecResult.Meta,
    private val stream: StreamId,
    private val maxBytes: Int,
    private val overflow: ExecSpec.OverflowPolicy,
) : Sink {
    private val out = HeadBuffer(maxBytes)
    private var bytesRead: Long = 0
    private var truncated: Boolean = false
    private var overflowSignaled: Boolean = false

    override fun offer(buf: ByteArray, off: Int, len: Int): ExecError? {
        bytesRead += len.toLong()
        val written = out.write(buf, off, len)
        if (written < len) truncated = true

        if (!overflowSignaled && overflow == ExecSpec.OverflowPolicy.KillProcess && bytesRead > maxBytes.toLong()) {
            overflowSignaled = true
            return ExecError.OutputLimitExceeded(
                meta = meta,
                stream = stream,
                limitBytes = maxBytes,
                observedBytes = bytesRead,
            )
        }
        return null
    }

    override fun finish(): SinkFinished {
        val cap = ExecResult.Captured(bytes = out.toByteArray(), truncated = truncated, bytesRead = bytesRead)
        return SinkFinished(
            captured = cap,
            stats = ExecResult.OutputStats(bytesRead = bytesRead, truncated = truncated)
        )
    }
}

/** Keep last N bytes using a ring buffer. */
internal class TailCaptureSink(
    private val meta: ExecResult.Meta,
    private val stream: StreamId,
    private val maxBytes: Int,
    private val overflow: ExecSpec.OverflowPolicy,
) : Sink {
    private val ring = ByteRing(maxBytes)
    private var bytesRead: Long = 0
    private var overflowSignaled: Boolean = false

    override fun offer(buf: ByteArray, off: Int, len: Int): ExecError? {
        bytesRead += len.toLong()
        ring.write(buf, off, len)

        if (!overflowSignaled && overflow == ExecSpec.OverflowPolicy.KillProcess && bytesRead > maxBytes.toLong()) {
            overflowSignaled = true
            return ExecError.OutputLimitExceeded(
                meta = meta,
                stream = stream,
                limitBytes = maxBytes,
                observedBytes = bytesRead,
            )
        }
        return null
    }

    override fun finish(): SinkFinished {
        val truncated = bytesRead > maxBytes.toLong()
        val cap = ExecResult.Captured(bytes = ring.toByteArray(), truncated = truncated, bytesRead = bytesRead)
        return SinkFinished(
            captured = cap,
            stats = ExecResult.OutputStats(bytesRead = bytesRead, truncated = truncated)
        )
    }
}

internal class StreamSink(
    private val meta: ExecResult.Meta,
    private val stream: StreamId,
    private val onChunk: (ByteArray, Int, Int) -> Unit,
    private val copyChunks: Boolean,
    private val maxBytes: Int?,
    private val overflow: ExecSpec.OverflowPolicy,
) : Sink {
    private var bytesRead: Long = 0
    private var truncated: Boolean = false
    private var overflowSignaled: Boolean = false
    private var callbackEnabled: Boolean = true

    override fun offer(buf: ByteArray, off: Int, len: Int): ExecError? {
        val prev = bytesRead
        bytesRead += len.toLong()

        // Fast path: no limit or callback already disabled.
        if (maxBytes == null || !callbackEnabled) {
            if (callbackEnabled) {
                tryDeliver(buf, off, len)
            }
            return null
        }

        val limit = maxBytes.toLong()
        val allowed = (limit - prev).coerceAtLeast(0L).toInt()

        // If we're already at/over the limit, stop delivering.
        if (allowed <= 0) {
            truncated = true
            callbackEnabled = false
            if (overflow == ExecSpec.OverflowPolicy.KillProcess && !overflowSignaled && bytesRead > limit) {
                overflowSignaled = true
                return ExecError.OutputLimitExceeded(meta = meta, stream = stream, limitBytes = maxBytes, observedBytes = bytesRead)
            }
            return null
        }

        val deliver = minOf(len, allowed)
        if (deliver < len) {
            truncated = true
            callbackEnabled = false
        }

        // Deliver up to the allowed boundary (so callers actually get "up to maxBytes").
        tryDeliver(buf, off, deliver)

        // If we exceeded and policy is KillProcess, signal now (after delivering allowed bytes).
        if (overflow == ExecSpec.OverflowPolicy.KillProcess && !overflowSignaled && bytesRead > limit) {
            overflowSignaled = true
            return ExecError.OutputLimitExceeded(
                meta = meta,
                stream = stream,
                limitBytes = maxBytes,
                observedBytes = bytesRead,
            )
        }

        // DrainAndTruncate: keep draining silently after callback disabled.
        return null
    }

    private fun tryDeliver(buf: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        try {
            if (copyChunks) {
                val chunk = buf.copyOfRange(off, off + len)
                onChunk(chunk, 0, len)
            } else {
                onChunk(buf, off, len)
            }
        } catch (t: Throwable) {
            Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
            throw ExecException(
                ExecError.OutputConsumerFailed(meta = meta, stream = stream, cause = t)
            )
        }
    }

    override fun finish(): SinkFinished {
        // Streaming mode does not retain bytes (by design).
        return SinkFinished(
            captured = null,
            stats = ExecResult.OutputStats(bytesRead = bytesRead, truncated = truncated)
        )
    }
}

internal class FileSink(
    private val meta: ExecResult.Meta,
    private val stream: StreamId,
    private val path: Path,
    private val write: FileWritePolicy,
    private val maxBytes: Int?,
    private val overflow: ExecSpec.OverflowPolicy,
) : Sink {
    private var bytesRead: Long = 0
    private var bytesWritten: Long = 0
    private var truncated: Boolean = false
    private var overflowSignaled: Boolean = false
    private var os: OutputStream? = null

    init {
        // Default behavior is eager open: create/truncate/append at exec start so a "no output" run
        // doesn't leave stale files, and file errors surface immediately.
        if (write.eager) {
            // Opens and keeps the handle for the duration (closed in finish()/close()).
            openIfNeeded()
        }
    }

    private fun openIfNeeded(): OutputStream {
        val existing = os
        if (existing != null) return existing
        val created =
            when (write) {
                is FileWritePolicy.Append ->
                    Files.newOutputStream(
                        path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND,
                    )
                is FileWritePolicy.Truncate ->
                    Files.newOutputStream(
                        path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                    )
            }
        os = created
        return created
    }

    override fun offer(buf: ByteArray, off: Int, len: Int): ExecError? {
        bytesRead += len.toLong()

        // Enforce optional maxBytes bound (relative to this run's output, not total file size).
        if (maxBytes != null) {
            if (bytesWritten >= maxBytes.toLong()) {
                truncated = true
                if (!overflowSignaled && overflow == ExecSpec.OverflowPolicy.KillProcess && bytesRead > maxBytes.toLong()) {
                    overflowSignaled = true
                    return ExecError.OutputLimitExceeded(
                        meta = meta,
                        stream = stream,
                        limitBytes = maxBytes,
                        observedBytes = bytesRead,
                    )
                }
                // Drain but do not write.
                return null
            }
        }

        val toWrite: Int =
            if (maxBytes == null) {
                len
            } else {
                val remaining = (maxBytes.toLong() - bytesWritten).coerceAtLeast(0L)
                minOf(len.toLong(), remaining).toInt()
            }

        return try {
            if (toWrite > 0) {
                openIfNeeded().write(buf, off, toWrite)
                bytesWritten += toWrite.toLong()
            }
            if (toWrite < len) truncated = true

            if (maxBytes != null && !overflowSignaled && overflow == ExecSpec.OverflowPolicy.KillProcess && bytesRead > maxBytes.toLong()) {
                overflowSignaled = true
                return ExecError.OutputLimitExceeded(
                    meta = meta,
                    stream = stream,
                    limitBytes = maxBytes,
                    observedBytes = bytesRead,
                )
            }
            null
        } catch (t: Throwable) {
            Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
            ExecError.OutputSinkFailed(meta = meta, stream = stream, cause = t)
        }
    }

    override fun finish(): SinkFinished {
        val closeErr = closeForFinish()
        return SinkFinished(
            captured = null,
            stats = ExecResult.OutputStats(
                bytesRead = bytesRead,
                truncated = truncated || (maxBytes != null && bytesRead > maxBytes.toLong())
            ),
            error = closeErr,
        )
    }

    private fun closeForFinish(): ExecError? {
        val current = os
        os = null
        if (current == null) return null

        var first: Throwable? = null
        try {
            current.flush()
        } catch (t: Throwable) {
            first = t
        }
        try {
            current.close()
        } catch (t: Throwable) {
            if (first == null) first = t else Throwables.addSuppressedBestEffort(first, t)
        }

        if (first != null) {
            Throwables.propagateIfNeeded(first)
            return ExecError.OutputSinkFailed(meta = meta, stream = stream, cause = first)
        }
        return null
    }

    override fun close() {
        val current = os
        os = null
        if (current != null) {
            try { current.flush() } catch (t: Throwable) {
                restoreInterruptFlagIfNeeded(t)
                Throwables.propagateFatalPlatformErrorsIfNeeded(t)
            }
            try { current.close() } catch (t: Throwable) {
                restoreInterruptFlagIfNeeded(t)
                Throwables.propagateFatalPlatformErrorsIfNeeded(t)
            }
        }
    }
}

internal class TeeSink(
    private val primary: Sink,
    private val branches: List<Sink>,
) : Sink {
    override fun offer(buf: ByteArray, off: Int, len: Int): ExecError? {
        val p = primary.offer(buf, off, len)
        if (p != null) return p
        for (b in branches) {
            val e = b.offer(buf, off, len)
            if (e != null) return e
        }
        return null
    }

    override fun finish(): SinkFinished {
        val p = primary.finish()
        var err: ExecError? = p.error
        for (b in branches) {
            val bf = b.finish()
            if (err == null) err = bf.error
        }
        return if (err == null) p else p.copy(error = err)
    }

    override fun close() {
        try { primary.close() } catch (t: Throwable) {
            restoreInterruptFlagIfNeeded(t)
            Throwables.propagateFatalPlatformErrorsIfNeeded(t)
        }
        for (b in branches) {
            try { b.close() } catch (t: Throwable) {
                restoreInterruptFlagIfNeeded(t)
                Throwables.propagateFatalPlatformErrorsIfNeeded(t)
            }
        }
    }
}

/**
 * Bounded head buffer that never allocates more than maxBytes, while still avoiding
 * allocating maxBytes upfront for small outputs.
 */
internal class HeadBuffer(private val maxBytes: Int) {
    private var buf: ByteArray = ByteArray(minOf(8192, maxBytes).coerceAtLeast(1))
    private var size: Int = 0

    fun write(src: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        val remaining = maxBytes - size
        if (remaining <= 0) return 0
        val toWrite = minOf(len, remaining)
        ensureCapacity(size + toWrite)
        System.arraycopy(src, off, buf, size, toWrite)
        size += toWrite
        return toWrite
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity <= buf.size) return
        var newCap = buf.size
        while (newCap < minCapacity) {
            val doubled = newCap * 2
            newCap = minOf(maxBytes, maxOf(minCapacity, doubled))
            if (newCap == maxBytes) break
        }
        if (newCap > maxBytes) newCap = maxBytes
        buf = buf.copyOf(newCap)
    }
}

internal class ByteRing(private val capacity: Int) {
    private val buf: ByteArray = ByteArray(capacity)
    private var writeIndex: Int = 0
    private var filled: Int = 0

    fun write(src: ByteArray, off: Int, len: Int) {
        if (capacity == 0 || len <= 0) return
        var remaining = len
        var srcPos = off

        while (remaining > 0) {
            val toEnd = capacity - writeIndex
            val n = minOf(remaining, toEnd)
            System.arraycopy(src, srcPos, buf, writeIndex, n)
            srcPos += n
            remaining -= n

            writeIndex += n
            if (writeIndex == capacity) writeIndex = 0

            filled = minOf(capacity, filled + n)
        }
    }

    fun toByteArray(): ByteArray {
        if (capacity == 0) return ByteArray(0)
        if (filled < capacity) return buf.copyOfRange(0, filled)

        // When full, writeIndex points to oldest element.
        val out = ByteArray(capacity)
        val tailLen = capacity - writeIndex
        System.arraycopy(buf, writeIndex, out, 0, tailLen)
        if (writeIndex > 0) {
            System.arraycopy(buf, 0, out, tailLen, writeIndex)
        }
        return out
    }
}
