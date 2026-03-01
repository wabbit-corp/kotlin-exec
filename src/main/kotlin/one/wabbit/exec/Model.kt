package one.wabbit.exec

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

sealed interface VirtualThreadsPolicy {
    data object Never : VirtualThreadsPolicy
    data object Prefer : VirtualThreadsPolicy
    data object Require : VirtualThreadsPolicy
}

@JvmInline value class ExitCode(val value: Int)

enum class ExitPolicy {
    /** Always return ExecResult (even if exit != 0). */
    Return,

    /** Throw ExecException(ExecError.ExitNonZero) if exit != 0. */
    ThrowOnNonZero,
}

/**
 * Environment policy.
 *
 * Note: Hermetic provides minimal OS defaults (PATH / SystemRoot / ComSpec / PATHEXT / TEMP / TMP)
 * and then overlays `base`.
 *
 * Rationale:
 * - Inherit is convenient for "normal app" usage.
 * - Hermetic is useful for tooling / sandbox-ish execution where ambient environment leaks cause
 *   surprising behavior (PATH hijacking, locale differences, etc.).
 * - ClearAndSet is mostly for tests or extreme cases; it can break process startup on Windows if
 *   you omit critical variables.
 */
sealed interface EnvPolicy {
    /**
     * Start from the parent environment, then apply overlay additions/replacements.
     * overlay empty => plain inheritance.
     */
    data class Inherit(val overlay: Map<String, String> = emptyMap()) : EnvPolicy

    /** Clear env, add minimal OS defaults, then apply base. */
    data class Hermetic(val base: Map<String, String> = emptyMap()) : EnvPolicy

    /** Clear env entirely, then apply base. */
    data class ClearAndSet(val base: Map<String, String> = emptyMap()) : EnvPolicy
}

/**
 * Shutdown policy for cancellation / timeouts / output-limit kills.
 *
 * Note: Process.destroy() is not truly "graceful" on all OSes (Windows especially). Best-effort.
 *
 * Rationale:
 * - Some tools handle SIGTERM/terminate nicely and can flush partial output.
 * - Others need the hammer immediately.
 */
sealed interface ShutdownPolicy {
    /** Immediate destroyForcibly() for the process tree. */
    data object KillTree : ShutdownPolicy

    /** destroy() the process tree, then after grace destroyForcibly() if still alive. */
    data class TerminateThenKillTree(val grace: Duration = 500.milliseconds) : ShutdownPolicy
}

/**
 * Policy for writing process output to a file.
 *
 * This controls:
 *  - whether we append vs truncate, and
 *  - whether the file is opened eagerly (at exec start) or lazily (only if at least 1 byte is written).
 *
 * Default is eager truncate, to avoid stale files when the child produces no output.
 */
sealed interface FileWritePolicy {
    /** If true, open/create/truncate/append at exec start. If false, open only on first write. */
    val eager: Boolean

    /** Create if needed and truncate if exists. */
    data class Truncate(override val eager: Boolean = true) : FileWritePolicy

    /** Create if needed and append if exists. */
    data class Append(override val eager: Boolean = true) : FileWritePolicy
}

data class ExecSpec(
    val argv: List<String>,

    val cwd: Path? = null,
    val env: EnvPolicy = defaultEnvPolicy,

    val stdin: Input = defaultStdinSpec,
    val stdout: StdoutSpec = defaultStdoutSpec,
    val stderr: StderrSpec = defaultStderrSpec,

    /**
     * Execution timeout. Null = no timeout (caller owns the consequences).
     *
     * Important:
     * Timeout/cancellation initiates shutdown according to [shutdown], then waits up to [cleanupTimeout].
     * This is best-effort: the process may still be alive when this function returns if the OS refuses
     * to kill it, or if cleanup exceeds budget. If you need faster termination, prefer [ShutdownPolicy.KillTree]
     * with a reasonable [cleanupTimeout].
     *
     * Timeout semantics:
     * When [timeout] is exceeded, shutdown is initiated according to [shutdown] (terminate/kill).
     * We then wait up to [cleanupTimeout] for pump tasks to finish and for the process to exit.
     *
     * Note: total wall-clock time may exceed [timeout] by up to [cleanupTimeout] (and by up to the
     * shutdown grace period if using [ShutdownPolicy.TerminateThenKillTree]).
     */
    val timeout: Duration? = null,

    /** Cancellation/timeout/output-limit kill behavior. Defaults to "try terminate, then kill". */
    val shutdown: ShutdownPolicy = defaultShutdownPolicy,

    /**
     * Cleanup budget after shutdown/cancel/timeout (joining pumpers, closing sinks, etc).
     *
     * Must be > 0.
     */
    val cleanupTimeout: Duration = defaultCleanupTimeout,

    /** Exit handling policy. */
    val exitPolicy: ExitPolicy = defaultExitPolicy,
) {
    /**
     * Stdin configuration.
     *
     * WARNING: If you use Inherit, the child's stdin is connected to the parent's stdin.
     * Do not close process.outputStream in that mode.
     */
    sealed interface Input {
        /** Close stdin immediately (EOF). */
        data object None : Input

        /** Inherit parent's stdin. */
        data object Inherit : Input

        /** Write the provided bytes to stdin, then close. */
        data class Bytes(val bytes: ByteArray) : Input

        /** Write the provided text to stdin (with charset), then close. */
        data class Text(
            val text: String,
            val charset: Charset = StandardCharsets.UTF_8,
        ) : Input

        /**
         * Advanced: caller provides a writer. Library closes stdin when the writer returns.
         * If writer throws, the process is killed and the exec fails.
         */
        data class Writer(val write: (java.io.OutputStream) -> Unit) : Input

        /** Stream a file into stdin, then close. */
        data class FromPath(val path: Path) : Input

        /** Stream an InputStream into stdin, then close. */
        data class FromStream(val open: () -> InputStream) : Input
    }

    enum class OverflowPolicy {
        /**
         * Keep draining; only store/write up to the configured limit.
         *
         * Rationale: continuing to drain avoids pipe backpressure deadlocks even when we stop retaining
         * bytes in memory (or stop writing to a bounded file sink).
         */
        DrainAndTruncate,

        /**
         * Kill process tree immediately when the configured limit is exceeded.
         *
         * Rationale: hard safety guardrail for runaway output (especially useful for preventing disk
         * fill with file sinks).
         */
        KillProcess,
    }

    enum class Keep {
        /** Keep the first N bytes (head). */
        Head,

        /** Keep the last N bytes (tail). */
        Tail,
    }

    /**
     * Pipe sink algebra (composable).
     *
     * All Pipe sinks execute on the reader/pump path. If a sink is slow (especially Stream callbacks),
     * it can apply backpressure and stall the child process. See SinkSpec.Stream docs.
     */
    sealed interface SinkSpec {
        data class Capture(
            val maxBytes: Int = 4 * 1024 * 1024,
            val keep: Keep = Keep.Head,
            val overflow: OverflowPolicy = OverflowPolicy.DrainAndTruncate,
        ) : SinkSpec

        /**
         * Synchronous streaming callback sink.
         *
         * WARNING:
         * This callback runs on the pump thread/coroutine. If it blocks (slow disk, locks, logging
         * backpressure, network, etc) it can stall pumping, fill OS pipes, and deadlock the subprocess.
         *
         * If you need async delivery, implement your own buffering (bounded queue) or wait for:
         *   TODO(one.wabbit.exec): SinkSpec.StreamAsync (bounded queue + overflow policy).
         */
        data class Stream(
            val onChunk: (ByteArray, Int, Int) -> Unit,
            val copyChunks: Boolean = false,
            val maxBytes: Int? = null,
            val overflow: OverflowPolicy = OverflowPolicy.DrainAndTruncate,
        ) : SinkSpec

        /**
         * Write bytes to a file.
         *
         * Note: when used as the *only* sink (StdoutSpec.Pipe(File(...)) / StderrSpec.Pipe(File(...))),
         * the runner may specialize this to a ProcessBuilder Redirect (no pump threads).
         *
         * Disk safety:
         * If [maxBytes] is set, the file sink becomes bounded:
         * - overflow=DrainAndTruncate: stop writing beyond the limit but keep draining to avoid pipe stalls.
         * - overflow=KillProcess: kill the process tree once output exceeds the limit.
         *
         * Note: [maxBytes] applies to bytes written *by this sink during this run*, not total file size.
         */
        data class File(
            val path: Path,
            val write: FileWritePolicy = FileWritePolicy.Truncate(),
            val maxBytes: Int? = null,
            val overflow: OverflowPolicy = OverflowPolicy.KillProcess,
        ) : SinkSpec

        /**
         * Tee to multiple sinks. Only the primary sink's capture/stats are surfaced in ExecResult.
         * Branches exist for side-effects (file, streaming callbacks, etc).
         */
        data class Tee(val primary: SinkSpec, val branches: List<SinkSpec>) : SinkSpec
    }

    /**
     * Stdout handling.
     *
     * - Pipe requires background pumping to avoid OS pipe backpressure deadlocks.
     * - Inherit/Discard avoid pumping entirely.
     */
    sealed interface StdoutSpec {
        data object Inherit : StdoutSpec
        data object Discard : StdoutSpec
        data class Pipe(val sink: SinkSpec) : StdoutSpec
    }

    /**
     * Stderr handling.
     *
     * - stderr and stdout are separate pipes by default; both must be drained concurrently if piped.
     * - ToStdout merges stderr into stdout at the ProcessBuilder level.
     */
    sealed interface StderrSpec {
        data object Inherit : StderrSpec
        data object Discard : StderrSpec
        data class Pipe(val sink: SinkSpec) : StderrSpec

        /** Merge stderr into stdout (ProcessBuilder.redirectErrorStream(true)). */
        data object ToStdout : StderrSpec
    }

    companion object {
        val defaultEnvPolicy = EnvPolicy.Inherit()
        val defaultStdinSpec = Input.None
        val defaultStdoutSpec = StdoutSpec.Pipe(SinkSpec.Capture(maxBytes = 4 * 1024 * 1024, keep = Keep.Head))
        val defaultStderrSpec = StderrSpec.Pipe(SinkSpec.Capture(maxBytes = 256 * 1024, keep = Keep.Tail))
        val defaultShutdownPolicy = ShutdownPolicy.TerminateThenKillTree()
        val defaultCleanupTimeout: Duration = 2.seconds
        val defaultExitPolicy = ExitPolicy.Return

        /**
         * Preset for tool-ish commands (ruff/black/pyright/gradle/native-image/ghostscript/etc).
         * stderr is tail-captured larger by default because it tends to be huge and the interesting part
         * often shows up at the end.
         */
        fun tooling(
            argv: List<String>,
            cwd: Path? = null,
            env: EnvPolicy = defaultEnvPolicy,
            stdin: Input = defaultStdinSpec,
            timeout: Duration? = null,
            stdoutMaxBytes: Int = 4 * 1024 * 1024,
            stderrTailBytes: Int = 1 * 1024 * 1024,
            shutdown: ShutdownPolicy = defaultShutdownPolicy,
            cleanupTimeout: Duration = defaultCleanupTimeout,
            exitPolicy: ExitPolicy = defaultExitPolicy,
        ): ExecSpec =
            ExecSpec(
                argv = argv,
                cwd = cwd,
                env = env,
                stdin = stdin,
                stdout = StdoutSpec.Pipe(SinkSpec.Capture(maxBytes = stdoutMaxBytes, keep = Keep.Head)),
                stderr = StderrSpec.Pipe(SinkSpec.Capture(maxBytes = stderrTailBytes, keep = Keep.Tail)),
                timeout = timeout,
                shutdown = shutdown,
                cleanupTimeout = cleanupTimeout,
                exitPolicy = exitPolicy,
            )
    }
}

data class ExecResult(
    val meta: Meta,
    val exitCode: ExitCode,
    val stdout: Captured? = null,
    val stderr: Captured? = null,
    val stdoutStats: OutputStats? = stdout?.let { OutputStats(it.bytesRead, it.truncated) },
    val stderrStats: OutputStats? = stderr?.let { OutputStats(it.bytesRead, it.truncated) },
    val duration: Duration,
) {
    data class Meta(
        val argv: List<String>,
        val pid: Long? = null,
    )

    data class Captured(
        val bytes: ByteArray,
        val truncated: Boolean,
        val bytesRead: Long,
    ) {
        fun text(
            charset: Charset = StandardCharsets.UTF_8,
            trimLineEndings: Boolean = true
        ): String {
            val s = bytes.toString(charset)
            return if (!trimLineEndings) s else s.trimEnd('\r', '\n')
        }
    }

    data class OutputStats(
        val bytesRead: Long,
        val truncated: Boolean,
    )

    /** Convenience container for attaching partial captures (and stats) to errors. */
    data class Captures(
        val stdout: Captured? = null,
        val stderr: Captured? = null,
        val stdoutStats: OutputStats? = stdout?.let { OutputStats(it.bytesRead, it.truncated) },
        val stderrStats: OutputStats? = stderr?.let { OutputStats(it.bytesRead, it.truncated) },
    )

    val ok: Boolean get() = exitCode.value == 0

    @Throws(ExecException::class)
    fun requireOk(): ExecResult {
        if (ok) return this
        val c =
            ExecResult.Captures(
                stdout = stdout,
                stderr = stderr,
                stdoutStats = stdoutStats,
                stderrStats = stderrStats,
            )
        throw ExecException(
            ExecError.ExitNonZero(
                meta = meta,
                exitCode = exitCode.value,
                captures = c
            )
        )
    }
}

sealed interface ExecOutcome {
    data class Success(val result: ExecResult) : ExecOutcome
    data class Failure(val error: ExecError) : ExecOutcome
}

/**
 * Spawn/detach API.
 *
 * This is intentionally *redirect-only* I/O: no PIPE pumping, no capture sinks, no streaming callbacks.
 *
 * Reason: if you detach while stdout/stderr are piped, you either:
 *  - leak background pump tasks forever, or
 *  - deadlock the child when its pipes fill (classic ProcessBuilder foot-gun).
 *
 * If you want capture/pumping, use [Exec.exec]/[Exec.execBlocking]/[Exec.execVirtual] and keep the task alive.
 */

data class SpawnSpec(
    val argv: List<String>,
    val cwd: Path? = null,
    val env: EnvPolicy = EnvPolicy.Inherit(),

    val stdin: Input = Input.None,
    val stdout: StdoutSpec = StdoutSpec.Discard,
    val stderr: StderrSpec = StderrSpec.Discard,

    /** Default kill behavior for [RunningProcessImpl.killTree]. */
    val shutdown: ShutdownPolicy = ShutdownPolicy.TerminateThenKillTree(500.milliseconds),
) {
    sealed interface Input {
        /** Child stdin is immediately closed (EOF). */
        data object None : Input

        /** Child stdin is inherited from the parent. */
        data object Inherit : Input
    }

    sealed interface StdoutSpec {
        data object Inherit : StdoutSpec
        data object Discard : StdoutSpec
        data class File(val path: Path, val append: Boolean = false) : StdoutSpec
    }

    sealed interface StderrSpec {
        data object Inherit : StderrSpec
        data object Discard : StderrSpec
        data class File(val path: Path, val append: Boolean = false) : StderrSpec

        /** Merge stderr into stdout (ProcessBuilder.redirectErrorStream(true)). */
        data object ToStdout : StderrSpec
    }
}

/** Outcome for timeboxed waiting on a spawned process. */
sealed interface AwaitExitOutcome {
    data class Exited(val code: ExitCode) : AwaitExitOutcome
    data object TimedOut : AwaitExitOutcome
    data object Interrupted : AwaitExitOutcome
}

interface RunningProcess {
    val pid: Long?

    fun isAlive(): Boolean

    fun exitCodeOrNull(): ExitCode?

    /** Kill the process tree using the configured [SpawnSpec.shutdown]. Idempotent. */
    fun killTree()

    /**
     * Wait for exit; returns a richer outcome (distinguishes timeout vs interrupt).
     * Does NOT kill on timeout/interrupt.
     */
    fun awaitExitBlockingOutcome(timeout: Duration? = null): AwaitExitOutcome

    /** Wait for exit; returns null if still running after [timeout]. Does NOT kill on timeout/interrupt. */
    fun awaitExitBlocking(timeout: Duration? = null): ExitCode?

    /** Wait for exit; returns null if still running after [timeout]. Does NOT kill on timeout. */
    suspend fun awaitExitOutcome(timeout: Duration? = null): AwaitExitOutcome

    /** Wait for exit; returns null if still running after [timeout]. Cancellation propagates. */
    suspend fun awaitExit(timeout: Duration? = null): ExitCode?
}

object Exec {
    @Throws(ExecException::class)
    suspend fun exec(spec: ExecSpec, ioDispatcher: CoroutineDispatcher = Dispatchers.IO): ExecResult =
        execInternal(spec, ioDispatcher = ioDispatcher)

    @Throws(ExecException::class)
    fun execBlocking(spec: ExecSpec, virtualThreads: VirtualThreadsPolicy = VirtualThreadsPolicy.Prefer): ExecResult =
        execBlockingInternal(spec, virtualThreads = virtualThreads)

    @Throws(ExecException::class)
    suspend fun spawn(
        spec: SpawnSpec,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): RunningProcess =
        spawnInternal(spec, ioDispatcher = ioDispatcher)

    @Throws(ExecException::class)
    fun spawnBlocking(spec: SpawnSpec): RunningProcess =
        spawnBlockingInternal(spec)

    @Throws(ExecException::class)
    suspend fun exec(
        argv: List<String>,
        cwd: Path? = null,
        env: EnvPolicy = ExecSpec.defaultEnvPolicy,
        stdin: ExecSpec.Input = ExecSpec.defaultStdinSpec,
        stdout: ExecSpec.StdoutSpec = ExecSpec.defaultStdoutSpec,
        stderr: ExecSpec.StderrSpec = ExecSpec.defaultStderrSpec,
        timeout: Duration? = null,
        shutdown: ShutdownPolicy = ExecSpec.defaultShutdownPolicy,
        cleanupTimeout: Duration = ExecSpec.defaultCleanupTimeout,
        exitPolicy: ExitPolicy = ExecSpec.defaultExitPolicy,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): ExecResult =
        execInternal(ExecSpec(
            argv = argv, cwd = cwd, env = env,
            stdin = stdin, stdout = stdout, stderr = stderr,
            timeout = timeout, shutdown = shutdown,
            cleanupTimeout = cleanupTimeout,
            exitPolicy = exitPolicy,
        ), ioDispatcher = ioDispatcher)

    @Throws(ExecException::class)
    fun execBlocking(
        argv: List<String>,
        cwd: Path? = null,
        env: EnvPolicy = ExecSpec.defaultEnvPolicy,
        stdin: ExecSpec.Input = ExecSpec.defaultStdinSpec,
        stdout: ExecSpec.StdoutSpec = ExecSpec.defaultStdoutSpec,
        stderr: ExecSpec.StderrSpec = ExecSpec.defaultStderrSpec,
        timeout: Duration? = null,
        shutdown: ShutdownPolicy = ExecSpec.defaultShutdownPolicy,
        cleanupTimeout: Duration = ExecSpec.defaultCleanupTimeout,
        exitPolicy: ExitPolicy = ExecSpec.defaultExitPolicy,
        virtualThreads: VirtualThreadsPolicy = VirtualThreadsPolicy.Prefer
    ): ExecResult =
        execBlockingInternal(ExecSpec(
            argv = argv, cwd = cwd, env = env,
            stdin = stdin, stdout = stdout, stderr = stderr,
            timeout = timeout, shutdown = shutdown,
            cleanupTimeout = cleanupTimeout,
            exitPolicy = exitPolicy,
        ), virtualThreads = virtualThreads)

    suspend fun execOutcome(spec: ExecSpec): ExecOutcome =
        try {
            ExecOutcome.Success(exec(spec))
        } catch (e: ExecException) {
            ExecOutcome.Failure(e.error)
        }

    fun execBlockingOutcome(spec: ExecSpec, virtualThreads: VirtualThreadsPolicy = VirtualThreadsPolicy.Prefer): ExecOutcome =
        try {
            ExecOutcome.Success(execBlocking(spec))
        } catch (e: ExecException) {
            ExecOutcome.Failure(e.error)
        }
}
