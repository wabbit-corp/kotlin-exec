// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.exec

/** Execution phase associated with an [ExecError]. */
enum class Phase {
    /** Building or validating the process configuration failed before spawn. */
    ConfigureProcessBuilder,

    /** Starting the process failed. */
    Spawn,

    /** Providing or writing stdin failed. */
    WriteStdin,

    /** Reading or consuming stdout failed. */
    ReadStdout,

    /** Reading or consuming stderr failed. */
    ReadStderr,

    /** Waiting for the process to exit failed or timed out. */
    AwaitExit,

    /** Process-tree termination failed. */
    KillTree,

    /** Cleanup after exit or failure failed. */
    Cleanup,
}

/** Identifier for a process stream. */
enum class StreamId {
    /** Child standard input. */
    STDIN,

    /** Child standard output. */
    STDOUT,

    /** Child standard error. */
    STDERR,
}

/**
 * Structured failure reported by `kotlin-exec`.
 *
 * Each error records the process [meta], failure [phase], human-readable [message], optional
 * underlying [cause], and any [captures] available when the failure was reported.
 */
sealed interface ExecError {
    /** Command metadata available for the failed run. */
    val meta: ExecResult.Meta

    /** Execution phase where the failure occurred. */
    val phase: Phase

    /** Human-readable diagnostic message. */
    val message: String

    /** Underlying cause, when the failure wraps another exception. */
    val cause: Throwable?

    /** Captured stdout/stderr available at failure time. */
    val captures: ExecResult.Captures?

    /**
     * Process configuration failed before spawn.
     *
     * @property meta command metadata.
     * @property cause underlying configuration failure.
     * @property message human-readable diagnostic.
     * @property captures captures available at failure time.
     */
    data class ConfigureFailed(
        override val meta: ExecResult.Meta,
        override val cause: Throwable,
        override val message: String = cause.message ?: "Failed to configure ProcessBuilder",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase = Phase.ConfigureProcessBuilder
    }

    /**
     * Process startup failed after configuration.
     *
     * @property meta command metadata.
     * @property cause underlying startup failure.
     * @property message human-readable diagnostic.
     * @property captures captures available at failure time.
     */
    data class SpawnFailed(
        override val meta: ExecResult.Meta,
        override val cause: Throwable,
        override val message: String = cause.message ?: "Failed to spawn process",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase = Phase.Spawn
    }

    /**
     * A caller-supplied stdin provider failed before bytes could be written.
     *
     * @property meta command metadata.
     * @property cause provider failure.
     * @property message human-readable diagnostic.
     * @property captures captures available at failure time.
     */
    data class InputProviderFailed(
        override val meta: ExecResult.Meta,
        override val cause: Throwable,
        override val message: String = cause.message ?: "stdin provider failed",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase = Phase.WriteStdin
    }

    /**
     * Writing bytes to child stdin failed.
     *
     * @property meta command metadata.
     * @property cause write failure.
     * @property message human-readable diagnostic.
     * @property captures captures available at failure time.
     */
    data class StdinWriteFailed(
        override val meta: ExecResult.Meta,
        override val cause: Throwable,
        override val message: String = cause.message ?: "Failed writing stdin",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase = Phase.WriteStdin
    }

    /**
     * A caller-supplied output consumer callback failed.
     *
     * @property meta command metadata.
     * @property stream output stream being consumed.
     * @property cause callback failure.
     * @property message human-readable diagnostic.
     * @property captures captures available at failure time.
     */
    data class OutputConsumerFailed(
        override val meta: ExecResult.Meta,
        val stream: StreamId,
        override val cause: Throwable,
        override val message: String = cause.message ?: "Output consumer failed for $stream",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase =
            when (stream) {
                StreamId.STDOUT -> Phase.ReadStdout
                StreamId.STDERR -> Phase.ReadStderr
                StreamId.STDIN -> Phase.Cleanup
            }
    }

    /**
     * A configured output sink failed while receiving or finalizing process output.
     *
     * @property meta command metadata.
     * @property stream output stream being sunk.
     * @property cause sink failure.
     * @property message human-readable diagnostic.
     * @property captures captures available at failure time.
     */
    data class OutputSinkFailed(
        override val meta: ExecResult.Meta,
        val stream: StreamId,
        override val cause: Throwable,
        override val message: String = cause.message ?: "Output sink failed for $stream",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase =
            when (stream) {
                StreamId.STDOUT -> Phase.ReadStdout
                StreamId.STDERR -> Phase.ReadStderr
                StreamId.STDIN -> Phase.Cleanup
            }
    }

    /**
     * Reading stdout or stderr from the process failed.
     *
     * @property meta command metadata.
     * @property stream process stream that failed.
     * @property cause read failure.
     * @property message human-readable diagnostic.
     * @property captures captures available at failure time.
     */
    data class StreamReadFailed(
        override val meta: ExecResult.Meta,
        val stream: StreamId,
        override val cause: Throwable,
        override val message: String = cause.message ?: "Failed reading $stream",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase =
            when (stream) {
                StreamId.STDOUT -> Phase.ReadStdout
                StreamId.STDERR -> Phase.ReadStderr
                StreamId.STDIN -> Phase.Cleanup
            }
    }

    /**
     * A hard output limit was exceeded.
     *
     * This error is produced when a sink has [ExecSpec.OverflowPolicy.KillProcess] and the stream
     * emits more than [limitBytes].
     *
     * @property meta command metadata.
     * @property stream stream that exceeded its limit.
     * @property limitBytes configured byte limit.
     * @property observedBytes bytes observed when the limit was reported.
     * @property message human-readable diagnostic.
     * @property captures captures available at failure time.
     */
    data class OutputLimitExceeded(
        override val meta: ExecResult.Meta,
        val stream: StreamId,
        val limitBytes: Int,
        val observedBytes: Long,
        override val message: String =
            "$stream exceeded output limit ($observedBytes > $limitBytes bytes)",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase =
            when (stream) {
                StreamId.STDOUT -> Phase.ReadStdout
                StreamId.STDERR -> Phase.ReadStderr
                StreamId.STDIN -> Phase.Cleanup
            }
        override val cause: Throwable? = null
    }

    /**
     * Managed execution exceeded its configured timeout.
     *
     * The execution engine terminates the process tree before reporting this error.
     *
     * @property meta command metadata.
     * @property timeoutMs timeout in milliseconds.
     * @property exitCodeAfterKill best-effort exit code observed after termination.
     * @property message human-readable diagnostic.
     * @property captures captures available at failure time.
     */
    data class TimedOut(
        override val meta: ExecResult.Meta,
        val timeoutMs: Long,
        val exitCodeAfterKill: Int? = null,
        override val message: String = "Timed out after ${timeoutMs}ms",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase = Phase.AwaitExit
        override val cause: Throwable? = null
    }

    /**
     * Managed execution was cancelled or interrupted before it could complete normally.
     *
     * Coroutine cancellation propagates through suspend APIs where appropriate; this error records
     * cancellation-shaped failures that are represented as [ExecException].
     *
     * @property meta command metadata.
     * @property cause cancellation or interruption cause.
     * @property exitCodeAfterKill best-effort exit code observed after termination.
     * @property message human-readable diagnostic.
     * @property captures captures available at failure time.
     */
    data class Cancelled(
        override val meta: ExecResult.Meta,
        override val cause: Throwable?,
        val exitCodeAfterKill: Int? = null,
        override val message: String = "Cancelled",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase = Phase.AwaitExit
    }

    /**
     * Waiting for process exit failed for a reason other than timeout or cancellation.
     *
     * @property meta command metadata.
     * @property cause wait failure.
     * @property message human-readable diagnostic.
     * @property captures captures available at failure time.
     */
    data class WaitFailed(
        override val meta: ExecResult.Meta,
        override val cause: Throwable,
        override val message: String = cause.message ?: "Failed while awaiting exit",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase = Phase.AwaitExit
    }

    /**
     * Terminating a process tree failed.
     *
     * @property meta command metadata.
     * @property cause termination failure.
     * @property message human-readable diagnostic.
     * @property captures captures available at failure time.
     */
    data class KillFailed(
        override val meta: ExecResult.Meta,
        override val cause: Throwable,
        override val message: String = cause.message ?: "Failed to kill process tree",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase = Phase.KillTree
    }

    /**
     * Cleanup exceeded its budget or failed after process exit or termination.
     *
     * @property meta command metadata.
     * @property cause cleanup failure when one is available.
     * @property message human-readable diagnostic.
     * @property captures captures available at failure time.
     */
    data class CleanupFailed(
        override val meta: ExecResult.Meta,
        override val cause: Throwable?,
        override val message: String = cause?.message ?: "Cleanup failed",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase = Phase.Cleanup
    }

    /**
     * Process exited with a non-zero status while [ExitPolicy.ThrowOnNonZero] or
     * [ExecResult.requireOk] requested exception-based handling.
     *
     * @property meta command metadata.
     * @property exitCode non-zero process exit code.
     * @property message human-readable diagnostic.
     * @property captures captures available at failure time.
     */
    data class ExitNonZero(
        override val meta: ExecResult.Meta,
        val exitCode: Int,
        override val message: String = "Exited non-zero: $exitCode",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase = Phase.AwaitExit
        override val cause: Throwable? = null

        /**
         * Best-effort Unix signal number for exit codes in the conventional `128 + signal` range.
         */
        val maybeSignal: Int? = exitCode.takeIf { it in 128..255 }?.minus(128)
    }

    /**
     * Unexpected failure that does not fit a more specific error type.
     *
     * @property meta command metadata.
     * @property phase phase where the unexpected failure occurred.
     * @property cause underlying failure.
     * @property message human-readable diagnostic.
     * @property captures captures available at failure time.
     */
    data class Unexpected(
        override val meta: ExecResult.Meta,
        override val phase: Phase,
        override val cause: Throwable,
        override val message: String = cause.message ?: "Unexpected failure",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError
}

/**
 * Exception wrapper for [ExecError].
 *
 * Throwing APIs use this exception so callers can catch one type and still inspect structured
 * failure details through [error].
 *
 * @property error structured execution failure.
 */
class ExecException(val error: ExecError) : RuntimeException(error.message, error.cause)
