@file:OptIn(PlatformSpecificExecApi::class)

package one.wabbit.exec

import one.wabbit.throwables.Throwables
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.time.Duration


class RunningProcessImpl internal constructor(
    val meta: ExecResult.Meta,
    private val process: Process,
    private val kill: KillSwitch,
): JvmRunningProcess {
    override val pid: Long? get() = meta.pid
    override val rawProcess: Process get() = process

    override fun isAlive(): Boolean = process.isAlive

    override fun exitCodeOrNull(): ExitCode? =
        if (process.isAlive) null else exitValueOrNull(process)?.let { ExitCode(it) }

    /** Kill the process tree using the configured [JvmSpawnSpec.shutdown]. Idempotent. */
    override fun killTree() {
        kill.killOnce()
    }

    /**
     * Wait for exit; returns a richer outcome (distinguishes timeout vs interrupt).
     * Does NOT kill on timeout/interrupt.
     */
    override fun awaitExitBlockingOutcome(timeout: Duration?): AwaitExitOutcome =
        try {
            val finished =
                if (timeout != null) {
                    process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                } else {
                    process.waitFor()
                    true
                }
            if (!finished) AwaitExitOutcome.TimedOut
            else AwaitExitOutcome.Exited(ExitCode(process.exitValue()))
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            // Do not kill in spawn/detach mode.
            if (!process.isAlive) {
                AwaitExitOutcome.Exited(ExitCode(exitValueOrNull(process) ?: -1))
            } else {
                AwaitExitOutcome.Interrupted
            }
        }

    /** Wait for exit; returns null if still running after [timeout]. Does NOT kill on timeout/interrupt. */
    override fun awaitExitBlocking(timeout: Duration?): ExitCode? =
        when (val o = awaitExitBlockingOutcome(timeout)) {
            is AwaitExitOutcome.Exited -> o.code
            AwaitExitOutcome.TimedOut, AwaitExitOutcome.Interrupted -> null
        }

    /** Wait for exit; returns null if still running after [timeout]. Does NOT kill on timeout. */
    override suspend fun awaitExitOutcome(timeout: Duration?): AwaitExitOutcome {
        val done =
            if (timeout != null) {
                withTimeoutOrNull(timeout) { awaitExitSuspend(process); true } ?: false
            } else {
                awaitExitSuspend(process); true
            }
        return if (!done) AwaitExitOutcome.TimedOut
        else AwaitExitOutcome.Exited(ExitCode(exitValueOrNull(process) ?: -1))
        // Note: coroutine cancellation propagates (no outcome).
    }

    /** Wait for exit; returns null if still running after [timeout]. Cancellation propagates. */
    override suspend fun awaitExit(timeout: Duration?): ExitCode? =
        when (val o = awaitExitOutcome(timeout)) {
            is AwaitExitOutcome.Exited -> o.code
            AwaitExitOutcome.TimedOut, AwaitExitOutcome.Interrupted -> null
        }
}

@Throws(ExecException::class)
internal fun spawnBlockingInternal(spec: JvmSpawnSpec): RunningProcessImpl {
    val baseMeta = ExecResult.Meta(argv = spec.argv, pid = null)
    validateSpawnSpecOrThrow(spec)

    val pb =
        try {
            buildProcessBuilder(spec)
        } catch (t: Throwable) {
            Throwables.propagateIfNeeded(t)
            throw ExecException(ExecError.ConfigureFailed(meta = baseMeta, cause = t))
        }

    val proc =
        try {
            pb.start()
        } catch (t: Throwable) {
            Throwables.propagateIfNeeded(t)
            throw ExecException(ExecError.SpawnFailed(meta = baseMeta, cause = t))
        }

    val meta = baseMeta.copy(pid = pidOrNull(proc))
    val kill = KillSwitch(proc, closeStdinOnKill = spec.stdin != JvmSpawnSpec.Input.Inherit, shutdown = spec.shutdown)

    // If we didn't inherit stdin, close it immediately to avoid children hanging on stdin reads.
    if (spec.stdin == JvmSpawnSpec.Input.None) closeQuietly(proc.outputStream)

    return RunningProcessImpl(meta = meta, process = proc, kill = kill)
}

@Throws(ExecException::class)
internal suspend fun spawnInternal(
    spec: JvmSpawnSpec,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): RunningProcessImpl {
    val baseMeta = ExecResult.Meta(argv = spec.argv, pid = null)
    validateSpawnSpecOrThrow(spec)

    // If we're already cancelled, fail fast before doing any work that could spawn.
    currentCoroutineContext().ensureActive()

    val pb =
        try {
            buildProcessBuilder(spec)
        } catch (t: Throwable) {
            // Must not wrap FATAL/abort/control-flow/bugs into ExecException.
            Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
            throw ExecException(ExecError.ConfigureFailed(meta = baseMeta, cause = t))
        }

    // Spawn in IO context, but guard against the cancellation race that can orphan a started process:
    // cancellation can happen during/around pb.start(), and withContext may throw CancellationException
    // *after* the process is created but *before* we return its handle.
    val proc =
        withContext(ioDispatcher) {
            currentCoroutineContext().ensureActive()

            val p =
                try {
                    pb.start()
                } catch (t: Throwable) {
                    Throwables.propagateIfNeeded(t, WORKER_THROWABLE_POLICY)
                    throw ExecException(ExecError.SpawnFailed(meta = baseMeta, cause = t))
                }

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
    val kill = KillSwitch(proc, closeStdinOnKill = spec.stdin != JvmSpawnSpec.Input.Inherit, shutdown = spec.shutdown)

    try {
        if (spec.stdin == JvmSpawnSpec.Input.None) closeQuietly(proc.outputStream)
        // If caller cancels mid-spawn, don't leak an orphaned process.
        currentCoroutineContext().ensureActive()
        return RunningProcessImpl(meta = meta, process = proc, kill = kill)
    } catch (ce: CancellationException) {
        kill.killOnce()
        throw ce
    }
}

internal fun validateSpawnSpecOrThrow(spec: JvmSpawnSpec) {
    require(spec.argv.isNotEmpty()) { "argv must not be empty" }
    when (val s = spec.shutdown) {
        is ShutdownPolicy.KillTree -> {}
        is ShutdownPolicy.TerminateThenKillTree -> {
            require(s.grace.isFinite()) { "shutdown.grace must be finite" }
            require(!s.grace.isNegative()) { "shutdown.grace must be >= 0" }
        }
    }
}

internal fun buildProcessBuilder(spec: JvmSpawnSpec): ProcessBuilder {
    val pb = ProcessBuilder(spec.argv)
    spec.cwd?.let { pb.directory(fileOf(it)) }
    applyEnv(pb, spec.env)

    // stdin
    when (spec.stdin) {
        JvmSpawnSpec.Input.Inherit -> pb.redirectInput(ProcessBuilder.Redirect.INHERIT)
        JvmSpawnSpec.Input.None -> pb.redirectInput(ProcessBuilder.Redirect.PIPE)
    }

    // stdout
    when (val o = spec.stdout) {
        JvmSpawnSpec.StdoutSpec.Inherit -> pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
        JvmSpawnSpec.StdoutSpec.Discard -> pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        is JvmSpawnSpec.StdoutSpec.File -> {
            val f = fileOf(o.path)
            pb.redirectOutput(if (o.append) ProcessBuilder.Redirect.appendTo(f) else ProcessBuilder.Redirect.to(f))
        }
    }

    // stderr / merge
    when (val e = spec.stderr) {
        JvmSpawnSpec.StderrSpec.ToStdout -> {
            pb.redirectErrorStream(true)
        }

        JvmSpawnSpec.StderrSpec.Inherit -> {
            pb.redirectErrorStream(false)
            pb.redirectError(ProcessBuilder.Redirect.INHERIT)
        }

        JvmSpawnSpec.StderrSpec.Discard -> {
            pb.redirectErrorStream(false)
            pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        }

        is JvmSpawnSpec.StderrSpec.File -> {
            pb.redirectErrorStream(false)
            val f = fileOf(e.path)
            pb.redirectError(if (e.append) ProcessBuilder.Redirect.appendTo(f) else ProcessBuilder.Redirect.to(f))
        }
    }

    return pb
}
