# API Reference

Generate exact signatures locally with:

```bash
./gradlew dokkaGeneratePublicationHtml
```

This page documents the public surface and the behavioral contracts that matter at call sites.

## Common Execution API

- `Exec`: process execution entry point.
- `Exec.exec(spec, ioDispatcher)`: suspend managed execution.
- `Exec.execBlocking(spec)`: blocking managed execution.
- `Exec.spawn(spec, ioDispatcher)`: suspend spawn that returns `RunningProcess`.
- `Exec.spawnBlocking(spec)`: blocking spawn that returns `RunningProcess`.
- `Exec.exec(...)` and `Exec.execBlocking(...)`: convenience overloads for building `ExecSpec`.
- `Exec.execOutcome(spec)` and `Exec.execBlockingOutcome(spec)`: convert `ExecException` into
  `ExecOutcome.Failure`.
- `ExecOutcome.Success`: successful managed execution result.
- `ExecOutcome.Failure`: structured managed execution failure containing `ExecError`, without
  throwing.

Managed execution owns the process lifecycle. Spawned execution returns ownership to the caller.

## ExecSpec

`ExecSpec` describes a managed run:

- `argv`: non-empty command and arguments.
- `cwd`: optional `kotlinx.io.files.Path` working directory.
- `env`: `EnvPolicy`.
- `stdin`: `ExecSpec.Input`.
- `stdout`: `ExecSpec.StdoutSpec`.
- `stderr`: `ExecSpec.StderrSpec`.
- `timeout`: optional managed timeout.
- `shutdown`: process-tree termination policy.
- `cleanupTimeout`: cleanup and I/O join budget.
- `exitPolicy`: non-zero exit behavior.

`ExecSpec.tooling` builds a bounded capture spec intended for compilers, CLIs, and build tools.

The raw constructor defaults to stdout head capture up to 4 MiB and stderr tail capture up to 256
KiB. `ExecSpec.tooling` keeps stdout head capture at 4 MiB and increases stderr tail capture to 1
MiB.

`ExitPolicy` controls non-zero exits:

- `ExitPolicy.Return`: return an `ExecResult` with `ok == false`.
- `ExitPolicy.ThrowOnNonZero`: throw `ExecException` with `ExecError.ExitNonZero`.

`ShutdownPolicy` controls destructive cleanup after managed timeout, cancellation, or output-limit
failure:

- `ShutdownPolicy.TerminateThenKillTree(grace)`: request graceful termination, wait for `grace`, then
  force-kill the process tree. This is the default with a 500 millisecond grace period.
- `ShutdownPolicy.KillTree`: force-kill the process tree immediately.

`cleanupTimeout` should exceed the configured `TerminateThenKillTree` grace period so the runner has
time to finish termination cleanup and output-drain work.

## Input

Managed stdin variants:

- `Input.None`: close stdin immediately.
- `Input.Inherit`: inherit parent stdin.
- `Input.Bytes`: write an in-memory byte array.
- `Input.Text`: encode text with `TextEncoding`.
- `Input.Source`: stream from `kotlinx.io.Source`.
- `Input.WriteTo`: write through `kotlinx.io.Sink`.
- `Input.FromPath`: stream bytes from a `kotlinx.io.files.Path`.

## Output

Managed output sinks:

- `SinkSpec.Capture`: retain a bounded head or tail in memory.
- `SinkSpec.Stream`: invoke a callback for chunks as they are read. Set `copyChunks = true` when the
  callback retains the buffer after returning.
- `SinkSpec.WriteTo`: write to a caller-supplied `kotlinx.io.Sink`.
- `SinkSpec.File`: write to a filesystem path.
- `SinkSpec.Tee`: duplicate output to a primary sink and branches. The primary sink controls the
  capture attached to `ExecResult`; branch sink or callback failures are reported as execution
  failures.

`OverflowPolicy.DrainAndTruncate` keeps the process running while discarding output past the limit.
`OverflowPolicy.KillProcess` reports `ExecError.OutputLimitExceeded` and terminates the process.

`ExecResult.stdout` and `ExecResult.stderr` are non-null only for streams routed through
`SinkSpec.Capture` or a `SinkSpec.Tee` whose primary sink captures. Streaming, file, inherited, and
discarded streams report stats when pumped but do not retain bytes.

## Results

- `ExecResult`: completed process result.
- `ExecResult.Meta`: argv and optional pid.
- `ExecResult.Captured`: retained output bytes, truncation flag, and total bytes read.
- `ExecResult.Captured.text(...)`: decode captured bytes.
- `ExecResult.OutputStats`: byte counters for a stream.
- `ExecResult.Captures`: captures attached to errors.
- `ExecResult.ok`: `true` when exit code is zero.
- `ExecResult.requireOk()`: throws `ExecException` wrapping `ExecError.ExitNonZero` for non-zero
  exit.

## Errors

`ExecException` wraps an `ExecError`. Error types include:

- `ConfigureFailed`
- `SpawnFailed`
- `InputProviderFailed`
- `StdinWriteFailed`
- `OutputConsumerFailed`
- `OutputSinkFailed`
- `StreamReadFailed`
- `OutputLimitExceeded`
- `TimedOut`
- `Cancelled`
- `WaitFailed`
- `KillFailed`
- `CleanupFailed`
- `ExitNonZero`
- `Unexpected`

Each error includes `meta`, `phase`, `message`, optional `cause`, and optional captures.

## Spawn API

`SpawnSpec` describes an unmanaged spawned process:

- `argv`
- `cwd`
- `env`
- `stdin`
- `stdout`
- `stderr`
- `shutdown`

Spawn stdin variants:

- `SpawnSpec.Input.None`
- `SpawnSpec.Input.Inherit`

Spawn stdin intentionally does not support piped, streaming, or in-memory payloads. Use managed
execution when `kotlin-exec` should write stdin.

Spawn stdout variants:

- `SpawnSpec.StdoutSpec.Inherit`
- `SpawnSpec.StdoutSpec.Discard`
- `SpawnSpec.StdoutSpec.File`

Spawn stderr variants:

- `SpawnSpec.StderrSpec.Inherit`
- `SpawnSpec.StderrSpec.Discard`
- `SpawnSpec.StderrSpec.File`
- `SpawnSpec.StderrSpec.ToStdout`

`RunningProcess` exposes:

- `pid`
- `isAlive()`
- `exitCodeOrNull()`
- `killTree()`
- `awaitExitBlockingOutcome(timeout)`
- `awaitExitBlocking(timeout)`
- `awaitExitOutcome(timeout)`
- `awaitExit(timeout)`

Spawn wait timeouts do not kill the child.

`AwaitExitOutcome` variants:

- `AwaitExitOutcome.Exited`: the process exited and includes an `ExitCode`.
- `AwaitExitOutcome.TimedOut`: the wait timeout elapsed and the process may still be running.
- `AwaitExitOutcome.Interrupted`: a blocking wait was interrupted and the process may still be
  running.

## Encoding

`TextEncoding` supports:

- `Utf8`
- `Ascii`
- `Latin1`
- `Named`

`Named` is resolved by the platform implementation at encode/decode time.

## JVM-Specific API

JVM-only declarations are marked with `@PlatformSpecificExecApi`:

- `JvmExecSpec`: managed spec using `java.nio.file.Path`, JVM streams, and `Charset`.
- `JvmSpawnSpec`: spawn spec using `java.nio.file.Path`.
- `JvmRunningProcess`: exposes raw JVM `Process`.
- `VirtualThreadsPolicy`: `Never`, `Prefer`, or `Require`.
- JVM overloads on `Exec` for `JvmExecSpec`, `JvmSpawnSpec`, and virtual-thread policy.
- `ExecResult.Captured.text(charset, trimLineEndings)`: JVM charset decoding.
- `execInputFromStream(open)`: adapt `InputStream` to portable stdin.
- `execInputWriteTo(write)`: adapt `OutputStream` writer callback to portable stdin.

`execInternal(spec, ioDispatcher)` remains public for historical compatibility but is an opt-in
low-level JVM execution engine. Prefer `Exec.exec`.

Use `JvmRunningProcess.rawProcess` only when integration code needs a JVM API that is intentionally
not part of the portable `RunningProcess` contract.
