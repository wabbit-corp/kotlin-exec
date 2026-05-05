# Troubleshooting

## `argv must not be empty`

`ExecSpec.argv` and `SpawnSpec.argv` must include the executable as the first element.

```kotlin
ExecSpec(argv = listOf("git", "status"))
```

`kotlin-exec` does not parse shell command strings. Pass arguments as separate list elements, or
invoke a shell explicitly when shell parsing is required.

## Command Works In A Shell But Not Through `kotlin-exec`

Shells expand globs, variables, aliases, functions, and redirections. `kotlin-exec` passes `argv`
directly to the process launcher.

If shell behavior is intentional, use:

```kotlin
ExecSpec.tooling(listOf("sh", "-c", "printf '%s\\n' *.kt"))
```

On Windows, use the appropriate shell such as `cmd /c` or PowerShell.

## Missing `PATH` Or Environment Variables

Check the selected `EnvPolicy`.

`EnvPolicy.ClearAndSet` uses exactly the supplied map. If the command relies on `PATH`, provide it
explicitly or use `EnvPolicy.Hermetic`, which installs a minimal launch environment before applying
your variables.

## Output Is Truncated

Captured output is intentionally bounded. Inspect:

- `ExecResult.Captured.truncated`
- `ExecResult.Captured.bytesRead`
- `ExecResult.stdoutStats`
- `ExecResult.stderrStats`

Increase `maxBytes` or use `SinkSpec.File` for large output.

## Process Is Killed On Timeout

This is expected for managed execution. `Exec.exec` and `Exec.execBlocking` own the child lifecycle,
so timeout is destructive.

Use `Exec.spawn` or `Exec.spawnBlocking` when timeout should not kill the process.

## Spawn Does Not Capture Output

Spawn currently supports inherit, discard, and file redirects. It does not provide in-memory capture
because the process continues after the call returns and output ownership remains open-ended.

Use managed `exec` for captured foreground runs, or redirect spawn output to files.

## Blocking Execution Fails With Virtual Thread Requirement

`VirtualThreadsPolicy.Require` needs a JDK with virtual-thread support. Use
`VirtualThreadsPolicy.Prefer` to use virtual threads when available and fall back otherwise.

## Stream Callback Sees Mutating Buffers

`SinkSpec.Stream` passes an internal reusable buffer by default. Set `copyChunks = true` if the
callback stores the byte array after returning.

## Output Limit Kills The Process

This happens when a sink uses `OverflowPolicy.KillProcess`. Use `DrainAndTruncate` when the process
should continue and only retention should be bounded.
