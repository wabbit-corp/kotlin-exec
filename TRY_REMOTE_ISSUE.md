# `kotlin-launch` `tryRemote` and the current `kotlin-exec` gap

## Summary

`kotlin-launch` still has one manual `ProcessBuilder` path after the `kotlin-exec` migration work:

- [/Users/wabbit/ws/datatron/kotlin-launch/src/main/kotlin/one/wabbit/launch/Helpers.kt](/Users/wabbit/ws/datatron/kotlin-launch/src/main/kotlin/one/wabbit/launch/Helpers.kt)
- function: `Proc.tryRemote(...)`

This path is not using `kotlin-exec` because its required behavior does not match either of the two execution modes that `kotlin-exec` currently exposes:

- managed `exec`
- unmanaged `spawn`

The mismatch is not about syntax or convenience. It is about behavior and lifecycle ownership.

## The concrete use case

`tryRemote(...)` is used by browser launch code that tries a browser's "remote control" interface before falling back to a normal launch.

Reference:

- [/Users/wabbit/ws/datatron/kotlin-launch/src/main/kotlin/one/wabbit/launch/Browser.kt:175](/Users/wabbit/ws/datatron/kotlin-launch/src/main/kotlin/one/wabbit/launch/Browser.kt:175)

The remote invocation is a short-lived probe in the success case, but in the timeout case the launched process may still be a valid browser process that should be left alone.

Examples of the kind of commands this path is responsible for:

- browser remote-control calls
- "open existing instance" probes
- commands where "did not exit quickly" is not the same as "must be killed"

## Required behavior

The behavior `tryRemote(...)` currently relies on is:

1. Start a child process.
2. Discard `stdout`.
3. Capture `stderr`.
4. Wait only up to a short timeout.
5. If the process exits within the timeout:
   - inspect the exit code
   - inspect captured `stderr`
6. If the process does **not** exit within the timeout:
   - report that the remote invocation timed out
   - **do not kill the process**
   - return a handle to the still-running process
7. If the parent coroutine is cancelled:
   - kill the child process

This is an intentionally asymmetric contract:

- timeout should be non-destructive
- cancellation should be destructive

That asymmetry is important. The timeout is part of normal control flow for this browser-probe path. Cancellation is not.

## Why `exec` does not currently fit

Current `exec` in `kotlin-exec` owns the whole child lifecycle:

- it spawns the process
- it starts and owns stdin/stdout/stderr pumping
- it owns sink finalization
- it assembles final captures
- on timeout, it kills the process
- on cancellation, it kills the process

Relevant files:

- [/Users/wabbit/ws/datatron/kotlin-exec/src/jvmMain/kotlin/one/wabbit/exec/CoroutineCommandRunner.kt](/Users/wabbit/ws/datatron/kotlin-exec/src/jvmMain/kotlin/one/wabbit/exec/CoroutineCommandRunner.kt)
- [/Users/wabbit/ws/datatron/kotlin-exec/src/jvmMain/kotlin/one/wabbit/exec/BlockingCommandRunner.kt](/Users/wabbit/ws/datatron/kotlin-exec/src/jvmMain/kotlin/one/wabbit/exec/BlockingCommandRunner.kt)

This means `exec` is built around the assumption that a timeout is terminal for the managed operation.

For `tryRemote(...)`, that assumption is wrong.

If `exec` were used as-is, a timeout would kill the process. That would change semantics in a real and undesirable way:

- the probe would become destructive
- a child process that should have been allowed to keep running would be terminated
- browser remote behavior would change materially

## Why `spawn` does not currently fit

Current `spawn` has the right timeout behavior:

- it can wait with a timeout
- timeout does not kill the child

Relevant file:

- [/Users/wabbit/ws/datatron/kotlin-exec/src/jvmMain/kotlin/one/wabbit/exec/SpawnCommandRunner.kt](/Users/wabbit/ws/datatron/kotlin-exec/src/jvmMain/kotlin/one/wabbit/exec/SpawnCommandRunner.kt)

However, `spawn` does **not** currently provide the I/O behavior this path needs.

Current `JvmSpawnSpec` supports only:

- `stdin`: `None | Inherit`
- `stdout`: `Inherit | Discard | File`
- `stderr`: `Inherit | Discard | File | ToStdout`

Reference:

- [/Users/wabbit/ws/datatron/kotlin-exec/src/jvmMain/kotlin/one/wabbit/exec/JvmModel.kt:126](/Users/wabbit/ws/datatron/kotlin-exec/src/jvmMain/kotlin/one/wabbit/exec/JvmModel.kt:126)

What is missing:

- no piped `stdout`/`stderr` handling in `spawn`
- no capture sinks in `spawn`
- no stream consumers in `spawn`
- no result object with captured output attached to a spawned process

That means `spawn` can preserve the child, but it cannot give `tryRemote(...)` the `stderr` inspection it needs.

## The exact semantic gap

`tryRemote(...)` needs both of these properties at the same time:

1. managed stderr capture
2. non-destructive timeout

Current `kotlin-exec` splits those properties across two different abstractions:

- `exec` has managed capture
- `spawn` has non-destructive timeout

But there is no single path that provides both together.

That is the core issue.

## Why this matters in practice

Without this capability, callers that need this hybrid behavior must keep a manual `ProcessBuilder` implementation.

That has several costs:

### 1. One-off process-management code remains in downstream libraries

This is exactly what happened in `kotlin-launch`.

The library now uses `kotlin-exec` for:

- normal foreground command execution
- background detached launches
- simple captured probes

But one manual process runner remains because the current abstraction gap forces it.

### 2. Process lifecycle semantics stay duplicated outside `kotlin-exec`

The manual path must reimplement:

- spawn failure handling
- timeout behavior
- cancellation cleanup
- stderr reading
- process liveness handling

Those are precisely the behaviors `kotlin-exec` exists to centralize.

### 3. There is pressure to make downstream code choose the wrong compromise

Without a proper library-level primitive, downstream callers are pushed toward one of two bad options:

- use `exec` and kill the process on timeout, changing behavior
- use `spawn` and lose stderr capture, reducing observability and correctness

Neither preserves the intended contract.

### 4. This is not unique to browser launching

The same pattern can arise anywhere a caller wants:

- bounded waiting
- captured stderr/stdout
- the option to leave the child alive after the initial wait window

Examples include:

- remote-control probes
- daemon/client handshake launchers
- readiness probes for tools that may outlive the first wait
- "start and check quickly" workflows

So this is a real abstraction hole, not a quirky `kotlin-launch` special case.

## Constraints the eventual library support must preserve

Any eventual fix needs to preserve these behavioral constraints:

### Timeout must not imply kill

The caller must be able to observe "did not exit in time" without that observation itself terminating the child.

### Cancellation must still be able to imply kill

The caller must be able to rely on structured concurrency cleanup when the parent scope is cancelled.

### Captured output must remain trustworthy

If stderr capture is requested, the eventual API must make it clear whether the returned stderr is:

- complete
- partial
- unavailable because the process is still running

The ambiguity matters. `tryRemote(...)` currently uses stderr as part of error classification.

### Process ownership must remain explicit

If the process is still running after timeout, ownership has not ended.

That means the library cannot pretend the operation has fully completed. There is still a live child and live I/O state to account for.

### The current simple `exec` contract should remain simple

The ordinary case in `kotlin-exec` is still:

- run process
- wait
- capture output
- return result or error

That path should not become harder to reason about just to support this hybrid case.

## Current downstream consequence

As of now:

- `kotlin-network-context` no longer needs manual `ProcessBuilder`
- `kotlin-ghostscript` no longer needs manual `ProcessBuilder`
- `kotlin-launch` still has one manual path because of this gap

Remaining manual site:

- [/Users/wabbit/ws/datatron/kotlin-launch/src/main/kotlin/one/wabbit/launch/Helpers.kt:213](/Users/wabbit/ws/datatron/kotlin-launch/src/main/kotlin/one/wabbit/launch/Helpers.kt:213)

This file should be treated as the motivating real-world example for the gap.

## Scope of this document

This document intentionally records:

- the use case
- the current mismatch
- the required semantics
- the downstream costs

It intentionally does **not** record a proposed API design or implementation plan.

That should be discussed separately once the behavioral requirements are accepted as real.
