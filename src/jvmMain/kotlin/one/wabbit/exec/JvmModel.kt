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

@PlatformSpecificExecApi
sealed interface VirtualThreadsPolicy {
    data object Never : VirtualThreadsPolicy
    data object Prefer : VirtualThreadsPolicy
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
    val rawProcess: Process
}

/**
 * JVM-only spec for the remaining execution features that are not portable today:
 * charset-specific text input, writer-backed stdin, stream-backed stdin, and
 * direct `java.nio.file.Path` interop.
 *
 * Prefer [ExecSpec] whenever you only need `argv`, `cwd`, env, UTF-8/common
 * stdin variants, common sinks, file sinks, timeout, shutdown, and exit policy.
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
    sealed interface Input {
        data object None : Input
        data object Inherit : Input
        data class Bytes(val bytes: ByteArray) : Input
        data class Text(
            val text: String,
            val charset: Charset = StandardCharsets.UTF_8,
        ) : Input
        data class Source(val open: () -> kotlinx.io.Source) : Input
        data class WriteTo(val write: (Sink) -> Unit) : Input
        data class Writer(val write: (OutputStream) -> Unit) : Input
        data class FromPath(val path: Path) : Input
        data class FromStream(val open: () -> InputStream) : Input
    }

    sealed interface SinkSpec {
        data class Capture(
            val maxBytes: Int = 4 * 1024 * 1024,
            val keep: ExecSpec.Keep = ExecSpec.Keep.Head,
            val overflow: ExecSpec.OverflowPolicy = ExecSpec.OverflowPolicy.DrainAndTruncate,
        ) : SinkSpec

        data class Stream(
            val onChunk: (ByteArray, Int, Int) -> Unit,
            val copyChunks: Boolean = false,
            val maxBytes: Int? = null,
            val overflow: ExecSpec.OverflowPolicy = ExecSpec.OverflowPolicy.DrainAndTruncate,
        ) : SinkSpec

        data class WriteTo(
            val open: () -> Sink,
            val maxBytes: Int? = null,
            val overflow: ExecSpec.OverflowPolicy = ExecSpec.OverflowPolicy.DrainAndTruncate,
        ) : SinkSpec

        data class File(
            val path: Path,
            val write: FileWritePolicy = FileWritePolicy.Truncate(),
            val maxBytes: Int? = null,
            val overflow: ExecSpec.OverflowPolicy = ExecSpec.OverflowPolicy.KillProcess,
        ) : SinkSpec

        data class Tee(val primary: SinkSpec, val branches: List<SinkSpec>) : SinkSpec
    }

    sealed interface StdoutSpec {
        data object Inherit : StdoutSpec
        data object Discard : StdoutSpec
        data class Pipe(val sink: SinkSpec) : StdoutSpec
    }

    sealed interface StderrSpec {
        data object Inherit : StderrSpec
        data object Discard : StderrSpec
        data class Pipe(val sink: SinkSpec) : StderrSpec
        data object ToStdout : StderrSpec
    }

    companion object {
        val defaultEnvPolicy = ExecSpec.defaultEnvPolicy
        val defaultStdinSpec = Input.None
        val defaultStdoutSpec = StdoutSpec.Pipe(SinkSpec.Capture(maxBytes = 4 * 1024 * 1024, keep = ExecSpec.Keep.Head))
        val defaultStderrSpec = StderrSpec.Pipe(SinkSpec.Capture(maxBytes = 256 * 1024, keep = ExecSpec.Keep.Tail))
        val defaultShutdownPolicy = ExecSpec.defaultShutdownPolicy
        val defaultCleanupTimeout: Duration = ExecSpec.defaultCleanupTimeout
        val defaultExitPolicy = ExecSpec.defaultExitPolicy

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
    sealed interface Input {
        data object None : Input
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
        data object ToStdout : StderrSpec
    }
}

actual object Exec {
    @Throws(ExecException::class)
    actual suspend fun exec(
        spec: ExecSpec,
        ioDispatcher: CoroutineDispatcher,
    ): ExecResult =
        execInternal(spec.toJvmSpecOrThrow(), ioDispatcher = ioDispatcher)

    @Throws(ExecException::class)
    @PlatformSpecificExecApi
    suspend fun exec(spec: JvmExecSpec, ioDispatcher: CoroutineDispatcher = Dispatchers.IO): ExecResult =
        execInternal(spec, ioDispatcher = ioDispatcher)

    @Throws(ExecException::class)
    actual fun execBlocking(spec: ExecSpec): ExecResult =
        execBlockingInternal(spec.toJvmSpecOrThrow(), virtualThreads = VirtualThreadsPolicy.Prefer)

    @Throws(ExecException::class)
    @PlatformSpecificExecApi
    fun execBlocking(spec: ExecSpec, virtualThreads: VirtualThreadsPolicy = VirtualThreadsPolicy.Prefer): ExecResult =
        execBlockingInternal(spec.toJvmSpecOrThrow(), virtualThreads = virtualThreads)

    @Throws(ExecException::class)
    @PlatformSpecificExecApi
    fun execBlocking(spec: JvmExecSpec, virtualThreads: VirtualThreadsPolicy = VirtualThreadsPolicy.Prefer): ExecResult =
        execBlockingInternal(spec, virtualThreads = virtualThreads)

    @Throws(ExecException::class)
    actual suspend fun spawn(
        spec: SpawnSpec,
        ioDispatcher: CoroutineDispatcher,
    ): RunningProcess =
        spawnInternal(spec.toJvmSpecOrThrow(), ioDispatcher = ioDispatcher)

    @Throws(ExecException::class)
    @PlatformSpecificExecApi
    suspend fun spawn(
        spec: JvmSpawnSpec,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): RunningProcess =
        spawnInternal(spec, ioDispatcher = ioDispatcher)

    @Throws(ExecException::class)
    actual fun spawnBlocking(spec: SpawnSpec): RunningProcess =
        spawnBlockingInternal(spec.toJvmSpecOrThrow())

    @Throws(ExecException::class)
    @PlatformSpecificExecApi
    fun spawnBlocking(spec: JvmSpawnSpec): RunningProcess =
        spawnBlockingInternal(spec)

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

@PlatformSpecificExecApi
fun ExecResult.Captured.text(
    charset: Charset,
    trimLineEndings: Boolean = true,
): String {
    return text(TextEncoding.Named(charset.name()), trimLineEndings = trimLineEndings)
}

@PlatformSpecificExecApi
suspend fun Exec.execOutcome(spec: JvmExecSpec): ExecOutcome =
    try {
        ExecOutcome.Success(exec(spec))
    } catch (e: ExecException) {
        ExecOutcome.Failure(e.error)
    }

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
