package one.wabbit.exec

import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.files.Path

@JvmInline value class ExitCode(val value: Int)

enum class ExitPolicy {
    Return,
    ThrowOnNonZero,
}

sealed interface EnvPolicy {
    data class Inherit(val overlay: Map<String, String> = emptyMap()) : EnvPolicy
    data class Hermetic(val base: Map<String, String> = emptyMap()) : EnvPolicy
    data class ClearAndSet(val base: Map<String, String> = emptyMap()) : EnvPolicy
}

sealed interface ShutdownPolicy {
    data object KillTree : ShutdownPolicy
    data class TerminateThenKillTree(val grace: Duration = 500.milliseconds) : ShutdownPolicy
}

sealed interface FileWritePolicy {
    val eager: Boolean

    data class Truncate(override val eager: Boolean = true) : FileWritePolicy
    data class Append(override val eager: Boolean = true) : FileWritePolicy
}

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
    sealed interface Input {
        data object None : Input
        data object Inherit : Input
        data class Bytes(val bytes: ByteArray) : Input
        data class Text(
            val text: String,
            val encoding: TextEncoding = TextEncoding.Utf8,
        ) : Input
        data class Source(val open: () -> kotlinx.io.Source) : Input
        data class WriteTo(val write: (Sink) -> Unit) : Input
        data class FromPath(val path: Path) : Input
    }

    enum class OverflowPolicy {
        DrainAndTruncate,
        KillProcess,
    }

    enum class Keep {
        Head,
        Tail,
    }

    sealed interface SinkSpec {
        data class Capture(
            val maxBytes: Int = 4 * 1024 * 1024,
            val keep: Keep = Keep.Head,
            val overflow: OverflowPolicy = OverflowPolicy.DrainAndTruncate,
        ) : SinkSpec

        data class Stream(
            val onChunk: (ByteArray, Int, Int) -> Unit,
            val copyChunks: Boolean = false,
            val maxBytes: Int? = null,
            val overflow: OverflowPolicy = OverflowPolicy.DrainAndTruncate,
        ) : SinkSpec

        data class WriteTo(
            val open: () -> Sink,
            val maxBytes: Int? = null,
            val overflow: OverflowPolicy = OverflowPolicy.DrainAndTruncate,
        ) : SinkSpec

        data class File(
            val path: Path,
            val write: FileWritePolicy = FileWritePolicy.Truncate(),
            val maxBytes: Int? = null,
            val overflow: OverflowPolicy = OverflowPolicy.KillProcess,
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
        val defaultEnvPolicy = EnvPolicy.Inherit()
        val defaultStdinSpec = Input.None
        val defaultStdoutSpec = StdoutSpec.Pipe(SinkSpec.Capture(maxBytes = 4 * 1024 * 1024, keep = Keep.Head))
        val defaultStderrSpec = StderrSpec.Pipe(SinkSpec.Capture(maxBytes = 256 * 1024, keep = Keep.Tail))
        val defaultShutdownPolicy = ShutdownPolicy.TerminateThenKillTree()
        val defaultCleanupTimeout: Duration = 2.seconds
        val defaultExitPolicy = ExitPolicy.Return

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
            encoding: TextEncoding = TextEncoding.Utf8,
            trimLineEndings: Boolean = true,
        ): String {
            val value = TextEncodingPlatform.decode(bytes, encoding)
            return if (!trimLineEndings) value else value.trimEnd('\r', '\n')
        }
    }

    data class OutputStats(
        val bytesRead: Long,
        val truncated: Boolean,
    )

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

sealed interface ExecOutcome {
    data class Success(val result: ExecResult) : ExecOutcome
    data class Failure(val error: ExecError) : ExecOutcome
}

data class SpawnSpec(
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

sealed interface AwaitExitOutcome {
    data class Exited(val code: ExitCode) : AwaitExitOutcome
    data object TimedOut : AwaitExitOutcome
    data object Interrupted : AwaitExitOutcome
}

interface RunningProcess {
    val pid: Long?

    fun isAlive(): Boolean

    fun exitCodeOrNull(): ExitCode?

    fun killTree()

    fun awaitExitBlockingOutcome(timeout: Duration? = null): AwaitExitOutcome

    fun awaitExitBlocking(timeout: Duration? = null): ExitCode?

    suspend fun awaitExitOutcome(timeout: Duration? = null): AwaitExitOutcome

    suspend fun awaitExit(timeout: Duration? = null): ExitCode?
}
