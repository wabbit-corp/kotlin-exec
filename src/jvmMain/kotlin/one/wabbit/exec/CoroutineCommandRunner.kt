@file:OptIn(PlatformSpecificExecApi::class, InternalCoroutinesApi::class)

package one.wabbit.exec

import one.wabbit.throwables.Throwables
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import kotlin.time.Duration.Companion.milliseconds
import java.util.concurrent.TimeUnit

/**
 * Low-level JVM coroutine execution engine for [JvmExecSpec].
 *
 * This function is public for historical compatibility with early JVM callers, but normal code
 * should prefer [Exec.exec]. It owns the child lifecycle, pumps requested I/O on [ioDispatcher],
 * kills the process tree on timeout or cancellation, and reports structured failures as
 * [ExecException].
 *
 * @param spec complete JVM execution specification.
 * @param ioDispatcher dispatcher used for blocking process and I/O work.
 * @return completed process result.
 * @throws ExecException for structured execution failures.
 */
@PlatformSpecificExecApi
@Throws(ExecException::class)
suspend fun execInternal(
    spec: JvmExecSpec,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): ExecResult = coroutineScope {
    val startNanos = System.nanoTime()
    val baseMeta = ExecResult.Meta(argv = spec.argv, pid = null)

    validateSpecOrThrow(spec, baseMeta)
    // Cancellation is not preemptive; make it win early to avoid spawning work when already cancelled.
    currentCoroutineContext().ensureActive()

    val pb = try {
        buildProcessBuilder(spec)
    } catch (t: Throwable) {
        Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
        throw ExecException(ExecError.ConfigureFailed(meta = baseMeta, cause = t))
    }

    // Spawn in IO context, but guard against cancellation races that can orphan a started process.
    val proc =
        withContext(ioDispatcher) {
            // If we were already cancelled, fail fast before spawning.
            currentCoroutineContext().ensureActive()

            val p =
                try {
                    pb.start()
                } catch (t: Throwable) {
                    Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
                    throw ExecException(ExecError.SpawnFailed(meta = baseMeta, cause = t))
                }

            // Critical: if cancellation happened during/around spawn, kill the process we just started.
            try {
                currentCoroutineContext().ensureActive()
                p
            } catch (ce: CancellationException) {
                killProcessTree(p)
                closeQuietly(p.inputStream, p.errorStream, p.outputStream)
                throw ce
            }
        }

    val meta = baseMeta.copy(pid = pidOrNull(proc))
    val kill = KillSwitch(proc, closeStdinOnKill = spec.stdin !is JvmExecSpec.Input.Inherit, shutdown = spec.shutdown)

    // Second guard: cancellation in the tiny window between returning from withContext and installing hooks.
    try {
        currentCoroutineContext().ensureActive()
    } catch (ce: CancellationException) {
        kill.killOnce()
        throw ce
    }

    // Cancellation hook: cancel => kill tree.
    val parentJob = currentCoroutineContext()[Job]
    val cancelHandle = parentJob?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause != null) kill.killOnce()
    }

    // Setup sinks. Sink creation can throw (e.g. eager File sinks).
    var stdoutSink: Sink? = null
    var stderrSink: Sink? = null
    (spec.stdout as? JvmExecSpec.StdoutSpec.Pipe)
        ?.takeIf { stdoutNeedsPipe(spec.stdout) }
        ?.let { pipe ->
            try {
                stdoutSink = sinkFor(pipe.sink, meta, StreamId.STDOUT)
            } catch (t: Throwable) {
                runCatching { stdoutSink?.close() }
                runCatching { stderrSink?.close() }
                kill.killOnce()
                // If cancellation is already pending, let it win (but only after we've ensured the
                // process won't be orphaned).
                currentCoroutineContext().ensureActive()
                Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
                throw ExecException(ExecError.OutputSinkFailed(meta = meta, stream = StreamId.STDOUT, cause = t))
            }
        }
    (spec.stderr as? JvmExecSpec.StderrSpec.Pipe)
        ?.takeIf { stderrNeedsPipe(spec.stderr) }
        ?.let { pipe ->
            try {
                stderrSink = sinkFor(pipe.sink, meta, StreamId.STDERR)
            } catch (t: Throwable) {
                runCatching { stdoutSink?.close() }
                runCatching { stderrSink?.close() }
                kill.killOnce()
                // If cancellation is already pending, let it win (but only after we've ensured the
                // process won't be orphaned).
                currentCoroutineContext().ensureActive()
                Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
                throw ExecException(ExecError.OutputSinkFailed(meta = meta, stream = StreamId.STDERR, cause = t))
            }
        }

    // IMPORTANT:
    // Pump tasks must NOT throw (or they will cancel this coroutineScope and we lose captures).
    // Instead they return TaskOutcome(error?) and we throw only after sinks are finished.
    val stdoutJob =
        stdoutSink?.let { sink ->
            async(ioDispatcher) { TaskOutcome(pumpStreamOutcome(proc.inputStream, sink, meta, StreamId.STDOUT, kill)) }
        }
    val stderrJob =
        stderrSink?.let { sink ->
            async(ioDispatcher) { TaskOutcome(pumpStreamOutcome(proc.errorStream, sink, meta, StreamId.STDERR, kill)) }
        }
    val stdinJob =
        if (stdinNeedsTask(spec.stdin)) {
            async(ioDispatcher) { TaskOutcome(writeStdinOutcome(proc, spec.stdin, meta, kill)) }
        } else null

    when (spec.stdin) {
        JvmExecSpec.Input.None -> closeQuietly(proc.outputStream)
        JvmExecSpec.Input.Inherit -> {}
        else -> {}
    }

    var timedOut = false

    try {
        val exited: Boolean =
            try {
                if (spec.timeout != null) {
                    withTimeoutOrNull(spec.timeout) { awaitExitSuspend(proc) } != null
                } else {
                    awaitExitSuspend(proc); true
                }
            } catch (ce: CancellationException) {
                // ensure kill happens (hook should do it, but be explicit)
                kill.killOnce()
                throw ce
            } catch (t: Throwable) {
                kill.killOnce()
                Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
                throw ExecException(ExecError.WaitFailed(meta = meta, cause = t))
            }

        if (!exited) {
            timedOut = true
            kill.killOnce()
        }

        // Cleanup/join with explicit budget.
        val cleanupDeadline = Deadline.from(spec.cleanupTimeout)

        val jobs = listOfNotNull(stdoutJob, stderrJob, stdinJob)
        var joinedAll: Boolean = true
        var pumpError: ExecError? = null

        fun recordFailure(t: Throwable) {
            if (pumpError != null) return
            pumpError =
                when (t) {
                    is ExecException -> t.error
                    else -> ExecError.Unexpected(meta = meta, phase = Phase.Cleanup, cause = t)
                }
        }

        suspend fun awaitJobsOnce(): Boolean {
            var ok = true
            for (j in jobs) {
                val rem = cleanupDeadline.remainingMillis()
                if (rem <= 0L) return false
                try {
                    val out: TaskOutcome? = withTimeoutOrNull(rem) { j.await() }
                    if (out == null) {
                        ok = false
                    } else if (pumpError == null && out.error != null) {
                        pumpError = out.error
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) {
                        kill.killOnce()
                        throw t
                    }
                    kill.killOnce()
                    Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
                    recordFailure(t)
                }
            }
            return ok
        }

        // First await; if it doesn't complete, killOnce() closes streams and we retry within the same deadline.
        val firstOk = awaitJobsOnce()
        if (!firstOk || pumpError != null) kill.killOnce()
        val secondOk = if (firstOk) true else awaitJobsOnce()
        joinedAll = secondOk

        // Only finish sinks once pump jobs are definitely done (avoid data races).
        // IMPORTANT:
        // finish() is non-suspending and can be expensive (Capture does large byte-array copies).
        // If we do it on the caller dispatcher (runBlocking/Main), we can starve sibling coroutines
        // that are trying to cancel us, letting ExitNonZero win purely due to scheduling.
        val (stdoutFinished, stderrFinished) =
            if (joinedAll) {
                withContext(ioDispatcher) {
                    stdoutSink?.finish() to stderrSink?.finish()
                }
            } else {
                null to null
            }
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

        currentCoroutineContext().ensureActive()

        val exitAfterKill: Int? =
            if (proc.isAlive) {
                val rem = cleanupDeadline.remainingMillis()
                if (rem > 0L) {
                    val done = withTimeoutOrNull(rem) { awaitExitSuspend(proc); true } ?: false
                    if (done) exitValueOrNull(proc) else null
                } else null
            } else {
                exitValueOrNull(proc)
            }

        // Match blocking runner precedence: if a pump caused failure (overflow/sink/consumer), report that.
        if (pumpError != null) {
            currentCoroutineContext().ensureActive()
            throw ExecException(pumpError.copyWithCaptures(captures))
        }

        if (timedOut) {
            currentCoroutineContext().ensureActive()
            throw ExecException(
                ExecError.TimedOut(
                    meta = meta,
                    timeoutMs = spec.timeout?.inWholeMilliseconds ?: 0L,
                    exitCodeAfterKill = exitAfterKill,
                    captures = captures,
                ),
            )
        }

        if (!joinedAll) {
            currentCoroutineContext().ensureActive()
            throw ExecException(
                ExecError.CleanupFailed(
                    meta = meta,
                    cause = null,
                    message = "Cleanup exceeded ${spec.cleanupTimeout.inWholeMilliseconds}ms (tasks did not finish)",
                    captures = captures,
                ),
            )
        }

        if (finishError != null) {
            currentCoroutineContext().ensureActive()
            throw ExecException(
                finishError.copyWithCaptures(captures),
            )
        }

        val exit = exitValueOrNull(proc) ?: exitAfterKill ?: -1
        val durMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
        val result =
            ExecResult(
                meta = meta,
                exitCode = ExitCode(exit),
                stdout = stdoutCap,
                stderr = stderrCap,
                stdoutStats = stdoutStats,
                stderrStats = stderrStats,
                duration = durMs.milliseconds,
            )

        if (spec.exitPolicy == ExitPolicy.ThrowOnNonZero && exit != 0) {
            currentCoroutineContext().ensureActive()
            throw ExecException(
                ExecError.ExitNonZero(
                    meta = meta,
                    exitCode = exit,
                    captures = captures,
                ),
            )
        }

        currentCoroutineContext().ensureActive()
        return@coroutineScope result
    } finally {
        cancelHandle?.dispose()
        stdoutSink?.close()
        stderrSink?.close()
    }
}

private data class TaskOutcome(val error: ExecError?)

private fun phaseForPump(stream: StreamId): Phase =
    when (stream) {
        StreamId.STDOUT -> Phase.ReadStdout
        StreamId.STDERR -> Phase.ReadStderr
        StreamId.STDIN -> Phase.WriteStdin
    }

private fun pumpStreamOutcome(
    ins: InputStream,
    sink: Sink,
    meta: ExecResult.Meta,
    stream: StreamId,
    kill: KillSwitch,
): ExecError? =
    try {
        pumpStream(ins, sink, meta, stream, kill)
        null
    } catch (ce: CancellationException) {
        // Preserve structured cancellation semantics.
        throw ce
    } catch (t: Throwable) {
        // Pump failures should not leave a process running forever.
        kill.killOnce()
        Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
        when (t) {
            is ExecException -> t.error
            else -> ExecError.Unexpected(meta = meta, phase = phaseForPump(stream), cause = t)
        }
    }

private fun writeStdinOutcome(
    proc: Process,
    stdin: JvmExecSpec.Input,
    meta: ExecResult.Meta,
    kill: KillSwitch,
): ExecError? =
    try {
        writeStdin(proc, stdin, meta, kill)
        null
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        kill.killOnce()
        Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
        when (t) {
            is ExecException -> t.error
            else -> ExecError.Unexpected(meta = meta, phase = Phase.WriteStdin, cause = t)
        }
    }
