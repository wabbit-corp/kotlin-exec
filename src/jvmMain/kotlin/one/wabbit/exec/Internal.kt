@file:OptIn(PlatformSpecificExecApi::class)

package one.wabbit.exec

import one.wabbit.throwables.Throwables
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlinx.io.files.Path as KxPath

/**
 * Policy for worker threads (IO pump threads, Dispatchers.IO, virtual threads, etc).
 *
 * Rationale:
 * - Restoring the thread interrupt flag on pooled threads can "poison" the pool.
 * - At the API boundary, we still preserve interruption semantics (execBlocking restores it).
 */
internal val WORKER_THROWABLE_POLICY: Throwables.Policy =
    Throwables.Policy(restoreInterrupt = false)

/**
 * Best-effort process metadata helpers.
 *
 * Rationale:
 * Kotlin's `runCatching { ... }` catches *Throwable* (including `Error`). In exception-handling code,
 * that is an attractive nuisance: it can accidentally swallow fatal JVM errors (OOME, SOE, LinkageError)
 * and silently degrade abort-signal semantics.
 *
 * These helpers are intentionally narrow:
 * - never throw for non-fatal "best-effort" failures,
 * - but never bury fatal platform errors.
 */
internal fun pidOrNull(proc: Process): Long? =
    try {
        proc.pid()
    } catch (t: Throwable) {
        // Never swallow JVM death just because pid() failed.
        Throwables.propagateFatalPlatformErrorsIfNeeded(t, WORKER_THROWABLE_POLICY)
        null
    }

internal fun exitValueOrNull(proc: Process): Int? =
    try {
        proc.exitValue()
    } catch (_: IllegalThreadStateException) {
        // Normal: process still alive.
        null
    } catch (t: Throwable) {
        // exitValue() shouldn't throw much besides IllegalThreadStateException, but "shouldn't"
        // is not a contract. Never bury JVM death.
        Throwables.propagateFatalPlatformErrorsIfNeeded(t, WORKER_THROWABLE_POLICY)
        null
    }

/**
 * If we *swallow* an interruption-shaped exception, restore the interrupt flag.
 *
 * Only used on best-effort cleanup paths where we are explicitly choosing not to propagate.
 */
internal fun restoreInterruptFlagIfNeeded(t: Throwable) {
    when (t) {
        is InterruptedException -> Thread.currentThread().interrupt()
        is java.io.InterruptedIOException ->
            if (t !is java.net.SocketTimeoutException) Thread.currentThread().interrupt()
        is java.nio.channels.ClosedByInterruptException,
        is java.nio.channels.FileLockInterruptionException -> Thread.currentThread().interrupt()
    }
}

/** Await process exit using JDK's non-blocking onExit() bridge to coroutines. */
internal suspend fun awaitExitSuspend(proc: Process) {
    suspendCancellableCoroutine<Unit> { cont ->
        val cf = proc.onExit()
        cf.whenComplete { _, ex ->
            if (!cont.isActive) return@whenComplete
            if (ex != null) cont.resumeWithException(ex)
            else cont.resume(Unit)
        }
        // Avoid retaining cancelled continuations (especially for polling with timeouts).
        // This cancels *this* onExit future only, not the process.
        cont.invokeOnCancellation {
            runCatching { cf.cancel(false) }
        }
    }
}


internal fun newVirtualThreadPerTaskExecutorOrNull(): ExecutorService? =
    try {
        val m = Executors::class.java.getMethod("newVirtualThreadPerTaskExecutor")
        @Suppress("UNCHECKED_CAST")
        m.invoke(null) as ExecutorService
    } catch (t: Throwable) {
        Throwables.propagateIfNeeded(t)
        null
    }


internal fun newVirtualThreadPerTaskExecutorOrThrow(): ExecutorService =
    try {
        val m = Executors::class.java.getMethod("newVirtualThreadPerTaskExecutor")
        @Suppress("UNCHECKED_CAST")
        m.invoke(null) as ExecutorService
    } catch (e: Throwable) {
        Throwables.propagateIfNeeded(e)
        throw IllegalStateException("Virtual threads not available (need JDK 21+).", e)
    }


internal fun shutdownExecutor(es: ExecutorService, timeout: Duration) {
    // First: polite
    es.shutdown()
    val ms = timeout.inWholeMilliseconds.coerceAtLeast(0)
    try {
        if (ms > 0) es.awaitTermination(ms, TimeUnit.MILLISECONDS)
    } catch (ie: InterruptedException) {
        Thread.currentThread().interrupt()
    }

    // Then: realistic
    if (!es.isTerminated) {
        es.shutdownNow()
        try {
            if (ms > 0) es.awaitTermination(ms, TimeUnit.MILLISECONDS)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

internal fun isWindows(): Boolean =
    System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")

internal fun provideMinimalEnv(envMap: MutableMap<String, String>) {
    if (isWindows()) {
        envMap.putIfAbsent("SystemRoot", System.getenv("SystemRoot") ?: "C:\\Windows")
        envMap.putIfAbsent("ComSpec", System.getenv("ComSpec") ?: "C:\\Windows\\System32\\cmd.exe")
        envMap.putIfAbsent(
            "PATH",
            System.getenv("PATH") ?: "C:\\Windows\\System32;C:\\Windows"
        )
        envMap.putIfAbsent("PATHEXT", System.getenv("PATHEXT") ?: ".COM;.EXE;.BAT;.CMD")
        val tmp = System.getenv("TEMP") ?: System.getenv("TMP") ?: System.getProperty("java.io.tmpdir")
        envMap.putIfAbsent("TEMP", tmp)
        envMap.putIfAbsent("TMP", tmp)
    } else {
        envMap.putIfAbsent("PATH", "/usr/bin:/bin")
        envMap.putIfAbsent("TMPDIR", System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"))
    }
}

internal fun applyEnv(pb: ProcessBuilder, policy: EnvPolicy) {
    val env = pb.environment()
    when (policy) {
        is EnvPolicy.Inherit -> {
            if (policy.overlay.isNotEmpty()) env.putAll(policy.overlay)
        }
        is EnvPolicy.Hermetic -> {
            env.clear()
            provideMinimalEnv(env)
            env.putAll(policy.base)
        }
        is EnvPolicy.ClearAndSet -> {
            env.clear()
            env.putAll(policy.base)
        }
    }
}

/**
 * A sink can be specialized to a ProcessBuilder redirect only when it is a simple unbounded file sink.
 * If it has a maxBytes bound we must pump through FileSink to enforce disk safety.
 */
internal fun isSpecializableRedirect(sink: JvmExecSpec.SinkSpec): Boolean =
    sink is JvmExecSpec.SinkSpec.File &&
        sink.maxBytes == null &&
        sink.write.eager

internal fun stdoutNeedsPipe(stdout: JvmExecSpec.StdoutSpec): Boolean =
    when (stdout) {
        JvmExecSpec.StdoutSpec.Inherit, JvmExecSpec.StdoutSpec.Discard -> false
        is JvmExecSpec.StdoutSpec.Pipe -> !isSpecializableRedirect(stdout.sink)
    }

internal fun stderrNeedsPipe(stderr: JvmExecSpec.StderrSpec): Boolean =
    when (stderr) {
        JvmExecSpec.StderrSpec.Inherit,
        JvmExecSpec.StderrSpec.Discard,
        JvmExecSpec.StderrSpec.ToStdout -> false
        is JvmExecSpec.StderrSpec.Pipe -> !isSpecializableRedirect(stderr.sink)
    }

internal fun stdinNeedsTask(stdin: JvmExecSpec.Input): Boolean =
    when (stdin) {
        JvmExecSpec.Input.None, JvmExecSpec.Input.Inherit -> false
        else -> true
    }

/** Best-effort required parallelism for I/O pump tasks (stdout/stderr/stdin). */
internal fun requiredIoParallelism(spec: JvmExecSpec): Int =
    (if (stdoutNeedsPipe(spec.stdout)) 1 else 0) +
        (if (stderrNeedsPipe(spec.stderr)) 1 else 0) +
        (if (stdinNeedsTask(spec.stdin)) 1 else 0)

/**
 * Catch obvious executor foot-guns (especially fixed/single thread pools) before we spawn anything.
 * This is intentionally conservative: if we can't introspect, we just don't pretend we can.
 */
internal fun requireExecutorParallelism(executor: Executor, required: Int) {
    if (required <= 1) return
    when (executor) {
        is ThreadPoolExecutor -> {
            val max = executor.maximumPoolSize
            val core = executor.corePoolSize
            // With unbounded queues, maximumPoolSize is effectively ignored (tasks just queue).
            // Best-effort heuristic: remainingCapacity == Int.MAX_VALUE strongly suggests unbounded.
            val unboundedQueue =
                runCatching { executor.queue.remainingCapacity() == Int.MAX_VALUE }.getOrDefault(false)
            require(max >= required) {
                "taskExecutor maximumPoolSize=$max < requiredParallelism=$required; " +
                    "this can deadlock process I/O (stdout/stderr pumping needs concurrency)."
            }
            if (unboundedQueue) {
                require(core >= required) {
                    "taskExecutor corePoolSize=$core < requiredParallelism=$required with an unbounded queue; " +
                        "this can deadlock process I/O even if maximumPoolSize=$max."
                }
            }
        }
        is ForkJoinPool -> {
            val p = executor.parallelism
            require(p >= required) {
                "taskExecutor ForkJoinPool parallelism=$p < requiredParallelism=$required; " +
                    "this can deadlock process I/O."
            }
        }
        else -> {
            // Unknown executor type; documented requirement applies.
        }
    }
}

internal fun closeQuietly(vararg c: Any?) {
    for (x in c) {
        try {
            when (x) {
                is Closeable -> x.close()
                is AutoCloseable -> x.close()
                is java.io.InputStream -> x.close()
                is java.io.OutputStream -> x.close()
            }
        } catch (t: Throwable) {
            // Cleanup must not mask the primary failure, but must not bury JVM death either.
            restoreInterruptFlagIfNeeded(t)
            Throwables.propagateFatalPlatformErrorsIfNeeded(t)
        }
    }
}


/**
 * Worker-thread variant: do NOT restore interrupt status.
 *
 * Rationale:
 * - Dispatchers.IO and other pools reuse threads.
 * - Restoring interrupts on pooled threads can poison unrelated work.
 */
internal fun closeQuietlyWorker(vararg c: Any?) {
    for (x in c) {
        try {
            when (x) {
                is Closeable -> x.close()
                is AutoCloseable -> x.close()
                is java.io.InputStream -> x.close()
                is java.io.OutputStream -> x.close()
            }
        } catch (t: Throwable) {
            Throwables.propagateFatalPlatformErrorsIfNeeded(t, WORKER_THROWABLE_POLICY)
        }
    }
}


internal fun killProcessTree(proc: Process) {
    try {
        val h = proc.toHandle()
        // descendants first
        try {
            h.descendants().forEach { ph ->
                try { ph.destroyForcibly() } catch (t: Throwable) {
                    restoreInterruptFlagIfNeeded(t)
                    Throwables.propagateFatalPlatformErrorsIfNeeded(t)
                }
            }
        } catch (t: Throwable) {
            restoreInterruptFlagIfNeeded(t)
            Throwables.propagateFatalPlatformErrorsIfNeeded(t)
        }
        try { h.destroyForcibly() } catch (t: Throwable) {
            restoreInterruptFlagIfNeeded(t)
            Throwables.propagateFatalPlatformErrorsIfNeeded(t)
        }
    } catch (t: Throwable) {
        restoreInterruptFlagIfNeeded(t)
        Throwables.propagateFatalPlatformErrorsIfNeeded(t)
        try { proc.destroyForcibly() } catch (t2: Throwable) {
            restoreInterruptFlagIfNeeded(t2)
            Throwables.propagateFatalPlatformErrorsIfNeeded(t2)
        }
    }
}

internal fun terminateProcessTree(proc: Process) {
    try {
        val h = proc.toHandle()
        try {
            h.descendants().forEach { ph ->
                try { ph.destroy() } catch (t: Throwable) {
                    restoreInterruptFlagIfNeeded(t)
                    Throwables.propagateFatalPlatformErrorsIfNeeded(t)
                }
            }
        } catch (t: Throwable) {
            restoreInterruptFlagIfNeeded(t)
            Throwables.propagateFatalPlatformErrorsIfNeeded(t)
        }
        try { h.destroy() } catch (t: Throwable) {
            restoreInterruptFlagIfNeeded(t)
            Throwables.propagateFatalPlatformErrorsIfNeeded(t)
        }
    } catch (t: Throwable) {
        restoreInterruptFlagIfNeeded(t)
        Throwables.propagateFatalPlatformErrorsIfNeeded(t)
        try { proc.destroy() } catch (t2: Throwable) {
            restoreInterruptFlagIfNeeded(t2)
            Throwables.propagateFatalPlatformErrorsIfNeeded(t2)
        }
    }
}

internal data class Deadline(val deadlineNanos: Long) {
    fun remainingMillis(): Long {
        val rem = deadlineNanos - System.nanoTime()
        return if (rem <= 0L) 0L else TimeUnit.NANOSECONDS.toMillis(rem).coerceAtLeast(1L)
    }

    fun expired(): Boolean = System.nanoTime() >= deadlineNanos

    companion object {
        fun from(timeout: Duration): Deadline {
            val nanos = timeout.inWholeNanoseconds.coerceAtLeast(0)
            return Deadline(System.nanoTime() + nanos)
        }
    }
}

internal fun launchFutureTask(executor: Executor?, name: String, task: FutureTask<Unit>) {
    if (executor != null) {
        executor.execute(task)
    } else {
        Thread(task, name).apply {
            isDaemon = true
            start()
        }
    }
}

internal data class AwaitAllResult(
    val ok: Boolean,
    val interrupted: InterruptedException? = null,
)

internal fun awaitAllWithin(tasks: List<FutureTask<Unit>>, cleanup: Deadline): AwaitAllResult {
    for (t in tasks) {
        val rem = cleanup.remainingMillis()
        if (rem <= 0L) return AwaitAllResult(false)
        try {
            t.get(rem, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            return AwaitAllResult(false)
        } catch (_: ExecutionException) {
            // completed (failed) - still counts as "done"
        } catch (_: CancellationException) {
            // completed (cancelled) - still counts as "done"
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            return AwaitAllResult(false, ie)
        }
    }
    return AwaitAllResult(true)
}

internal fun joinAllWithin(threads: List<Thread>, cleanup: Deadline): Boolean {
    for (t in threads) {
        if (!t.isAlive) continue
        val rem = cleanup.remainingMillis()
        if (rem <= 0L) return false
        try { t.join(rem) } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        if (t.isAlive) return false
    }
    return true
}

internal fun fileOf(path: java.nio.file.Path): File = path.toFile()

internal fun nioPathOf(path: KxPath): java.nio.file.Path = java.nio.file.Path.of(path.toString())

internal class KillSwitch(
    private val proc: Process,
    private val closeStdinOnKill: Boolean,
    private val shutdown: ShutdownPolicy,
) {
    private val killed = AtomicBoolean(false)

    fun killOnce() {
        if (killed.compareAndSet(false, true)) {
            when (val p = shutdown) {
                is ShutdownPolicy.KillTree -> {
                    killProcessTree(proc)
                    // Closing streams helps unblock readers immediately in hard-kill mode.
                    closeQuietlyWorker(proc.inputStream, proc.errorStream)
                }
                is ShutdownPolicy.TerminateThenKillTree -> {
                    terminateProcessTree(proc)
                    val graceMs = p.grace.inWholeMilliseconds
                    if (graceMs <= 0L) {
                        killProcessTree(proc)
                        closeQuietlyWorker(proc.inputStream, proc.errorStream)
                    } else {
                        val pid = runCatching { proc.pid() }.getOrNull()
                        Thread({
                            try { Thread.sleep(graceMs) } catch (_: InterruptedException) {}
                            if (proc.isAlive) {
                                killProcessTree(proc)
                            }
                            // Only close stdout/stderr after escalation (or after kill) to avoid sabotaging
                            // graceful termination output (broken pipes / SIGPIPE / truncated captures).
                            closeQuietlyWorker(proc.inputStream, proc.errorStream)
                        }, "proc-kill-escalate-${pid ?: "?"}").apply {
                            isDaemon = true
                            start()
                        }
                    }
                }
            }
            if (closeStdinOnKill) closeQuietlyWorker(proc.outputStream)
        }
    }

    fun wasKilled(): Boolean = killed.get()
}
