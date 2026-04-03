package one.wabbit.exec

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.io.files.Path
import kotlin.time.Duration

expect object Exec {
    @Throws(ExecException::class)
    suspend fun exec(
        spec: ExecSpec,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): ExecResult

    @Throws(ExecException::class)
    fun execBlocking(spec: ExecSpec): ExecResult

    @Throws(ExecException::class)
    suspend fun spawn(
        spec: SpawnSpec,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): RunningProcess

    @Throws(ExecException::class)
    fun spawnBlocking(spec: SpawnSpec): RunningProcess

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
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): ExecResult

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
    ): ExecResult
}

suspend fun Exec.execOutcome(spec: ExecSpec): ExecOutcome =
    try {
        ExecOutcome.Success(exec(spec))
    } catch (e: ExecException) {
        ExecOutcome.Failure(e.error)
    }

fun Exec.execBlockingOutcome(spec: ExecSpec): ExecOutcome =
    try {
        ExecOutcome.Success(execBlocking(spec))
    } catch (e: ExecException) {
        ExecOutcome.Failure(e.error)
    }
