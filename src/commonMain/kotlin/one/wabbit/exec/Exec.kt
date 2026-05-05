// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.exec

import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.io.files.Path

/**
 * Cross-platform process execution entry point.
 *
 * The current published implementation targets JVM, but the common API is shaped so portable
 * callers can use [ExecSpec], [SpawnSpec], and `kotlinx-io` paths without depending on JVM-only
 * types.
 */
expect object Exec {
    /**
     * Run [spec] as a managed process from a coroutine.
     *
     * Managed execution owns the child lifecycle. Timeout or coroutine cancellation terminates the
     * process tree using [ExecSpec.shutdown].
     *
     * @param spec complete managed execution specification.
     * @param ioDispatcher dispatcher used for blocking process and I/O work.
     * @return completed process result.
     * @throws ExecException for spawn, I/O, timeout, cancellation cleanup, output-limit, and
     *   non-zero-exit failures when requested by [ExecSpec.exitPolicy].
     */
    @Throws(ExecException::class)
    suspend fun exec(spec: ExecSpec, ioDispatcher: CoroutineDispatcher = Dispatchers.IO): ExecResult

    /**
     * Run [spec] as a managed process and block the current thread.
     *
     * @param spec complete managed execution specification.
     * @return completed process result.
     * @throws ExecException for structured execution failures.
     */
    @Throws(ExecException::class) fun execBlocking(spec: ExecSpec): ExecResult

    /**
     * Spawn a process and return a handle without waiting for completion.
     *
     * Spawned processes are not killed by later wait timeouts. The caller owns the returned
     * [RunningProcess] and should call [RunningProcess.killTree] when termination is required.
     *
     * @param spec spawn specification.
     * @param ioDispatcher dispatcher used for blocking process startup work.
     * @return running process handle.
     * @throws ExecException when process configuration or startup fails.
     */
    @Throws(ExecException::class)
    suspend fun spawn(
        spec: SpawnSpec,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): RunningProcess

    /**
     * Spawn a process from a blocking call and return a handle without waiting for completion.
     *
     * @param spec spawn specification.
     * @return running process handle.
     * @throws ExecException when process configuration or startup fails.
     */
    @Throws(ExecException::class) fun spawnBlocking(spec: SpawnSpec): RunningProcess

    /**
     * Convenience overload for managed coroutine execution without manually constructing
     * [ExecSpec].
     *
     * @param argv command and arguments.
     * @param cwd optional working directory.
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

    /**
     * Convenience overload for managed blocking execution without manually constructing [ExecSpec].
     *
     * @param argv command and arguments.
     * @param cwd optional working directory.
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

/**
 * Run [spec] and return [ExecOutcome] instead of throwing [ExecException].
 *
 * Fatal platform errors and coroutine cancellation still propagate according to the underlying
 * execution engine; this helper only converts ordinary [ExecException] failures.
 *
 * @param spec complete managed execution specification.
 * @return success or structured failure.
 */
suspend fun Exec.execOutcome(spec: ExecSpec): ExecOutcome =
    try {
        ExecOutcome.Success(exec(spec))
    } catch (e: ExecException) {
        ExecOutcome.Failure(e.error)
    }

/**
 * Blocking variant of [execOutcome].
 *
 * @param spec complete managed execution specification.
 * @return success or structured failure.
 */
fun Exec.execBlockingOutcome(spec: ExecSpec): ExecOutcome =
    try {
        ExecOutcome.Success(execBlocking(spec))
    } catch (e: ExecException) {
        ExecOutcome.Failure(e.error)
    }
