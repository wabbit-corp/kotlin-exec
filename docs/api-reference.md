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
- `SinkSpec.Stream`: invoke a callback for chunks as they are read.
- `SinkSpec.WriteTo`: write to a caller-supplied `kotlinx.io.Sink`.
- `SinkSpec.File`: write to a filesystem path.
- `SinkSpec.Tee`: duplicate output to a primary sink and branches.

`OverflowPolicy.DrainAndTruncate` keeps the process running while discarding output past the limit.
`OverflowPolicy.KillProcess` reports `ExecError.OutputLimitExceeded` and terminates the process.

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
