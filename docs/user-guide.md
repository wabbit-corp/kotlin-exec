# User Guide

`kotlin-exec` centralizes JVM process execution patterns that are easy to get wrong with direct
`ProcessBuilder` use:

- draining stdout and stderr concurrently
- bounding captured output
- writing stdin safely
- killing process trees on managed timeout or cancellation
- reporting structured failures instead of stringly typed exceptions

## Managed Runs

Managed execution waits for the child to finish and owns cleanup:

```kotlin
import one.wabbit.exec.Exec
import one.wabbit.exec.ExecSpec

val result = Exec.execBlocking(
    ExecSpec.tooling(argv = listOf("git", "rev-parse", "--short", "HEAD"))
)

println(result.stdout?.text())
```

`ExecSpec.tooling` is a convenient default for CLI tools. It captures stdout from the head and
stderr from the tail, which keeps diagnostics useful even when stderr is noisy.

## Exit Handling

By default, non-zero exit codes are returned in `ExecResult`:

```kotlin
import one.wabbit.exec.Exec
import one.wabbit.exec.ExecSpec

val result = Exec.execBlocking(ExecSpec.tooling(listOf("false")))
check(!result.ok)
```

Set `ExitPolicy.ThrowOnNonZero` when non-zero exit should be treated as an exception:

```kotlin
import one.wabbit.exec.ExitPolicy

val spec = ExecSpec.tooling(
    argv = listOf("false"),
    exitPolicy = ExitPolicy.ThrowOnNonZero
)
```

You can also call `result.requireOk()` after inspecting captures.

## Structured Failures

Throwing APIs report ordinary execution failures as `ExecException`:

```kotlin
import one.wabbit.exec.ExecException
import one.wabbit.exec.ExecError

try {
    Exec.execBlocking(spec)
} catch (e: ExecException) {
    when (val error = e.error) {
        is ExecError.TimedOut -> println("timed out after ${error.timeoutMs}ms")
        is ExecError.ExitNonZero -> println("exit code ${error.exitCode}")
        else -> println(error.message)
    }
}
```

Use `Exec.execOutcome` or `Exec.execBlockingOutcome` if the call site prefers an explicit success or
failure value.

```kotlin
import one.wabbit.exec.Exec
import one.wabbit.exec.ExecOutcome

when (val outcome = Exec.execBlockingOutcome(spec)) {
    is ExecOutcome.Success -> println(outcome.result.stdout?.text())
    is ExecOutcome.Failure -> println(outcome.error.message)
}
```

## Stdin

Use `ExecSpec.Input.Text` or `ExecSpec.Input.Bytes` for small payloads:

```kotlin
val spec = ExecSpec.tooling(
    argv = listOf("wc", "-c"),
    stdin = ExecSpec.Input.Text("hello")
)
```

Use `ExecSpec.Input.Source`, `WriteTo`, or `FromPath` when the payload should be streamed instead of
held in memory.

JVM callers can opt in to `@PlatformSpecificExecApi` and use `JvmExecSpec.Input.FromStream`,
`JvmExecSpec.Input.Writer`, or the adapter helpers `execInputFromStream` and `execInputWriteTo`.

## Output Sinks

`Capture` retains bytes in memory:

```kotlin
val stdout = ExecSpec.StdoutSpec.Pipe(
    ExecSpec.SinkSpec.Capture(
        maxBytes = 64 * 1024,
        keep = ExecSpec.Keep.Tail
    )
)
```

`ExecResult.stdout` and `ExecResult.stderr` are populated only when the corresponding stream uses
`Capture` or a `Tee` whose primary sink captures. `Stream`, `WriteTo`, `File`, `Inherit`, and
`Discard` do not retain bytes in the result.

`Stream` invokes a callback as chunks arrive. Set `copyChunks = true` if the callback keeps the
buffer after it returns.

`WriteTo` writes output to a caller-supplied `kotlinx.io.Sink`. Use it when another component owns
the destination and the command runner should only pump process output into that sink.

`File` writes output to disk. The default is eager truncation, which prevents stale content when a
command produces no output. Set `FileWritePolicy.Truncate(eager = false)` or
`FileWritePolicy.Append(eager = false)` when lazy file creation is required.

`Tee` duplicates the same stream to a primary sink and side-effect branches. The primary sink decides
which capture appears in `ExecResult`. Branches are part of managed output pumping: sink and callback
failures are reported as execution failures instead of being ignored.

## Timeouts And Cancellation

Managed timeouts are destructive:

```kotlin
import kotlin.time.Duration.Companion.seconds

val spec = ExecSpec.tooling(
    argv = listOf("sleep", "10"),
    timeout = 1.seconds
)
```

When the timeout elapses, `kotlin-exec` terminates the process tree and throws `ExecException`
wrapping `ExecError.TimedOut`.

`ShutdownPolicy.TerminateThenKillTree` is the default. It gives the process tree a grace period
before force-killing it:

```kotlin
import one.wabbit.exec.ShutdownPolicy
import kotlin.time.Duration.Companion.seconds

val spec = ExecSpec.tooling(
    argv = listOf("my-tool"),
    timeout = 30.seconds,
    shutdown = ShutdownPolicy.TerminateThenKillTree(grace = 2.seconds),
    cleanupTimeout = 5.seconds
)
```

Use `ShutdownPolicy.KillTree` when graceful termination is not useful. `cleanupTimeout` is the total
budget for termination cleanup and stream or sink finalization after the child is no longer allowed
to run normally; choose a value that can cover the configured grace period and any output-drain work.

Coroutine cancellation for `Exec.exec` is also destructive. The implementation installs cancellation
cleanup so a process started near a cancellation race is not orphaned.

## Spawned Processes

Spawn returns a handle immediately:

```kotlin
import one.wabbit.exec.Exec
import one.wabbit.exec.SpawnSpec
import kotlin.time.Duration.Companion.seconds

val process = Exec.spawnBlocking(SpawnSpec(argv = listOf("sleep", "30")))
val exit = process.awaitExitBlocking(1.seconds)

if (exit == null) {
    process.killTree()
}
```

Spawn wait timeouts are non-destructive. If `awaitExitBlocking` or `awaitExit` returns `null`, the
process may still be alive.

Use the outcome-returning wait methods when the caller needs to distinguish timeout, interruption,
and a normal exit:

```kotlin
import one.wabbit.exec.AwaitExitOutcome

when (val outcome = process.awaitExitBlockingOutcome(1.seconds)) {
    is AwaitExitOutcome.Exited -> println("exit ${outcome.code.value}")
    AwaitExitOutcome.TimedOut -> println("still running")
    AwaitExitOutcome.Interrupted -> println("wait was interrupted")
}
```

Coroutine cancellation before spawn prevents the process from starting. Cancellation during startup
kills the child before propagating cancellation. Cancellation while waiting with `awaitExit` does not
kill an already-spawned process.

Current spawn specs support inherit, discard, and file redirects. They do not capture output in
memory. See [the non-destructive timeout design note](../TRY_REMOTE_ISSUE.md) for the known hybrid
case where callers need both managed capture and non-destructive timeout.

## Environment Policies

Use `EnvPolicy.Inherit` for most commands:

```kotlin
val env = EnvPolicy.Inherit(mapOf("CI" to "true"))
```

Use `EnvPolicy.Hermetic` for commands that should not inherit the full parent environment but still
need minimal launch variables.

On Unix-like platforms, hermetic execution supplies `PATH=/usr/bin:/bin` and `TMPDIR` from the parent
environment or the JVM temporary directory. On Windows, it supplies `SystemRoot`, `ComSpec`, `PATH`,
and `PATHEXT` from the parent environment when available, with conservative system defaults
otherwise.

Use `EnvPolicy.ClearAndSet` only when the command can run with exactly the supplied variables.

## JVM-Specific APIs

The JVM-specific surface is opt-in:

```kotlin
import one.wabbit.exec.PlatformSpecificExecApi

@OptIn(PlatformSpecificExecApi::class)
fun runJvmSpecific() {
    // use JvmExecSpec, JvmSpawnSpec, Charset decoding, or VirtualThreadsPolicy here
}
```

`JvmRunningProcess` exposes the raw JVM `Process` when integration code needs APIs not modeled by the
portable `RunningProcess` handle:

```kotlin
import one.wabbit.exec.JvmRunningProcess

val rawProcess = (process as? JvmRunningProcess)?.rawProcess
```

The opt-in is a signal that the code is intentionally not portable to future non-JVM actuals.
