@file:OptIn(PlatformSpecificExecApi::class)

package one.wabbit.exec

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.io.Sink
import kotlinx.io.files.Path as KxPath
import one.wabbit.throwables.Throwables

/**
 * Blocking execution strategy for JVM I/O worker tasks.
 *
 * Virtual threads are available on JDK 21 and newer. Use [Prefer] for normal blocking calls so the
 * library can use virtual threads when available and fall back to platform threads otherwise.
 */
@PlatformSpecificExecApi
sealed interface VirtualThreadsPolicy {
    /**
     * Always use platform threads created by `kotlin-exec`.
     */
    data object Never : VirtualThreadsPolicy

    /**
     * Use virtual threads when the current JDK exposes them, otherwise fall back to platform threads.
     */
    data object Prefer : VirtualThreadsPolicy

    /**
     * Require virtual threads and fail if the current JDK does not expose them.
     */
    data object Require : VirtualThreadsPolicy
}

/**
 * JVM escape hatch for callers that genuinely need the underlying [Process].
 *
 * Prefer the portable [RunningProcess] API whenever possible. Downcast to this
 * only when you need JVM-specific integration that `kotlin-exec` does not model.
 */
@PlatformSpecificExecApi
interface JvmRunningProcess : RunningProcess {
    /**
     * Underlying JVM [Process].
     *
     * Prefer [RunningProcess] methods unless you need a JVM API that this library does not model.
     */
    val rawProcess: Process
}

/**
 * JVM-only spec for the remaining execution features that are not portable today:
 * charset-specific text input, writer-backed stdin, stream-backed stdin, and
 * direct `java.nio.file.Path` interop.
 *
 * Prefer [ExecSpec] whenever you only need `argv`, `cwd`, env, UTF-8/common
 * stdin variants, common sinks, file sinks, timeout, shutdown, and exit policy.
 *
 * @property argv command and arguments. The list must be non-empty.
 * @property cwd optional JVM working directory.
 * @property env environment policy for the child process.
 * @property stdin JVM-aware stdin source.
 * @property stdout stdout routing.
 * @property stderr stderr routing.
 * @property timeout optional managed-execution timeout.
 * @property shutdown process-tree termination policy used when termination is required.
 * @property cleanupTimeout cleanup budget after exit or termination.
 * @property exitPolicy non-zero exit handling.
 */
@PlatformSpecificExecApi
data class JvmExecSpec(
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
     * JVM-aware stdin source for managed execution.
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
         * Write [bytes] to child stdin and close it.
         *
         * @property bytes complete stdin payload.
         */
        data class Bytes(val bytes: ByteArray) : Input

        /**
         * Encode [text] with [charset], write it to child stdin, and close stdin.
         *
         * @property text complete text stdin payload.
         * @property charset JVM charset used to produce bytes.
         */
        data class Text(
            val text: String,
            val charset: Charset = StandardCharsets.UTF_8,
        ) : Input

        /**
         * Open a `kotlinx-io` source, copy it to child stdin, and close both streams.
         *
         * @property open source factory called when stdin pumping starts.
         */
        data class Source(val open: () -> kotlinx.io.Source) : Input

        /**
         * Let caller code write child stdin through a `kotlinx-io` [Sink].
         *
         * @property write callback that writes the stdin payload.
         */
        data class WriteTo(val write: (Sink) -> Unit) : Input

        /**
         * Let caller code write child stdin through a JVM [OutputStream].
         *
         * @property write callback that writes the stdin payload.
         */
        data class Writer(val write: (OutputStream) -> Unit) : Input

        /**
         * Open [path], copy its bytes to child stdin, and close stdin.
         *
         * @property path JVM filesystem path to stream into stdin.
         */
        data class FromPath(val path: Path) : Input

        /**
         * Open a JVM [InputStream], copy it to child stdin, and close both streams.
         *
         * @property open stream factory called when stdin pumping starts.
         */
        data class FromStream(val open: () -> InputStream) : Input
    }

    /**
     * JVM-aware destination for a piped stdout or stderr stream.
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
            val keep: ExecSpec.Keep = ExecSpec.Keep.Head,
            val overflow: ExecSpec.OverflowPolicy = ExecSpec.OverflowPolicy.DrainAndTruncate,
        ) : SinkSpec

        /**
         * Deliver output chunks to [onChunk] as they are read.
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
            val overflow: ExecSpec.OverflowPolicy = ExecSpec.OverflowPolicy.DrainAndTruncate,
        ) : SinkSpec

        /**
         * Open a `kotlinx-io` [Sink] and write output bytes into it.
         *
         * @property open sink factory.
         * @property maxBytes optional maximum bytes written. Must be greater than zero when set.
         * @property overflow behavior when [maxBytes] is exceeded.
         */
        data class WriteTo(
            val open: () -> Sink,
            val maxBytes: Int? = null,
            val overflow: ExecSpec.OverflowPolicy = ExecSpec.OverflowPolicy.DrainAndTruncate,
        ) : SinkSpec

        /**
         * Write output bytes to a JVM [Path].
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
            val overflow: ExecSpec.OverflowPolicy = ExecSpec.OverflowPolicy.KillProcess,
        ) : SinkSpec

        /**
         * Send the same output stream to multiple sinks.
         *
         * The [primary] sink determines captured bytes and stats returned in [ExecResult]. [branches]
         * are side-effect sinks.
         *
         * @property primary sink whose capture is reported.
         * @property branches additional sinks that receive the same chunks.
         */
        data class Tee(val primary: SinkSpec, val branches: List<SinkSpec>) : SinkSpec
    }

    /**
     * Stdout routing for JVM managed execution.
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
     * Stderr routing for JVM managed execution.
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
         * Merge stderr into stdout using [ProcessBuilder.redirectErrorStream].
         */
        data object ToStdout : StderrSpec
    }

    companion object {
        /**
         * Default environment policy: inherit parent environment without changes.
         */
        val defaultEnvPolicy = ExecSpec.defaultEnvPolicy

        /**
         * Default stdin: close child stdin immediately.
         */
        val defaultStdinSpec = Input.None

        /**
         * Default stdout: capture the first 4 MiB in memory.
         */
        val defaultStdoutSpec = StdoutSpec.Pipe(SinkSpec.Capture(maxBytes = 4 * 1024 * 1024, keep = ExecSpec.Keep.Head))

        /**
         * Default stderr: capture the last 256 KiB in memory.
         */
        val defaultStderrSpec = StderrSpec.Pipe(SinkSpec.Capture(maxBytes = 256 * 1024, keep = ExecSpec.Keep.Tail))

        /**
         * Default shutdown policy inherited from [ExecSpec.defaultShutdownPolicy].
         */
        val defaultShutdownPolicy = ExecSpec.defaultShutdownPolicy

        /**
         * Default cleanup budget inherited from [ExecSpec.defaultCleanupTimeout].
         */
        val defaultCleanupTimeout: Duration = ExecSpec.defaultCleanupTimeout

        /**
         * Default exit handling inherited from [ExecSpec.defaultExitPolicy].
         */
        val defaultExitPolicy = ExecSpec.defaultExitPolicy

        /**
         * Build a [JvmExecSpec] tuned for tool invocation.
         *
         * @param argv command and arguments.
         * @param cwd optional JVM working directory.
         * @param env environment policy.
         * @param stdin stdin source.
         * @param timeout optional managed-execution timeout.
         * @param stdoutMaxBytes maximum stdout bytes retained from the head.
         * @param stderrTailBytes maximum stderr bytes retained from the tail.
         * @param shutdown shutdown policy used when the process must be terminated.
         * @param cleanupTimeout cleanup budget after process exit or termination.
         * @param exitPolicy non-zero exit handling.
         * @return a [JvmExecSpec] with bounded stdout and stderr capture.
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
        ): JvmExecSpec =
            JvmExecSpec(
                argv = argv,
                cwd = cwd,
                env = env,
                stdin = stdin,
                stdout = StdoutSpec.Pipe(SinkSpec.Capture(maxBytes = stdoutMaxBytes, keep = ExecSpec.Keep.Head)),
                stderr = StderrSpec.Pipe(SinkSpec.Capture(maxBytes = stderrTailBytes, keep = ExecSpec.Keep.Tail)),
                timeout = timeout,
                shutdown = shutdown,
                cleanupTimeout = cleanupTimeout,
                exitPolicy = exitPolicy,
            )
    }
}

/**
 * JVM-only spawn spec kept for legacy `java.nio.file.Path` interop.
 *
 * [SpawnSpec] already covers the portable spawn surface: `argv`, `cwd`, env,
 * inherit/discard stdio, file redirects, and shutdown behavior.
 *
 * @property argv command and arguments. The list must be non-empty.
 * @property cwd optional JVM working directory.
 * @property env environment policy for the child process.
 * @property stdin stdin behavior.
 * @property stdout stdout routing.
 * @property stderr stderr routing.
 * @property shutdown process-tree termination policy used by [RunningProcess.killTree].
 */
@PlatformSpecificExecApi
data class JvmSpawnSpec(
    val argv: List<String>,
    val cwd: Path? = null,
    val env: EnvPolicy = EnvPolicy.Inherit(),
    val stdin: Input = Input.None,
    val stdout: StdoutSpec = StdoutSpec.Discard,
    val stderr: StderrSpec = StderrSpec.Discard,
    val shutdown: ShutdownPolicy = ShutdownPolicy.TerminateThenKillTree(500.milliseconds),
) {
    /**
     * Stdin behavior for JVM spawned processes.
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
     * Stdout routing for JVM spawned processes.
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
     * Stderr routing for JVM spawned processes.
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
         * Merge stderr into stdout using [ProcessBuilder.redirectErrorStream].
         */
        data object ToStdout : StderrSpec
    }
}

/**
 * JVM implementation of the common [Exec] process execution entry point.
 */
actual object Exec {
    /**
     * Run [spec] as a managed process from a coroutine on the JVM.
     *
     * @param spec complete portable execution specification.
     * @param ioDispatcher dispatcher used for blocking process and I/O work.
     * @return completed process result.
     * @throws ExecException for structured execution failures.
     */
    @Throws(ExecException::class)
    actual suspend fun exec(
        spec: ExecSpec,
        ioDispatcher: CoroutineDispatcher,
    ): ExecResult =
        execInternal(spec.toJvmSpecOrThrow(), ioDispatcher = ioDispatcher)

    /**
     * Run a JVM-specific managed process spec from a coroutine.
     *
     * Use this overload only when [ExecSpec] cannot represent the required stdin, path, or charset
     * behavior.
     *
     * @param spec complete JVM-specific execution specification.
     * @param ioDispatcher dispatcher used for blocking process and I/O work.
     * @return completed process result.
     * @throws ExecException for structured execution failures.
     */
    @Throws(ExecException::class)
    @PlatformSpecificExecApi
    suspend fun exec(spec: JvmExecSpec, ioDispatcher: CoroutineDispatcher = Dispatchers.IO): ExecResult =
        execInternal(spec, ioDispatcher = ioDispatcher)

    /**
     * Run [spec] as a managed process and block the current thread.
     *
     * This common actual uses [VirtualThreadsPolicy.Prefer].
     *
     * @param spec complete portable execution specification.
     * @return completed process result.
     * @throws ExecException for structured execution failures.
     */
    @Throws(ExecException::class)
    actual fun execBlocking(spec: ExecSpec): ExecResult =
        execBlockingInternal(spec.toJvmSpecOrThrow(), virtualThreads = VirtualThreadsPolicy.Prefer)

    /**
     * Run [spec] as a managed process and block the current thread with explicit virtual-thread policy.
     *
     * @param spec complete portable execution specification.
     * @param virtualThreads virtual-thread usage policy for blocking I/O workers.
     * @return completed process result.
     * @throws ExecException for structured execution failures.
     */
    @Throws(ExecException::class)
    @PlatformSpecificExecApi
    fun execBlocking(spec: ExecSpec, virtualThreads: VirtualThreadsPolicy = VirtualThreadsPolicy.Prefer): ExecResult =
        execBlockingInternal(spec.toJvmSpecOrThrow(), virtualThreads = virtualThreads)

    /**
     * Run a JVM-specific managed process spec and block the current thread.
     *
     * @param spec complete JVM-specific execution specification.
     * @param virtualThreads virtual-thread usage policy for blocking I/O workers.
     * @return completed process result.
     * @throws ExecException for structured execution failures.
     */
    @Throws(ExecException::class)
    @PlatformSpecificExecApi
    fun execBlocking(spec: JvmExecSpec, virtualThreads: VirtualThreadsPolicy = VirtualThreadsPolicy.Prefer): ExecResult =
        execBlockingInternal(spec, virtualThreads = virtualThreads)

    /**
     * Spawn a portable process from a coroutine and return a running handle.
     *
     * @param spec spawn specification.
     * @param ioDispatcher dispatcher used for blocking process startup work.
     * @return running process handle.
     * @throws ExecException when process configuration or startup fails.
     */
    @Throws(ExecException::class)
    actual suspend fun spawn(
        spec: SpawnSpec,
        ioDispatcher: CoroutineDispatcher,
    ): RunningProcess =
        spawnInternal(spec.toJvmSpecOrThrow(), ioDispatcher = ioDispatcher)

    /**
     * Spawn a JVM-specific process from a coroutine and return a running handle.
     *
     * @param spec JVM-specific spawn specification.
     * @param ioDispatcher dispatcher used for blocking process startup work.
     * @return running process handle.
     * @throws ExecException when process configuration or startup fails.
     */
    @Throws(ExecException::class)
    @PlatformSpecificExecApi
    suspend fun spawn(
        spec: JvmSpawnSpec,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): RunningProcess =
        spawnInternal(spec, ioDispatcher = ioDispatcher)

    /**
     * Spawn a portable process from a blocking call and return a running handle.
     *
     * @param spec spawn specification.
     * @return running process handle.
     * @throws ExecException when process configuration or startup fails.
     */
    @Throws(ExecException::class)
    actual fun spawnBlocking(spec: SpawnSpec): RunningProcess =
        spawnBlockingInternal(spec.toJvmSpecOrThrow())

    /**
     * Spawn a JVM-specific process from a blocking call and return a running handle.
     *
     * @param spec JVM-specific spawn specification.
     * @return running process handle.
     * @throws ExecException when process configuration or startup fails.
     */
    @Throws(ExecException::class)
    @PlatformSpecificExecApi
    fun spawnBlocking(spec: JvmSpawnSpec): RunningProcess =
        spawnBlockingInternal(spec)

    /**
     * Convenience overload for portable managed coroutine execution.
     *
     * @param argv command and arguments.
     * @param cwd optional portable working directory.
     * @param env environment policy.
     * @param stdin stdin source.
     * @param stdout stdout routing.
     * @param stderr stderr routing.
     * @param timeout optional managed-execution timeout.
     * @param shutdown shutdown policy used when termination is required.
     * @param cleanupTimeout cleanup budget after exit or termination.
     * @param exitPolicy non-zero exit handling.
     * @param ioDispatcher dispatcher used for blocking process and I/O work.
     * @return completed process result.
     * @throws ExecException for structured execution failures.
     */
    @Throws(ExecException::class)
    actual suspend fun exec(
        argv: List<String>,
        cwd: KxPath?,
        env: EnvPolicy,
        stdin: ExecSpec.Input,
        stdout: ExecSpec.StdoutSpec,
        stderr: ExecSpec.StderrSpec,
        timeout: Duration?,
        shutdown: ShutdownPolicy,
        cleanupTimeout: Duration,
        exitPolicy: ExitPolicy,
        ioDispatcher: CoroutineDispatcher,
    ): ExecResult =
        exec(
            ExecSpec(
                argv = argv,
                cwd = cwd,
                env = env,
                stdin = stdin,
                stdout = stdout,
                stderr = stderr,
                timeout = timeout,
                shutdown = shutdown,
                cleanupTimeout = cleanupTimeout,
                exitPolicy = exitPolicy,
            ),
            ioDispatcher = ioDispatcher,
        )

    /**
     * Convenience overload for JVM-specific managed coroutine execution.
     *
     * @param argv command and arguments.
     * @param cwd optional JVM working directory.
     * @param env environment policy.
     * @param stdin JVM-aware stdin source.
     * @param stdout stdout routing.
     * @param stderr stderr routing.
     * @param timeout optional managed-execution timeout.
     * @param shutdown shutdown policy used when termination is required.
     * @param cleanupTimeout cleanup budget after exit or termination.
     * @param exitPolicy non-zero exit handling.
     * @param ioDispatcher dispatcher used for blocking process and I/O work.
     * @return completed process result.
     * @throws ExecException for structured execution failures.
     */
    @Throws(ExecException::class)
    @PlatformSpecificExecApi
    suspend fun exec(
        argv: List<String>,
        cwd: Path? = null,
        env: EnvPolicy = JvmExecSpec.defaultEnvPolicy,
        stdin: JvmExecSpec.Input = JvmExecSpec.defaultStdinSpec,
        stdout: JvmExecSpec.StdoutSpec = JvmExecSpec.defaultStdoutSpec,
        stderr: JvmExecSpec.StderrSpec = JvmExecSpec.defaultStderrSpec,
        timeout: Duration? = null,
        shutdown: ShutdownPolicy = JvmExecSpec.defaultShutdownPolicy,
        cleanupTimeout: Duration = JvmExecSpec.defaultCleanupTimeout,
        exitPolicy: ExitPolicy = JvmExecSpec.defaultExitPolicy,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): ExecResult =
        execInternal(
            JvmExecSpec(
                argv = argv,
                cwd = cwd,
                env = env,
                stdin = stdin,
                stdout = stdout,
                stderr = stderr,
                timeout = timeout,
                shutdown = shutdown,
                cleanupTimeout = cleanupTimeout,
                exitPolicy = exitPolicy,
            ),
            ioDispatcher = ioDispatcher,
        )

    /**
     * Convenience overload for portable managed blocking execution.
     *
     * @param argv command and arguments.
     * @param cwd optional portable working directory.
     * @param env environment policy.
     * @param stdin stdin source.
     * @param stdout stdout routing.
     * @param stderr stderr routing.
     * @param timeout optional managed-execution timeout.
     * @param shutdown shutdown policy used when termination is required.
     * @param cleanupTimeout cleanup budget after exit or termination.
     * @param exitPolicy non-zero exit handling.
     * @return completed process result.
     * @throws ExecException for structured execution failures.
     */
    @Throws(ExecException::class)
    actual fun execBlocking(
        argv: List<String>,
        cwd: KxPath?,
        env: EnvPolicy,
        stdin: ExecSpec.Input,
        stdout: ExecSpec.StdoutSpec,
        stderr: ExecSpec.StderrSpec,
        timeout: Duration?,
        shutdown: ShutdownPolicy,
        cleanupTimeout: Duration,
        exitPolicy: ExitPolicy,
    ): ExecResult =
        execBlocking(
            ExecSpec(
                argv = argv,
                cwd = cwd,
                env = env,
                stdin = stdin,
                stdout = stdout,
                stderr = stderr,
                timeout = timeout,
                shutdown = shutdown,
                cleanupTimeout = cleanupTimeout,
                exitPolicy = exitPolicy,
            )
        )

    /**
     * Convenience overload for JVM-specific managed blocking execution.
     *
     * @param argv command and arguments.
     * @param cwd optional JVM working directory.
     * @param env environment policy.
     * @param stdin JVM-aware stdin source.
     * @param stdout stdout routing.
     * @param stderr stderr routing.
     * @param timeout optional managed-execution timeout.
     * @param shutdown shutdown policy used when termination is required.
     * @param cleanupTimeout cleanup budget after exit or termination.
     * @param exitPolicy non-zero exit handling.
     * @param virtualThreads virtual-thread usage policy for blocking I/O workers.
     * @return completed process result.
     * @throws ExecException for structured execution failures.
     */
    @Throws(ExecException::class)
    @PlatformSpecificExecApi
    fun execBlocking(
        argv: List<String>,
        cwd: Path? = null,
        env: EnvPolicy = JvmExecSpec.defaultEnvPolicy,
        stdin: JvmExecSpec.Input = JvmExecSpec.defaultStdinSpec,
        stdout: JvmExecSpec.StdoutSpec = JvmExecSpec.defaultStdoutSpec,
        stderr: JvmExecSpec.StderrSpec = JvmExecSpec.defaultStderrSpec,
        timeout: Duration? = null,
        shutdown: ShutdownPolicy = JvmExecSpec.defaultShutdownPolicy,
        cleanupTimeout: Duration = JvmExecSpec.defaultCleanupTimeout,
        exitPolicy: ExitPolicy = JvmExecSpec.defaultExitPolicy,
        virtualThreads: VirtualThreadsPolicy = VirtualThreadsPolicy.Prefer,
    ): ExecResult =
        execBlockingInternal(
            JvmExecSpec(
                argv = argv,
                cwd = cwd,
                env = env,
                stdin = stdin,
                stdout = stdout,
                stderr = stderr,
                timeout = timeout,
                shutdown = shutdown,
                cleanupTimeout = cleanupTimeout,
                exitPolicy = exitPolicy,
            ),
            virtualThreads = virtualThreads,
        )

}

/**
 * Decode captured bytes with a JVM [Charset].
 *
 * @param charset charset used to decode the captured bytes.
 * @param trimLineEndings whether to remove trailing `\r` and `\n` characters.
 * @return decoded text.
 */
@PlatformSpecificExecApi
fun ExecResult.Captured.text(
    charset: Charset,
    trimLineEndings: Boolean = true,
): String {
    return text(TextEncoding.Named(charset.name()), trimLineEndings = trimLineEndings)
}

/**
 * Run a JVM-specific managed process and return [ExecOutcome] instead of throwing [ExecException].
 *
 * @param spec complete JVM-specific execution specification.
 * @return success or structured failure.
 */
@PlatformSpecificExecApi
suspend fun Exec.execOutcome(spec: JvmExecSpec): ExecOutcome =
    try {
        ExecOutcome.Success(exec(spec))
    } catch (e: ExecException) {
        ExecOutcome.Failure(e.error)
    }

/**
 * Blocking JVM-specific variant of [execOutcome].
 *
 * @param spec complete JVM-specific execution specification.
 * @param virtualThreads virtual-thread usage policy for blocking I/O workers.
 * @return success or structured failure.
 */
@PlatformSpecificExecApi
fun Exec.execBlockingOutcome(spec: JvmExecSpec, virtualThreads: VirtualThreadsPolicy = VirtualThreadsPolicy.Prefer): ExecOutcome =
    try {
        ExecOutcome.Success(execBlocking(spec, virtualThreads = virtualThreads))
    } catch (e: ExecException) {
        ExecOutcome.Failure(e.error)
    }

internal fun ExecSpec.toJvmSpecOrThrow(): JvmExecSpec =
    try {
        JvmExecSpec(
            argv = argv,
            cwd = cwd?.let(::nioPathOf),
            env = env,
            stdin = stdin.toJvmInput(),
            stdout = stdout.toJvmStdout(),
            stderr = stderr.toJvmStderr(),
            timeout = timeout,
            shutdown = shutdown,
            cleanupTimeout = cleanupTimeout,
            exitPolicy = exitPolicy,
        )
    } catch (t: Throwable) {
        Throwables.propagateIfNeeded(t)
        throw ExecException(
            ExecError.ConfigureFailed(
                meta = ExecResult.Meta(argv = argv),
                cause = t,
            )
        )
    }

private fun ExecSpec.Input.toJvmInput(): JvmExecSpec.Input =
    when (this) {
        ExecSpec.Input.None -> JvmExecSpec.Input.None
        ExecSpec.Input.Inherit -> JvmExecSpec.Input.Inherit
        is ExecSpec.Input.Bytes -> JvmExecSpec.Input.Bytes(bytes)
        is ExecSpec.Input.Text -> JvmExecSpec.Input.Text(text, encoding.toCharset())
        is ExecSpec.Input.Source -> JvmExecSpec.Input.Source(open)
        is ExecSpec.Input.WriteTo -> JvmExecSpec.Input.WriteTo(write)
        is ExecSpec.Input.FromPath -> JvmExecSpec.Input.FromPath(nioPathOf(path))
    }

private fun ExecSpec.StdoutSpec.toJvmStdout(): JvmExecSpec.StdoutSpec =
    when (this) {
        ExecSpec.StdoutSpec.Inherit -> JvmExecSpec.StdoutSpec.Inherit
        ExecSpec.StdoutSpec.Discard -> JvmExecSpec.StdoutSpec.Discard
        is ExecSpec.StdoutSpec.Pipe -> JvmExecSpec.StdoutSpec.Pipe(sink.toJvmSink())
    }

private fun ExecSpec.StderrSpec.toJvmStderr(): JvmExecSpec.StderrSpec =
    when (this) {
        ExecSpec.StderrSpec.Inherit -> JvmExecSpec.StderrSpec.Inherit
        ExecSpec.StderrSpec.Discard -> JvmExecSpec.StderrSpec.Discard
        ExecSpec.StderrSpec.ToStdout -> JvmExecSpec.StderrSpec.ToStdout
        is ExecSpec.StderrSpec.Pipe -> JvmExecSpec.StderrSpec.Pipe(sink.toJvmSink())
    }

private fun ExecSpec.SinkSpec.toJvmSink(): JvmExecSpec.SinkSpec =
    when (this) {
        is ExecSpec.SinkSpec.Capture -> JvmExecSpec.SinkSpec.Capture(maxBytes = maxBytes, keep = keep, overflow = overflow)
        is ExecSpec.SinkSpec.Stream -> JvmExecSpec.SinkSpec.Stream(
            onChunk = onChunk,
            copyChunks = copyChunks,
            maxBytes = maxBytes,
            overflow = overflow,
        )
        is ExecSpec.SinkSpec.WriteTo -> JvmExecSpec.SinkSpec.WriteTo(open = open, maxBytes = maxBytes, overflow = overflow)
        is ExecSpec.SinkSpec.File -> JvmExecSpec.SinkSpec.File(
            path = nioPathOf(path),
            write = write,
            maxBytes = maxBytes,
            overflow = overflow,
        )
        is ExecSpec.SinkSpec.Tee -> JvmExecSpec.SinkSpec.Tee(primary = primary.toJvmSink(), branches = branches.map { it.toJvmSink() })
    }

private fun SpawnSpec.toJvmSpecOrThrow(): JvmSpawnSpec =
    try {
        JvmSpawnSpec(
            argv = argv,
            cwd = cwd?.let(::nioPathOf),
            env = env,
            stdin =
                when (stdin) {
                    SpawnSpec.Input.None -> JvmSpawnSpec.Input.None
                    SpawnSpec.Input.Inherit -> JvmSpawnSpec.Input.Inherit
                },
            stdout =
                when (val out = stdout) {
                    SpawnSpec.StdoutSpec.Inherit -> JvmSpawnSpec.StdoutSpec.Inherit
                    SpawnSpec.StdoutSpec.Discard -> JvmSpawnSpec.StdoutSpec.Discard
                    is SpawnSpec.StdoutSpec.File -> JvmSpawnSpec.StdoutSpec.File(path = nioPathOf(out.path), append = out.append)
                },
            stderr =
                when (val err = stderr) {
                    SpawnSpec.StderrSpec.Inherit -> JvmSpawnSpec.StderrSpec.Inherit
                    SpawnSpec.StderrSpec.Discard -> JvmSpawnSpec.StderrSpec.Discard
                    is SpawnSpec.StderrSpec.File -> JvmSpawnSpec.StderrSpec.File(path = nioPathOf(err.path), append = err.append)
                    SpawnSpec.StderrSpec.ToStdout -> JvmSpawnSpec.StderrSpec.ToStdout
                },
            shutdown = shutdown,
        )
    } catch (t: Throwable) {
        Throwables.propagateIfNeeded(t)
        throw ExecException(
            ExecError.ConfigureFailed(
                meta = ExecResult.Meta(argv = argv),
                cause = t,
            )
        )
    }
