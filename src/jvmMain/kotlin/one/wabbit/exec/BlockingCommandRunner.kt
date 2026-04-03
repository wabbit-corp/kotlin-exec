@file:OptIn(PlatformSpecificExecApi::class)

package one.wabbit.exec

import one.wabbit.throwables.Throwables
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.io.asInputStream

@Throws(ExecException::class)
internal fun execBlockingInternal(spec: JvmExecSpec, virtualThreads: VirtualThreadsPolicy = VirtualThreadsPolicy.Prefer): ExecResult {
    fun runWith(es: ExecutorService?): ExecResult =
        try {
            execBlockingInternalWithExecutor(spec, taskExecutor = es)
        } finally {
            if (es != null) shutdownExecutor(es, spec.cleanupTimeout)
        }

    return when (virtualThreads) {
        VirtualThreadsPolicy.Never -> runWith(null)
        VirtualThreadsPolicy.Prefer -> runWith(newVirtualThreadPerTaskExecutorOrNull())
        VirtualThreadsPolicy.Require -> runWith(newVirtualThreadPerTaskExecutorOrThrow())
    }
}

@Throws(ExecException::class)
internal fun execBlockingInternalWithExecutor(spec: JvmExecSpec, taskExecutor: Executor?): ExecResult {
    val startNanos = System.nanoTime()
    val baseMeta = ExecResult.Meta(argv = spec.argv, pid = null)

    validateSpecOrThrow(spec, baseMeta)

    // Fail before spawn: catch obvious executor foot-guns (single/fixed pools).
    if (taskExecutor != null) {
        val required = requiredIoParallelism(spec)
        requireExecutorParallelism(taskExecutor, required)
    }

    val pb = try {
        buildProcessBuilder(spec)
    } catch (t: Throwable) {
        Throwables.propagateIfNeeded(t)
        throw ExecException(ExecError.ConfigureFailed(meta = baseMeta, cause = t))
    }

    val proc = try {
        pb.start()
    } catch (t: Throwable) {
        Throwables.propagateIfNeeded(t)
        throw ExecException(ExecError.SpawnFailed(meta = baseMeta, cause = t))
    }

    val meta = baseMeta.copy(pid = pidOrNull(proc))
    val kill = KillSwitch(proc, closeStdinOnKill = spec.stdin !is JvmExecSpec.Input.Inherit, shutdown = spec.shutdown)

    // Create sinks for piped streams. Sink creation can now throw (e.g. eager File sinks).
    var stdoutSink: Sink? = null
    var stderrSink: Sink? = null
    // stdout sink
    (spec.stdout as? JvmExecSpec.StdoutSpec.Pipe)
        ?.takeIf { stdoutNeedsPipe(spec.stdout) }
        ?.let { pipe ->
            try {
                stdoutSink = sinkFor(pipe.sink, meta, StreamId.STDOUT)
            } catch (t: Throwable) {
                stdoutSink?.close()
                stderrSink?.close()
                kill.killOnce()
                Throwables.propagateIfNeeded(t)
                throw ExecException(ExecError.OutputSinkFailed(meta = meta, stream = StreamId.STDOUT, cause = t))
            }
        }
    // stderr sink
    (spec.stderr as? JvmExecSpec.StderrSpec.Pipe)
        ?.takeIf { stderrNeedsPipe(spec.stderr) }
        ?.let { pipe ->
            try {
                stderrSink = sinkFor(pipe.sink, meta, StreamId.STDERR)
            } catch (t: Throwable) {
                stdoutSink?.close()
                stderrSink?.close()
                kill.killOnce()
                Throwables.propagateIfNeeded(t)
                throw ExecException(ExecError.OutputSinkFailed(meta = meta, stream = StreamId.STDERR, cause = t))
            }
        }

// Ensure sink resources (file handles, etc) are released on *all* exit paths.
    try {

        val stdoutTask = stdoutSink?.let {
            FutureTask { pumpStream(proc.inputStream, it, meta, StreamId.STDOUT, kill) }
        }
        val stderrTask = stderrSink?.let {
            FutureTask { pumpStream(proc.errorStream, it, meta, StreamId.STDERR, kill) }
        }

        val stdinTask: FutureTask<Unit>? =
            if (stdinNeedsTask(spec.stdin)) FutureTask { writeStdin(proc, spec.stdin, meta, kill) } else null

        try {
            stdoutTask?.let { launchFutureTask(taskExecutor, "proc-stdout-${meta.pid ?: "?"}", it) }
            stderrTask?.let { launchFutureTask(taskExecutor, "proc-stderr-${meta.pid ?: "?"}", it) }
        } catch (t: Throwable) {
            // If we can't start pump tasks, kill the child to avoid deadlocks and leaks.
            kill.killOnce()
            Throwables.propagateIfNeeded(t)
            throw ExecException(
                ExecError.Unexpected(
                    meta = meta,
                    phase = Phase.Cleanup,
                    cause = t,
                    message = "Failed to start I/O pump tasks",
                ),
            )
        }

        when (spec.stdin) {
            JvmExecSpec.Input.None -> {
                closeQuietly(proc.outputStream)
            }

            JvmExecSpec.Input.Inherit -> {
                // ProcessBuilder.Redirect.INHERIT handles it. Do nothing, do not close outputStream.
            }

            else -> {
                // potentially blocking (large stdin) => run concurrently
                try {
                    stdinTask?.let { launchFutureTask(taskExecutor, "proc-stdin-${meta.pid ?: "?"}", it) }
                } catch (t: Throwable) {
                    kill.killOnce()
                    Throwables.propagateIfNeeded(t)
                    throw ExecException(
                        ExecError.Unexpected(
                            meta = meta,
                            phase = Phase.Cleanup,
                            cause = t,
                            message = "Failed to start stdin task",
                        ),
                    )
                }
            }
        }

        var timedOut = false
        var interrupted: Throwable? = null

        var finished: Boolean = false
        try {
            finished =
                if (spec.timeout != null) {
                    proc.waitFor(spec.timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                } else {
                    proc.waitFor()
                    true
                }
        } catch (ie: InterruptedException) {
            // If the process already exited, treat this as a late interrupt and allow result to proceed.
            //
            // IMPORTANT: InterruptedException clears the interrupted flag. Restore immediately so we
            // never lose it, even if we later throw for some other reason (pump error, timeout, etc).
            Thread.currentThread().interrupt()
            interrupted = ie
            finished = !proc.isAlive
        }

        if (!finished) {
            if (interrupted != null) {
                kill.killOnce()
            } else {
                timedOut = true
                kill.killOnce()
            }
        }

        // Cleanup / join tasks with explicit cleanupTimeout budget.
        val cleanupDeadline = Deadline.from(spec.cleanupTimeout)

        val tasks = mutableListOf<FutureTask<Unit>>()
        stdoutTask?.let { tasks += it }
        stderrTask?.let { tasks += it }
        stdinTask?.let { tasks += it }

        var awaitRes = awaitAllWithin(tasks, cleanupDeadline)
        if (awaitRes.interrupted != null && interrupted == null) interrupted = awaitRes.interrupted

        // If we were interrupted but tasks are already done, treat cleanup as complete.
        if (!awaitRes.ok && tasks.all { it.isDone }) awaitRes = AwaitAllResult(ok = true, interrupted = awaitRes.interrupted)

        // If tasks didn't finish in time, do a last-ditch unblock: killOnce() closes streams.
        if (!awaitRes.ok) {
            kill.killOnce()
            val awaitRes2 = awaitAllWithin(tasks, cleanupDeadline)
            if (awaitRes2.interrupted != null && interrupted == null) interrupted = awaitRes2.interrupted
            awaitRes = AwaitAllResult(ok = awaitRes2.ok, interrupted = awaitRes.interrupted ?: awaitRes2.interrupted)
        }

        val joinedAll = awaitRes.ok

        // Only finish sinks once pump tasks are definitely done (avoid data races).
        val stdoutFinished = if (joinedAll) stdoutSink?.finish() else null
        val stderrFinished = if (joinedAll) stderrSink?.finish() else null
        val stdoutCap = stdoutFinished?.captured
        val stderrCap = stderrFinished?.captured
        val stdoutStats = stdoutFinished?.stats
        val stderrStats = stderrFinished?.stats
        val finishError: ExecError? = stdoutFinished?.error ?: stderrFinished?.error
        val captures =
            ExecResult.Captures(
                stdout = stdoutCap,
                stderr = stderrCap,
                stdoutStats = stdoutStats,
                stderrStats = stderrStats,
            )

        val exitAfterKill: Int? =
            if (proc.isAlive) {
                val rem = cleanupDeadline.remainingMillis()
                val exited =
                    if (rem > 0) {
                        try {
                            proc.waitFor(rem, TimeUnit.MILLISECONDS)
                        } catch (ie: InterruptedException) {
                            // Best-effort metadata probe; preserve interrupt status and continue.
                            Thread.currentThread().interrupt()
                            if (interrupted == null) interrupted = ie
                            false
                        } catch (t: Throwable) {
                            // Never bury JVM death on "best effort" paths.
                            Throwables.propagateFatalPlatformErrorsIfNeeded(t)
                            false
                        }
                    } else {
                        false
                    }
                if (exited) exitValueOrNull(proc) else null
            } else {
                exitValueOrNull(proc)
            }

        val durMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
        val duration = durMs.milliseconds

        // If pumps signaled an ExecException, it was thrown inside their task, not here.
        // NOTE: we intentionally do NOT immediately throw here.
        //
        // Rationale:
        // - firstPumpError() must still run to observe and propagate fatal JVM errors from pump tasks.
        // - At the API boundary, timeout/interrupt semantics may take precedence over ordinary pump failures.
        val pumpError: ExecError? = firstPumpError(stdoutTask, stderrTask, stdinTask, meta, captures)

        if (timedOut) {
            throw ExecException(
                ExecError.TimedOut(
                    meta = meta,
                    timeoutMs = spec.timeout?.inWholeMilliseconds ?: 0L,
                    exitCodeAfterKill = exitAfterKill,
                    captures = captures,
                ),
            )
        }

        if (interrupted != null) {
            // Interrupted flag was restored at the catch site (and awaitAllWithin() also restores it).
            // If the process exited and we successfully joined tasks, don't turn a late interrupt into a hard failure.
            if (!finished || !joinedAll) {
                throw ExecException(
                    ExecError.Cancelled(
                        meta = meta,
                        cause = interrupted,
                        exitCodeAfterKill = exitAfterKill,
                        captures = captures,
                    ),
                )
            }
        }

        // Now that timeout/interrupt semantics are handled, report pump failures (overflow/sink/consumer/etc).
        if (pumpError != null) throw ExecException(pumpError.copyWithCaptures(captures))

        if (finishError != null) {
            throw ExecException(
                finishError.copyWithCaptures(captures),
            )
        }

        if (!joinedAll) {
            // Don't block forever. Ever.
            throw ExecException(
                ExecError.CleanupFailed(
                    meta = meta,
                    cause = null,
                    message = "Cleanup exceeded ${spec.cleanupTimeout.inWholeMilliseconds}ms (tasks did not finish)",
                    captures = captures,
                ),
            )
        }

        val exit = exitValueOrNull(proc) ?: exitAfterKill ?: -1
        val result =
            ExecResult(
                meta = meta,
                exitCode = ExitCode(exit),
                stdout = stdoutCap,
                stderr = stderrCap,
                stdoutStats = stdoutStats,
                stderrStats = stderrStats,
                duration = duration,
            )

        if (spec.exitPolicy == ExitPolicy.ThrowOnNonZero && exit != 0) {
            throw ExecException(
                ExecError.ExitNonZero(
                    meta = meta,
                    exitCode = exit,
                    captures = captures,
                ),
            )
        }

        return result
    } finally {
        // Best-effort: ensure sinks release resources even when no finish() happened (file tee branches, etc).
        stdoutSink?.close()
        stderrSink?.close()
    }
}

private fun firstPumpError(
    stdoutTask: FutureTask<Unit>?,
    stderrTask: FutureTask<Unit>?,
    stdinTask: FutureTask<Unit>?,
    meta: ExecResult.Meta,
    captures: ExecResult.Captures,
): ExecError? {
    fun unwrap(task: FutureTask<Unit>?): ExecError? {
        if (task == null) return null
        return try {
            // best-effort: don't wait forever; tasks should have ended if joinedAll succeeded.
            task.get(0, TimeUnit.MILLISECONDS)
            null
        } catch (e: ExecutionException) {
            val c = e.cause
            when (c) {
                is ExecException -> c.error.copyWithCaptures(captures)
                null -> {
                    Throwables.propagateIfNeeded(e, WORKER_THROWABLE_POLICY)
                    ExecError.Unexpected(meta = meta, phase = Phase.Cleanup, cause = e, captures = captures)
                }

                else -> {
                    Throwables.propagateIfNeeded(c, WORKER_THROWABLE_POLICY)
                    ExecError.Unexpected(meta = meta, phase = Phase.Cleanup, cause = c, captures = captures)
                }
            }
        } catch (_: TimeoutException) {
            null
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            ExecError.Cancelled(meta = meta, cause = ie, captures = captures)
        } catch (t: Throwable) {
            Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
            ExecError.Unexpected(meta = meta, phase = Phase.Cleanup, cause = t, captures = captures)
        }
    }

    return unwrap(stdoutTask)
        ?: unwrap(stderrTask)
        ?: unwrap(stdinTask)
}

/** Attach captures to any error (best-effort). */
internal fun ExecError.copyWithCaptures(c: ExecResult.Captures): ExecError =
    when (this) {
        is ExecError.ConfigureFailed -> this.copy(captures = c)
        is ExecError.SpawnFailed -> this.copy(captures = c)
        is ExecError.InputProviderFailed -> this.copy(captures = c)
        is ExecError.StdinWriteFailed -> this.copy(captures = c)
        is ExecError.OutputConsumerFailed -> this.copy(captures = c)
        is ExecError.OutputSinkFailed -> this.copy(captures = c)
        is ExecError.StreamReadFailed -> this.copy(captures = c)
        is ExecError.OutputLimitExceeded -> this.copy(captures = c)
        is ExecError.TimedOut -> this.copy(captures = c)
        is ExecError.Cancelled -> this.copy(captures = c)
        is ExecError.WaitFailed -> this.copy(captures = c)
        is ExecError.KillFailed -> this.copy(captures = c)
        is ExecError.CleanupFailed -> this.copy(captures = c)
        is ExecError.ExitNonZero -> this.copy(captures = c)
        is ExecError.Unexpected -> this.copy(captures = c)
    }

@Throws(IllegalArgumentException::class)
internal fun validateSpecOrThrow(spec: JvmExecSpec, meta: ExecResult.Meta) {
    require(spec.argv.isNotEmpty()) { "argv must not be empty" }

    fun validateSink(s: JvmExecSpec.SinkSpec, label: String) {
        when (s) {
            is JvmExecSpec.SinkSpec.Capture -> require(s.maxBytes > 0) { "$label capture maxBytes must be > 0" }
            is JvmExecSpec.SinkSpec.Stream -> s.maxBytes?.let { require(it > 0) { "$label stream maxBytes must be > 0" } }
            is JvmExecSpec.SinkSpec.WriteTo -> {
                s.maxBytes?.let { require(it > 0) { "$label writeTo maxBytes must be > 0" } }
            }
            is JvmExecSpec.SinkSpec.File -> {
                s.maxBytes?.let { require(it > 0) { "$label file maxBytes must be > 0" } }
            }

            is JvmExecSpec.SinkSpec.Tee -> {
                validateSink(s.primary, label)
                for (b in s.branches) validateSink(b, label)
            }
        }
    }

    when (val o = spec.stdout) {
        is JvmExecSpec.StdoutSpec.Pipe -> validateSink(o.sink, "stdout")
        else -> {}
    }
    when (val e = spec.stderr) {
        is JvmExecSpec.StderrSpec.Pipe -> validateSink(e.sink, "stderr")
        else -> {}
    }

    spec.timeout?.let { require(it.isFinite()) { "timeout must be finite or null" } }
    require(spec.cleanupTimeout.isFinite()) { "cleanupTimeout must be finite" }
    require(spec.cleanupTimeout > Duration.ZERO) {
        "cleanupTimeout must be > 0"
    }

    when (val s = spec.shutdown) {
        is ShutdownPolicy.KillTree -> {}
        is ShutdownPolicy.TerminateThenKillTree -> {
            require(s.grace.isFinite()) { "shutdown.grace must be finite" }
            require(!s.grace.isNegative()) { "shutdown.grace must be >= 0" }
            require(s.grace <= spec.cleanupTimeout) {
                "shutdown.grace (${s.grace.inWholeMilliseconds}ms) must be <= cleanupTimeout (${spec.cleanupTimeout.inWholeMilliseconds}ms)"
            }
        }
    }
}

internal fun buildProcessBuilder(spec: JvmExecSpec): ProcessBuilder {
    val pb = ProcessBuilder(spec.argv)
    spec.cwd?.let { pb.directory(fileOf(it)) }
    applyEnv(pb, spec.env)

    // stdin
    when (spec.stdin) {
        is JvmExecSpec.Input.Inherit -> pb.redirectInput(ProcessBuilder.Redirect.INHERIT)
        else -> pb.redirectInput(ProcessBuilder.Redirect.PIPE)
    }

    // stdout
    configureStdout(pb, spec.stdout)

    // stderr / merge
    when (val e = spec.stderr) {
        JvmExecSpec.StderrSpec.ToStdout -> {
            pb.redirectErrorStream(true)
        }

        else -> {
            pb.redirectErrorStream(false)
            configureStderr(pb, e)
        }
    }

    return pb
}

private fun configureStdout(pb: ProcessBuilder, out: JvmExecSpec.StdoutSpec) {
    when (out) {
        JvmExecSpec.StdoutSpec.Inherit -> pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        JvmExecSpec.StdoutSpec.Discard -> pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        is JvmExecSpec.StdoutSpec.Pipe -> {
            val s = out.sink
            if (s is JvmExecSpec.SinkSpec.File && isSpecializableRedirect(s)) {
                val f = fileOf(s.path)
                pb.redirectOutput(
                    when (s.write) {
                        is FileWritePolicy.Append -> ProcessBuilder.Redirect.appendTo(f)
                        else -> ProcessBuilder.Redirect.to(f)
                    },
                )
            } else {
                pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
            }
        }
    }
}

private fun configureStderr(pb: ProcessBuilder, out: JvmExecSpec.StderrSpec) {
    when (out) {
        JvmExecSpec.StderrSpec.Inherit -> pb.redirectError(ProcessBuilder.Redirect.INHERIT)
        JvmExecSpec.StderrSpec.Discard -> pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        JvmExecSpec.StderrSpec.ToStdout -> { /* handled by redirectErrorStream(true) */
        }

        is JvmExecSpec.StderrSpec.Pipe -> {
            val s = out.sink
            if (s is JvmExecSpec.SinkSpec.File && isSpecializableRedirect(s)) {
                val f = fileOf(s.path)
                pb.redirectError(
                    when (s.write) {
                        is FileWritePolicy.Append -> ProcessBuilder.Redirect.appendTo(f)
                        is FileWritePolicy.Truncate -> ProcessBuilder.Redirect.to(f)
                    },
                )
            } else {
                pb.redirectError(ProcessBuilder.Redirect.PIPE)
            }
        }
    }
}

/**
 * Drain stream to sink.
 *
 * Important: If the process was killed (timeout/cancel/output-limit from another task),
 * stream reads may throw due to stream closure. Those exceptions are suppressed.
 */
internal fun pumpStream(
    ins: InputStream,
    sink: Sink,
    meta: ExecResult.Meta,
    stream: StreamId,
    kill: KillSwitch,
) {
    val buf = ByteArray(8192)
    try {
        while (true) {
            val n = try {
                ins.read(buf)
            } catch (t: Throwable) {
                if (kill.wasKilled()) {
                    // Shutdown noise is expected during kill/timeout/cancel paths.
                    //
                    // IMPORTANT: Do NOT restore interrupt status here. Pump tasks often run on pooled
                    // threads (Dispatchers.IO / executors); restoring interrupts can "poison" the pool.
                    Throwables.propagateFatalPlatformErrorsIfNeeded(t, WORKER_THROWABLE_POLICY)
                    break
                }
                // If we can't read a piped stream, we must kill to avoid deadlocks/leaks.
                kill.killOnce()
                Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
                throw ExecException(ExecError.StreamReadFailed(meta = meta, stream = stream, cause = t))
            }
            if (n == -1) break
            val err =
                try {
                    sink.offer(buf, 0, n)
                } catch (t: Throwable) {
                    // If we're already killing (timeout/cancel/output-limit from another task),
                    // and we get an interrupt-based consumer/sink failure, treat it as shutdown noise.
                    if (kill.wasKilled() && t is ExecException) {
                        when (val e = t.error) {
                            is ExecError.OutputConsumerFailed ->
                                if (e.cause is InterruptedException) break

                            is ExecError.OutputSinkFailed ->
                                if (e.cause is InterruptedException) break

                            else -> {}
                        }
                    }

                    kill.killOnce()
                    Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
                    throw t
                }
            if (err != null) {
                // If we're already killing (timeout/cancel/output-limit), and the sink reports an interrupt-based
                // consumer failure, treat it as a normal shutdown signal rather than "consumer exploded".
                if (kill.wasKilled()) {
                    when (err) {
                        is ExecError.OutputConsumerFailed ->
                            if (err.cause is InterruptedException) break

                        is ExecError.OutputSinkFailed ->
                            if (err.cause is InterruptedException) break

                        else -> {}
                    }
                }
                // Output-limit/consumer errors should terminate the run; killOnce() closes streams to unblock others.
                kill.killOnce()
                throw ExecException(err)
            }
        }
    } finally {
        closeQuietlyWorker(ins)
    }
}

internal fun writeStdin(proc: Process, stdin: JvmExecSpec.Input, meta: ExecResult.Meta, kill: KillSwitch) {
    when (stdin) {
        is JvmExecSpec.Input.None -> {
            // EOF
            closeQuietly(proc.outputStream)
        }

        is JvmExecSpec.Input.Inherit -> {
            // Do nothing. Do NOT close outputStream here.
        }

        is JvmExecSpec.Input.Bytes -> {
            try {
                proc.outputStream.use { it.write(stdin.bytes) }
            } catch (t: Throwable) {
                if (kill.wasKilled()) {
                    Throwables.propagateFatalPlatformErrorsIfNeeded(t, WORKER_THROWABLE_POLICY)
                    return
                }
                if (t is java.io.IOException && !proc.isAlive) return
                kill.killOnce()
                Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
                throw ExecException(ExecError.StdinWriteFailed(meta = meta, cause = t))
            }
        }

        is JvmExecSpec.Input.Text -> {
            try {
                proc.outputStream.use { it.write(stdin.text.toByteArray(stdin.charset)) }
            } catch (t: Throwable) {
                if (kill.wasKilled()) {
                    Throwables.propagateFatalPlatformErrorsIfNeeded(t, WORKER_THROWABLE_POLICY)
                    return
                }
                if (t is java.io.IOException && !proc.isAlive) return
                kill.killOnce()
                Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
                throw ExecException(ExecError.StdinWriteFailed(meta = meta, cause = t))
            }
        }

        is JvmExecSpec.Input.Source -> {
            val inStream =
                try {
                    stdin.open().asInputStream()
                } catch (t: Throwable) {
                    if (kill.wasKilled()) {
                        Throwables.propagateFatalPlatformErrorsIfNeeded(t, WORKER_THROWABLE_POLICY)
                        return
                    }
                    kill.killOnce()
                    Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
                    throw ExecException(ExecError.InputProviderFailed(meta = meta, cause = t))
                }
            try {
                inStream.use { src ->
                    proc.outputStream.use { dst ->
                        src.copyTo(dst)
                    }
                }
            } catch (t: Throwable) {
                if (kill.wasKilled()) {
                    Throwables.propagateFatalPlatformErrorsIfNeeded(t, WORKER_THROWABLE_POLICY)
                    return
                }
                if (t is java.io.IOException && !proc.isAlive) return
                kill.killOnce()
                Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
                throw ExecException(ExecError.StdinWriteFailed(meta = meta, cause = t))
            }
        }

        is JvmExecSpec.Input.WriteTo -> {
            try {
                proc.outputStream.use { os ->
                    os.asKxSink().use { sink ->
                        try {
                            stdin.write(sink)
                            sink.flush()
                        } catch (t: Throwable) {
                            if (kill.wasKilled()) {
                                Throwables.propagateFatalPlatformErrorsIfNeeded(t, WORKER_THROWABLE_POLICY)
                                return
                            }
                            if (t is java.io.IOException && !proc.isAlive) return
                            kill.killOnce()
                            Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
                            throw ExecException(ExecError.InputProviderFailed(meta = meta, cause = t))
                        }
                    }
                }
            } catch (e: ExecException) {
                throw e
            } catch (t: Throwable) {
                if (kill.wasKilled()) {
                    Throwables.propagateFatalPlatformErrorsIfNeeded(t, WORKER_THROWABLE_POLICY)
                    return
                }
                if (t is java.io.IOException && !proc.isAlive) return
                kill.killOnce()
                Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
                throw ExecException(ExecError.StdinWriteFailed(meta = meta, cause = t))
            }
        }

        is JvmExecSpec.Input.Writer -> {
            try {
                proc.outputStream.use { os ->
                    try {
                        stdin.write(os)
                    } catch (t: Throwable) {
                        if (kill.wasKilled()) {
                            Throwables.propagateFatalPlatformErrorsIfNeeded(t, WORKER_THROWABLE_POLICY)
                            return
                        }
                        if (t is java.io.IOException && !proc.isAlive) return
                        kill.killOnce()
                        Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
                        throw ExecException(ExecError.InputProviderFailed(meta = meta, cause = t))
                    }
                }
            } catch (e: ExecException) {
                throw e
            } catch (t: Throwable) {
                if (kill.wasKilled()) {
                    Throwables.propagateFatalPlatformErrorsIfNeeded(t, WORKER_THROWABLE_POLICY)
                    return
                }
                if (t is java.io.IOException && !proc.isAlive) return
                kill.killOnce()
                Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
                throw ExecException(ExecError.StdinWriteFailed(meta = meta, cause = t))
            }
        }

        is JvmExecSpec.Input.FromPath -> {
            val inStream =
                try {
                    Files.newInputStream(stdin.path)
                } catch (t: Throwable) {
                    if (kill.wasKilled()) {
                        Throwables.propagateFatalPlatformErrorsIfNeeded(t, WORKER_THROWABLE_POLICY)
                        return
                    }
                    kill.killOnce()
                    Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
                    throw ExecException(ExecError.InputProviderFailed(meta = meta, cause = t))
                }
            try {
                inStream.use { src ->
                    proc.outputStream.use { dst ->
                        src.copyTo(dst)
                    }
                }
            } catch (t: Throwable) {
                if (kill.wasKilled()) {
                    Throwables.propagateFatalPlatformErrorsIfNeeded(t, WORKER_THROWABLE_POLICY)
                    return
                }
                if (t is java.io.IOException && !proc.isAlive) return
                kill.killOnce()
                Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
                throw ExecException(ExecError.StdinWriteFailed(meta = meta, cause = t))
            }
        }

        is JvmExecSpec.Input.FromStream -> {
            val inStream =
                try {
                    stdin.open()
                } catch (t: Throwable) {
                    if (kill.wasKilled()) {
                        Throwables.propagateFatalPlatformErrorsIfNeeded(t, WORKER_THROWABLE_POLICY)
                        return
                    }
                    kill.killOnce()
                    Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
                    throw ExecException(ExecError.InputProviderFailed(meta = meta, cause = t))
                }
            try {
                inStream.use { src ->
                    proc.outputStream.use { dst ->
                        src.copyTo(dst)
                    }
                }
            } catch (t: Throwable) {
                if (kill.wasKilled()) {
                    Throwables.propagateFatalPlatformErrorsIfNeeded(t, WORKER_THROWABLE_POLICY)
                    return
                }
                if (t is java.io.IOException && !proc.isAlive) return
                kill.killOnce()
                Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
                throw ExecException(ExecError.StdinWriteFailed(meta = meta, cause = t))
            }
        }
    }
}
