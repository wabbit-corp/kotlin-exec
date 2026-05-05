package one.wabbit.exec

import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.files.Path

/**
 * Process exit status as reported by the operating system.
 *
 * The value is intentionally kept as a raw integer because conventions differ by platform and by
 * launcher. On Unix-like systems, values in the `128..255` range often encode signal termination as
 * `128 + signal`.
 *
 * @property value raw process exit status.
 */
@JvmInline value class ExitCode(val value: Int)

/**
 * Controls how non-zero process exits are represented.
 */
enum class ExitPolicy {
    /**
     * Return an [ExecResult] for any process exit code.
     */
    Return,

    /**
     * Throw [ExecException] with [ExecError.ExitNonZero] when the process exits with a non-zero code.
     */
    ThrowOnNonZero,
}

/**
 * Defines the environment visible to a spawned process.
 */
sealed interface EnvPolicy {
    /**
     * Inherit the parent process environment and then apply [overlay].
     *
     * @property overlay variables that replace or add entries in the inherited environment.
     */
    data class Inherit(val overlay: Map<String, String> = emptyMap()) : EnvPolicy

    /**
     * Clear the parent process environment, install a minimal platform environment, and then apply
     * [base].
     *
     * The minimal environment keeps basic process launching working, such as `PATH` and temporary
     * directory variables on Unix-like systems and `SystemRoot`, `ComSpec`, `PATH`, `PATHEXT`, `TEMP`,
     * and `TMP` on Windows.
     *
     * @property base variables that replace or add entries after the minimal environment is installed.
     */
    data class Hermetic(val base: Map<String, String> = emptyMap()) : EnvPolicy

    /**
     * Clear the parent process environment and use exactly [base].
     *
     * Use this only when the target command can run without platform helper variables such as `PATH`.
     *
     * @property base complete environment for the child process.
     */
    data class ClearAndSet(val base: Map<String, String> = emptyMap()) : EnvPolicy
}

/**
 * Controls how `kotlin-exec` terminates a process tree it owns.
 */
sealed interface ShutdownPolicy {
    /**
     * Force-kill the process tree immediately.
     */
    data object KillTree : ShutdownPolicy

    /**
     * Request graceful termination first and force-kill the process tree after [grace].
     *
     * [grace] must be finite, non-negative, and less than or equal to the enclosing
     * [ExecSpec.cleanupTimeout] for managed execution.
     *
     * @property grace time allowed between graceful termination and force kill.
     */
    data class TerminateThenKillTree(val grace: Duration = 500.milliseconds) : ShutdownPolicy
}

/**
 * Controls how [ExecSpec.SinkSpec.File] opens an output file.
 */
sealed interface FileWritePolicy {
    /**
     * Whether the file is opened at process-start time instead of on the first output byte.
     *
     * Eager opening surfaces file errors before the process can run and, for truncate mode, removes
     * stale content even when the command emits no output. Lazy opening can avoid creating files for
     * commands that produce no output.
     */
    val eager: Boolean

    /**
     * Create or truncate the file before writing.
     *
     * @property eager whether to open and truncate at process-start time.
     */
    data class Truncate(override val eager: Boolean = true) : FileWritePolicy

    /**
     * Create or append to the file before writing.
     *
     * @property eager whether to open the append handle at process-start time.
     */
    data class Append(override val eager: Boolean = true) : FileWritePolicy
}

/**
 * Complete specification for a managed process run.
 *
 * Managed execution owns the child lifecycle: it starts the process, pumps requested input and
 * output, waits for exit, enforces [timeout], finalizes sinks, and returns [ExecResult] or throws
 * [ExecException]. A timeout or coroutine cancellation is destructive for managed execution: the
 * process tree is terminated with [shutdown].
 *
 * @property argv command and arguments. The list must be non-empty; `argv[0]` is passed directly to
 * the platform process launcher.
 * @property cwd optional working directory.
 * @property env environment policy for the child process.
 * @property stdin stdin source. [Input.None] closes stdin immediately.
 * @property stdout stdout routing. The default captures the first 4 MiB.
 * @property stderr stderr routing. The default captures the last 256 KiB.
 * @property timeout maximum time to wait for process exit, or `null` to wait without a run timeout.
 * @property shutdown process-tree termination policy used for timeout, cancellation, and output-limit
 * failures.
 * @property cleanupTimeout maximum time spent joining I/O tasks and cleanup after the process exits or
 * is terminated.
 * @property exitPolicy whether non-zero exits are returned or thrown.
 */
data class ExecSpec(
    val argv: List<String>,
    val cwd: Path? = null,
    val env: EnvPolicy = defaultEnvPolicy,
    val stdin: Input = defaultStdinSpec,
    val stdout: StdoutSpec = defaultStdoutSpec,
    val stderr: StderrSpec = defaultStderrSpec,
    val timeout: Duration? = null,
    val shutdown: ShutdownPolicy = defaultShutdownPolicy,
    val cleanupTimeout: Duration = defaultCleanupTimeout,
    val exitPolicy: ExitPolicy = defaultExitPolicy,
) {
    /**
     * Stdin source for managed execution.
     */
    sealed interface Input {
        /**
         * Close child stdin immediately.
         */
        data object None : Input

        /**
         * Inherit stdin from the parent process.
         */
        data object Inherit : Input

        /**
         * Write [bytes] to child stdin and then close it.
         *
         * @property bytes complete stdin payload.
         */
        data class Bytes(val bytes: ByteArray) : Input

        /**
         * Encode [text] with [encoding], write it to child stdin, and then close stdin.
         *
         * @property text complete text stdin payload.
         * @property encoding text encoding used to produce bytes.
         */
        data class Text(
            val text: String,
            val encoding: TextEncoding = TextEncoding.Utf8,
        ) : Input

        /**
         * Open a [Source], copy it to child stdin, and close both streams.
         *
         * If [open] throws, managed execution fails with [ExecError.InputProviderFailed].
         *
         * @property open factory called by the execution engine when stdin pumping starts.
         */
        data class Source(val open: () -> kotlinx.io.Source) : Input

        /**
         * Let caller code write directly into child stdin through a [Sink].
         *
         * If [write] throws before bytes are fully transferred, managed execution fails with
         * [ExecError.InputProviderFailed] or [ExecError.StdinWriteFailed] depending on where the
         * failure occurs.
         *
         * @property write callback that writes the stdin payload.
         */
        data class WriteTo(val write: (Sink) -> Unit) : Input

        /**
         * Open [path], copy its bytes to child stdin, and close child stdin.
         *
         * @property path filesystem path to stream into stdin.
         */
        data class FromPath(val path: Path) : Input
    }

    /**
     * Behavior when process output exceeds a configured byte limit.
     */
    enum class OverflowPolicy {
        /**
         * Keep reading process output but stop retaining or delivering bytes past the limit.
         */
        DrainAndTruncate,

        /**
         * Treat the limit as a hard bound and terminate the process when it is exceeded.
         */
        KillProcess,
    }

    /**
     * Which portion of captured output is retained when a capture limit is applied.
     */
    enum class Keep {
        /**
         * Retain the first bytes up to the configured limit.
         */
        Head,

        /**
         * Retain the last bytes up to the configured limit.
         */
        Tail,
    }

    /**
     * Destination for a piped stdout or stderr stream.
     */
    sealed interface SinkSpec {
        /**
         * Capture output in memory.
         *
         * @property maxBytes maximum number of bytes retained. Must be greater than zero.
         * @property keep whether retained bytes come from the beginning or end of the stream.
         * @property overflow whether excess output is drained/truncated or terminates the process.
         */
        data class Capture(
            val maxBytes: Int = 4 * 1024 * 1024,
            val keep: Keep = Keep.Head,
            val overflow: OverflowPolicy = OverflowPolicy.DrainAndTruncate,
        ) : SinkSpec

        /**
         * Deliver output chunks to [onChunk] as they are read.
         *
         * The callback receives a reusable buffer unless [copyChunks] is `true`. If [maxBytes] is set,
         * callbacks stop after the limit and [overflow] decides whether the process is killed or the
         * remaining output is drained.
         *
         * @property onChunk callback receiving `(buffer, offset, length)`.
         * @property copyChunks copy each delivered chunk before invoking [onChunk].
         * @property maxBytes optional byte limit for callback delivery. Must be greater than zero when
         * set.
         * @property overflow behavior when [maxBytes] is exceeded.
         */
        data class Stream(
            val onChunk: (ByteArray, Int, Int) -> Unit,
            val copyChunks: Boolean = false,
            val maxBytes: Int? = null,
            val overflow: OverflowPolicy = OverflowPolicy.DrainAndTruncate,
        ) : SinkSpec

        /**
         * Open a [Sink] and write output bytes into it.
         *
         * The sink is opened lazily on first output byte. If [maxBytes] is set, only bytes up to the
         * limit are written and [overflow] controls what happens after the limit is exceeded.
         *
         * @property open sink factory.
         * @property maxBytes optional maximum bytes written. Must be greater than zero when set.
         * @property overflow behavior when [maxBytes] is exceeded.
         */
        data class WriteTo(
            val open: () -> Sink,
            val maxBytes: Int? = null,
            val overflow: OverflowPolicy = OverflowPolicy.DrainAndTruncate,
        ) : SinkSpec

        /**
         * Write output bytes to [path].
         *
         * Unbounded eager file sinks may be optimized into native process redirects. Bounded file
         * sinks are always pumped by `kotlin-exec` so [maxBytes] can be enforced.
         *
         * @property path output path.
         * @property write append/truncate and eager/lazy opening policy.
         * @property maxBytes optional maximum bytes written during this run. Must be greater than zero
         * when set.
         * @property overflow behavior when [maxBytes] is exceeded.
         */
        data class File(
            val path: Path,
            val write: FileWritePolicy = FileWritePolicy.Truncate(),
            val maxBytes: Int? = null,
            val overflow: OverflowPolicy = OverflowPolicy.KillProcess,
        ) : SinkSpec

        /**
         * Send the same output stream to multiple sinks.
         *
         * The [primary] sink determines captured bytes and stats returned in [ExecResult]. [branches]
         * are side-effect sinks such as streams or files.
         *
         * @property primary sink whose capture is reported.
         * @property branches additional sinks that receive the same chunks.
         */
        data class Tee(val primary: SinkSpec, val branches: List<SinkSpec>) : SinkSpec
    }

    /**
     * Stdout routing for managed execution.
     */
    sealed interface StdoutSpec {
        /**
         * Inherit stdout from the parent process.
         */
        data object Inherit : StdoutSpec

        /**
         * Discard stdout.
         */
        data object Discard : StdoutSpec

        /**
         * Pipe stdout through [sink].
         *
         * @property sink destination for stdout bytes.
         */
        data class Pipe(val sink: SinkSpec) : StdoutSpec
    }

    /**
     * Stderr routing for managed execution.
     */
    sealed interface StderrSpec {
        /**
         * Inherit stderr from the parent process.
         */
        data object Inherit : StderrSpec

        /**
         * Discard stderr.
         */
        data object Discard : StderrSpec

        /**
         * Pipe stderr through [sink].
         *
         * @property sink destination for stderr bytes.
         */
        data class Pipe(val sink: SinkSpec) : StderrSpec

        /**
         * Merge stderr into stdout using the platform process launcher.
         */
        data object ToStdout : StderrSpec
    }

    companion object {
        /**
         * Default environment policy: inherit parent environment without changes.
         */
        val defaultEnvPolicy = EnvPolicy.Inherit()

        /**
         * Default stdin: close child stdin immediately.
         */
        val defaultStdinSpec = Input.None

        /**
         * Default stdout: capture the first 4 MiB in memory.
         */
        val defaultStdoutSpec = StdoutSpec.Pipe(SinkSpec.Capture(maxBytes = 4 * 1024 * 1024, keep = Keep.Head))

        /**
         * Default stderr: capture the last 256 KiB in memory.
         */
        val defaultStderrSpec = StderrSpec.Pipe(SinkSpec.Capture(maxBytes = 256 * 1024, keep = Keep.Tail))

        /**
         * Default shutdown: graceful termination followed by force kill after 500 milliseconds.
         */
        val defaultShutdownPolicy = ShutdownPolicy.TerminateThenKillTree()

        /**
         * Default cleanup budget for joining I/O tasks and finalizing sinks.
         */
        val defaultCleanupTimeout: Duration = 2.seconds

        /**
         * Default exit handling: return [ExecResult] even for non-zero exits.
         */
        val defaultExitPolicy = ExitPolicy.Return

        /**
         * Build an [ExecSpec] tuned for tool invocation.
         *
         * This helper captures stdout from the head and stderr from the tail, which is usually the
         * right diagnostic shape for compilers, CLIs, and build tools.
         *
         * @param argv command and arguments.
         * @param cwd optional working directory.
         * @param env environment policy.
         * @param stdin stdin source.
         * @param timeout optional managed-execution timeout.
         * @param stdoutMaxBytes maximum stdout bytes retained from the head.
         * @param stderrTailBytes maximum stderr bytes retained from the tail.
         * @param shutdown shutdown policy used when the process must be terminated.
         * @param cleanupTimeout cleanup budget after process exit or termination.
         * @param exitPolicy non-zero exit handling.
         * @return an [ExecSpec] with bounded stdout and stderr capture.
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

/**
 * Completed managed process result.
 *
 * Captured output is present only for streams routed through [ExecSpec.SinkSpec.Capture] or a
 * [ExecSpec.SinkSpec.Tee] whose primary sink captures. Streaming, file, discard, and inherited
 * streams still report [stdoutStats] or [stderrStats] when `kotlin-exec` pumped the stream.
 *
 * @property meta command metadata.
 * @property exitCode raw process exit code.
 * @property stdout captured stdout bytes, when stdout was captured.
 * @property stderr captured stderr bytes, when stderr was captured.
 * @property stdoutStats stdout byte counters, when stdout was pumped or captured.
 * @property stderrStats stderr byte counters, when stderr was pumped or captured.
 * @property duration wall-clock duration measured by the execution engine.
 */
data class ExecResult(
    val meta: Meta,
    val exitCode: ExitCode,
    val stdout: Captured? = null,
    val stderr: Captured? = null,
    val stdoutStats: OutputStats? = stdout?.let { OutputStats(it.bytesRead, it.truncated) },
    val stderrStats: OutputStats? = stderr?.let { OutputStats(it.bytesRead, it.truncated) },
    val duration: Duration,
) {
    /**
     * Metadata known about a process run.
     *
     * @property argv command and arguments used for the run.
     * @property pid process id when the platform exposes it.
     */
    data class Meta(
        val argv: List<String>,
        val pid: Long? = null,
    )

    /**
     * In-memory captured output.
     *
     * @property bytes retained bytes. This may be a head or tail slice of the stream.
     * @property truncated `true` when not all bytes read from the process are present in [bytes].
     * @property bytesRead total bytes read from the process stream.
     */
    data class Captured(
        val bytes: ByteArray,
        val truncated: Boolean,
        val bytesRead: Long,
    ) {
        /**
         * Decode captured bytes to text.
         *
         * @param encoding character encoding used to decode [bytes].
         * @param trimLineEndings whether to remove trailing `\r` and `\n` characters.
         * @return decoded text.
         */
        fun text(
            encoding: TextEncoding = TextEncoding.Utf8,
            trimLineEndings: Boolean = true,
        ): String {
            val value = TextEncodingPlatform.decode(bytes, encoding)
            return if (!trimLineEndings) value else value.trimEnd('\r', '\n')
        }
    }

    /**
     * Byte counters for a process output stream.
     *
     * @property bytesRead total bytes read from the process stream.
     * @property truncated `true` when the configured sink stopped retaining or delivering bytes.
     */
    data class OutputStats(
        val bytesRead: Long,
        val truncated: Boolean,
    )

    /**
     * Captures and stats attached to an [ExecError].
     *
     * @property stdout captured stdout bytes available at failure time.
     * @property stderr captured stderr bytes available at failure time.
     * @property stdoutStats stdout counters available at failure time.
     * @property stderrStats stderr counters available at failure time.
     */
    data class Captures(
        val stdout: Captured? = null,
        val stderr: Captured? = null,
        val stdoutStats: OutputStats? = stdout?.let { OutputStats(it.bytesRead, it.truncated) },
        val stderrStats: OutputStats? = stderr?.let { OutputStats(it.bytesRead, it.truncated) },
    )

    /**
     * Whether [exitCode] is zero.
     */
    val ok: Boolean get() = exitCode.value == 0

    /**
     * Return this result when [ok], or throw [ExecException] with [ExecError.ExitNonZero].
     *
     * @return this result when the process exited successfully.
     * @throws ExecException when [exitCode] is non-zero.
     */
    @Throws(ExecException::class)
    fun requireOk(): ExecResult {
        if (ok) return this
        val c =
            Captures(
                stdout = stdout,
                stderr = stderr,
                stdoutStats = stdoutStats,
                stderrStats = stderrStats,
            )
        throw ExecException(
            ExecError.ExitNonZero(
                meta = meta,
                exitCode = exitCode.value,
                captures = c,
            )
        )
    }
}

/**
 * Non-throwing representation of a managed execution attempt.
 */
sealed interface ExecOutcome {
    /**
     * Process execution completed and produced [result].
     *
     * @property result completed managed process result.
     */
    data class Success(val result: ExecResult) : ExecOutcome

    /**
     * Process execution failed with a structured [error].
     *
     * @property error structured failure.
     */
    data class Failure(val error: ExecError) : ExecOutcome
}

/**
 * Specification for an unmanaged spawned process.
 *
 * Spawned processes return a [RunningProcess] handle immediately after launch. Unlike [ExecSpec],
 * spawn does not capture output in memory, does not wait for completion, and does not kill the
 * process when a later wait times out. Spawned stdout and stderr can still be inherited, discarded,
 * merged, or redirected to files. Call [RunningProcess.killTree] when the caller owns and wants to
 * terminate the child.
 *
 * @property argv command and arguments. The list must be non-empty.
 * @property cwd optional working directory.
 * @property env environment policy for the child process.
 * @property stdin stdin behavior. [Input.None] closes stdin immediately.
 * @property stdout stdout routing for the child process.
 * @property stderr stderr routing for the child process.
 * @property shutdown process-tree termination policy used by [RunningProcess.killTree].
 */
data class SpawnSpec(
    val argv: List<String>,
    val cwd: Path? = null,
    val env: EnvPolicy = EnvPolicy.Inherit(),
    val stdin: Input = Input.None,
    val stdout: StdoutSpec = StdoutSpec.Discard,
    val stderr: StderrSpec = StderrSpec.Discard,
    val shutdown: ShutdownPolicy = ShutdownPolicy.TerminateThenKillTree(500.milliseconds),
) {
    /**
     * Stdin behavior for spawned processes.
     */
    sealed interface Input {
        /**
         * Close child stdin immediately.
         */
        data object None : Input

        /**
         * Inherit stdin from the parent process.
         */
        data object Inherit : Input
    }

    /**
     * Stdout routing for spawned processes.
     */
    sealed interface StdoutSpec {
        /**
         * Inherit stdout from the parent process.
         */
        data object Inherit : StdoutSpec

        /**
         * Discard stdout.
         */
        data object Discard : StdoutSpec

        /**
         * Redirect stdout to [path].
         *
         * @property path file receiving stdout.
         * @property append whether to append instead of truncating.
         */
        data class File(val path: Path, val append: Boolean = false) : StdoutSpec
    }

    /**
     * Stderr routing for spawned processes.
     */
    sealed interface StderrSpec {
        /**
         * Inherit stderr from the parent process.
         */
        data object Inherit : StderrSpec

        /**
         * Discard stderr.
         */
        data object Discard : StderrSpec

        /**
         * Redirect stderr to [path].
         *
         * @property path file receiving stderr.
         * @property append whether to append instead of truncating.
         */
        data class File(val path: Path, val append: Boolean = false) : StderrSpec

        /**
         * Merge stderr into stdout using the platform process launcher.
         */
        data object ToStdout : StderrSpec
    }
}

/**
 * Result of waiting for a [RunningProcess].
 */
sealed interface AwaitExitOutcome {
    /**
     * The process exited with [code].
     *
     * @property code raw process exit status.
     */
    data class Exited(val code: ExitCode) : AwaitExitOutcome

    /**
     * The wait timeout elapsed and the process may still be running.
     */
    data object TimedOut : AwaitExitOutcome

    /**
     * A blocking wait was interrupted and the process may still be running.
     */
    data object Interrupted : AwaitExitOutcome
}

/**
 * Handle for a spawned process.
 *
 * Waiting through this interface is non-destructive: timeout does not kill the process. Use
 * [killTree] explicitly when the caller wants to terminate the process tree.
 */
interface RunningProcess {
    /**
     * Platform process id when available.
     */
    val pid: Long?

    /**
     * Return whether the process is currently alive.
     */
    fun isAlive(): Boolean

    /**
     * Return the exit code when the process has exited, or `null` while it is still running.
     */
    fun exitCodeOrNull(): ExitCode?

    /**
     * Terminate the process tree using the shutdown policy from the spawn specification.
     *
     * Implementations are expected to make this operation idempotent.
     */
    fun killTree()

    /**
     * Block until the process exits, [timeout] elapses, or the wait is interrupted.
     *
     * Timeout and interruption do not kill the process.
     *
     * @param timeout optional wait timeout.
     * @return detailed wait outcome.
     */
    fun awaitExitBlockingOutcome(timeout: Duration? = null): AwaitExitOutcome

    /**
     * Block until process exit or timeout.
     *
     * @param timeout optional wait timeout.
     * @return exit code, or `null` on timeout or interruption.
     */
    fun awaitExitBlocking(timeout: Duration? = null): ExitCode?

    /**
     * Suspend until process exit or timeout.
     *
     * Timeout does not kill the process. Coroutine cancellation propagates to the caller.
     *
     * @param timeout optional wait timeout.
     * @return detailed wait outcome.
     */
    suspend fun awaitExitOutcome(timeout: Duration? = null): AwaitExitOutcome

    /**
     * Suspend until process exit or timeout.
     *
     * @param timeout optional wait timeout.
     * @return exit code, or `null` on timeout.
     */
    suspend fun awaitExit(timeout: Duration? = null): ExitCode?
}
