package one.wabbit.exec

enum class Phase {
    ConfigureProcessBuilder,
    Spawn,
    WriteStdin,
    ReadStdout,
    ReadStderr,
    AwaitExit,
    KillTree,
    Cleanup,
}

enum class StreamId { STDIN, STDOUT, STDERR }

sealed interface ExecError {
    val meta: ExecResult.Meta
    val phase: Phase
    val message: String
    val cause: Throwable?
    val captures: ExecResult.Captures?

    data class ConfigureFailed(
        override val meta: ExecResult.Meta,
        override val cause: Throwable,
        override val message: String = cause.message ?: "Failed to configure ProcessBuilder",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase = Phase.ConfigureProcessBuilder
    }

    data class SpawnFailed(
        override val meta: ExecResult.Meta,
        override val cause: Throwable,
        override val message: String = cause.message ?: "Failed to spawn process",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase = Phase.Spawn
    }

    data class InputProviderFailed(
        override val meta: ExecResult.Meta,
        override val cause: Throwable,
        override val message: String = cause.message ?: "stdin provider failed",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase = Phase.WriteStdin
    }

    data class StdinWriteFailed(
        override val meta: ExecResult.Meta,
        override val cause: Throwable,
        override val message: String = cause.message ?: "Failed writing stdin",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase = Phase.WriteStdin
    }

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

    data class OutputLimitExceeded(
        override val meta: ExecResult.Meta,
        val stream: StreamId,
        val limitBytes: Int,
        val observedBytes: Long,
        override val message: String = "$stream exceeded output limit ($observedBytes > $limitBytes bytes)",
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

    data class Cancelled(
        override val meta: ExecResult.Meta,
        override val cause: Throwable?,
        val exitCodeAfterKill: Int? = null,
        override val message: String = "Cancelled",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase = Phase.AwaitExit
    }

    data class WaitFailed(
        override val meta: ExecResult.Meta,
        override val cause: Throwable,
        override val message: String = cause.message ?: "Failed while awaiting exit",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase = Phase.AwaitExit
    }

    data class KillFailed(
        override val meta: ExecResult.Meta,
        override val cause: Throwable,
        override val message: String = cause.message ?: "Failed to kill process tree",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase = Phase.KillTree
    }

    data class CleanupFailed(
        override val meta: ExecResult.Meta,
        override val cause: Throwable?,
        override val message: String = cause?.message ?: "Cleanup failed",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase = Phase.Cleanup
    }

    data class ExitNonZero(
        override val meta: ExecResult.Meta,
        val exitCode: Int,
        override val message: String = "Exited non-zero: $exitCode",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError {
        override val phase: Phase = Phase.AwaitExit
        override val cause: Throwable? = null

        val maybeSignal: Int? = exitCode.takeIf { it in 128..255 }?.minus(128)
    }

    data class Unexpected(
        override val meta: ExecResult.Meta,
        override val phase: Phase,
        override val cause: Throwable,
        override val message: String = cause.message ?: "Unexpected failure",
        override val captures: ExecResult.Captures? = null,
    ) : ExecError
}

class ExecException(val error: ExecError) : RuntimeException(error.message, error.cause)
