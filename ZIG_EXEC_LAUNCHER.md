# Native Exec Launcher

## Summary

`kotlin-exec` needs one category of launch behavior that plain JVM `ProcessBuilder` does not model
well:

- true detached launch

True detach means more than "run in the background". It means:

- the child is not tied to the parent console/session in the normal way
- the parent can exit without implicitly owning the child
- launch is shell-free and explicit
- `kotlin-exec` still gets a PID / handle back that it can expose to callers

This document proposes a small native launcher implemented in Kotlin/Native.

The launcher is not a replacement for `kotlin-exec`'s existing JVM execution engine. It is a
focused native tool for process-launch semantics that the JVM does not expose directly and reliably
across platforms.

The launcher should first solve:

- true detached launch for `Exec.spawn(...)`

It may later solve additional launch-time platform features that are currently either impossible or
awkward through `ProcessBuilder`.

## Problem

`ProcessBuilder` is good enough for:

- ordinary managed `exec`
- ordinary unmanaged `spawn`
- cwd/env setup
- file/inherit/discard stdio

It is not good enough for:

- Unix session detachment (`setsid`)
- Windows detached creation flags
- explicit process-group/session creation as a first-class launch feature
- shell-free, platform-accurate detached launch with a stable PID result

The missing capability is not about convenience. It is about launch semantics.

Trying to fake true detach with shell wrappers such as:

- `nohup ... &`
- `setsid ...`
- `cmd /c start ...`

would be the wrong abstraction because it creates problems with:

- quoting
- PID reporting
- shell-specific behavior
- error reporting
- kill semantics

## Goals

1. Support true detached launch for `kotlin-exec` on:
   - Linux
   - macOS
   - Windows

2. Keep the existing `Exec.spawn(...)` API shape as the main entry point.

3. Avoid shell wrappers entirely.

4. Return machine-readable launch results:
   - success/failure
   - PID
   - structured error information

5. Keep the launcher one-shot and simple:
   - receive request
   - launch child
   - report result
   - exit

6. Make the implementation portable across target OSes with one Kotlin/Native codebase.

7. Reuse as much of the launch logic as possible in future non-JVM `kotlin-exec` backends.

8. Keep ordinary `exec` and ordinary `spawn` in the JVM implementation when the JVM is already
   sufficient.

## Non-goals

1. Replace ordinary `exec` capture/pumping logic with an external tool.
   - `kotlin-exec` already handles managed capture well on JVM.

2. Create a new top-level public API instead of extending `spawn`.

3. Implement full sandboxing, privilege dropping, namespaces, or container isolation in the first
   version.

4. Guarantee identical OS semantics everywhere.
   - true detach has platform-specific meaning

5. Make the launcher a persistent daemon.
   - it should remain a short-lived helper

## Why Kotlin/Native

Kotlin/Native is the preferred implementation language for this helper because it creates a path to
reuse the same launch logic outside the JVM.

### 1. The helper is native, but the design still belongs in the Kotlin ecosystem

The launcher is a native executable artifact, not a library called from `commonMain`.

But unlike a Zig-only helper, a Kotlin/Native implementation lets us keep:

- the request/response model in Kotlin
- the platform split in Kotlin
- the surrounding build/publish conventions close to the rest of the workspace

### 2. Reuse for future non-JVM `kotlin-exec`

This is the main advantage over Zig.

If we build the launch core in Kotlin/Native, we can split the design into:

1. native launch core
   - common request/response types
   - POSIX launch implementation
   - Windows launch implementation

2. native launcher executable
   - tiny CLI wrapper around the core

Then:

- JVM `kotlin-exec` uses the launcher executable when true detach is requested
- future native `kotlin-exec` actuals can reuse the same native launch core directly, without
  spawning an intermediate launcher process

That reuse path is real and valuable.

### 3. Same language, different role

This does **not** mean the launcher is magically part of the `commonMain` runtime.

It remains:

- a native tool for the JVM path
- and a reusable native launch substrate for future native `kotlin-exec`

So Kotlin/Native is useful here not because the launcher is conceptually "KMP", but because the
launch logic can later be shared with non-JVM backends.

### 4. Tradeoff

Kotlin/Native does not eliminate the need for per-platform CI and packaging.

We still need:

- macOS builds
- Linux builds
- Windows builds

That operational cost remains.

## Recommended project split

The best structure is not a single monolith.

It should be:

1. `kotlin-exec-native-launch-core`
   - Kotlin/Native KMP library
   - owns launch request validation and platform-specific detach logic

2. `kotlin-exec-native-launcher`
   - Kotlin/Native executable
   - thin wrapper over the core
   - used by JVM `kotlin-exec`

This gives us both:

- a helper executable for JVM
- reusable native launch code for future non-JVM `kotlin-exec`

## High-level architecture

### Components

1. `kotlin-exec` JVM runtime
   - decides whether ordinary `ProcessBuilder` is sufficient
   - decides whether detached launch is requested
   - invokes the native launcher when required

2. Kotlin/Native launcher executable
   - parses a structured request
   - performs the OS-specific launch
   - prints a structured response
   - exits

3. native launch core
   - contains the actual platform launch logic
   - can later be reused by native `kotlin-exec`

4. `RunningProcess` / `JvmRunningProcess`
   - wraps the launched process using `ProcessHandle`
   - for true detached processes, raw `Process` may be unavailable

### Rule of use

The launcher should be used only for launch semantics that the JVM cannot express cleanly.

That means:

- use ordinary JVM spawn for ordinary spawn
- use the launcher for true detach and related launch-only features

This keeps the helper focused and prevents it from becoming a second execution engine.

## How `kotlin-exec` should use it

The launcher should be integrated behind `Exec.spawn(...)`, not through a separate public entry point.

Expected direction in `kotlin-exec`:

- extend `JvmSpawnSpec` with an explicit detach mode
- when detach mode is normal:
  - current JVM spawn path
- when detach mode is true detach:
  - invoke native launcher

This keeps the public API compact and avoids breeding another spawn variant.

## Proposed `kotlin-exec` API direction

This document is about the launcher, but the launcher exists to support a specific `kotlin-exec`
feature.

The likely JVM-side direction is:

```kotlin
@PlatformSpecificExecApi
enum class JvmDetachMode {
    None,
    TrueDetach,
}
```

```kotlin
@PlatformSpecificExecApi
data class JvmSpawnSpec(
    ...
    val detach: JvmDetachMode = JvmDetachMode.None,
)
```

Important implication:

- true detach should remain an explicit opt-in mode
- ordinary `spawn` semantics should remain unchanged by default

## Restrictions for true detach

Detached launch should reject configurations that contradict detachment.

### Stdio restrictions

Detached launch should not allow:

- `stdin = Inherit`
- `stdout = Inherit`
- `stderr = Inherit`
- piped capture
- `stderr = ToStdout` if stdout is inherited by the parent

The detached child should instead use only:

- null/discard
- explicit file redirection
- platform-specific detached defaults

This is not a nuisance restriction. It is required to keep the detach contract honest.

## What the launcher must do

At minimum, version 1 must support:

1. launch detached child
2. set cwd
3. set environment
4. configure detached-safe stdio
5. report success/failure
6. report PID

It should not own the child after reporting launch success.

## Transport protocol

The helper should use a structured machine protocol, not ad hoc argv escaping.

### Invocation

The launcher should be invoked as a one-shot command.

Suggested shape:

```text
kotlin-exec-native-launcher launch
```

The request should be passed on `stdin`.

The response should be written to `stdout`.

`stderr` should be reserved for human-readable diagnostics only.

### Encoding

Use JSON for version 1.

Reasons:

- easy to inspect manually
- easy to debug in JVM logs
- easy to extend compatibly
- sufficient because requests are small

Binary framing is unnecessary for the first version.

### Request schema

The JVM side should fully materialize the launch request before calling the helper.

That keeps the helper simple and avoids duplicating policy logic inside the helper.

Suggested request shape:

```json
{
  "version": 1,
  "op": "launch",
  "mode": "true_detach",
  "argv": ["/usr/bin/python3", "-m", "http.server"],
  "cwd": "/tmp",
  "env": {
    "PATH": "/usr/bin:/bin",
    "PYTHONUNBUFFERED": "1"
  },
  "stdin": {
    "kind": "null"
  },
  "stdout": {
    "kind": "file",
    "path": "/tmp/server.out",
    "append": true
  },
  "stderr": {
    "kind": "file",
    "path": "/tmp/server.err",
    "append": true
  },
  "options": {
    "new_process_group": true,
    "new_session": true,
    "create_no_window": true
  }
}
```

Notes:

- `env` should be the final environment map, not an inheritance policy. The JVM side can resolve
  `EnvPolicy` into a concrete map before the helper is called.
- `cwd` should be nullable.
- `stdin/stdout/stderr` should use a small explicit tagged shape.
- `options` should be explicit capability flags, not vague booleans.

### Response schema

Suggested response shape:

```json
{
  "ok": true,
  "pid": 12345,
  "process_group_id": 12345,
  "session_id": 12345,
  "details": {
    "detached": true
  }
}
```

Failure:

```json
{
  "ok": false,
  "stage": "exec",
  "code": "ENOENT",
  "message": "No such file or directory",
  "details": {
    "argv0": "/missing/binary"
  }
}
```

### Exit codes

The process exit code should be meaningful but secondary to the JSON response.

Suggested contract:

- `0`: response written, operation succeeded
- `10`: malformed request
- `20`: launch failed
- `30`: internal launcher failure

`kotlin-exec` should primarily parse the JSON response, not rely on exit code textually.

## Platform behavior

## POSIX: Linux and macOS

### Required behavior

On POSIX targets, true detach should do at least:

1. fork
2. create a new session with `setsid`
3. redirect stdio away from the parent
4. `chdir` if requested
5. apply final environment
6. `execve`

This is the minimum credible detached launch.

### Why a native helper is correct on POSIX

Doing `fork`/`setsid` directly inside the JVM process is the wrong place to solve this.

The JVM is a multi-threaded managed runtime. Fork semantics there are subtle and operationally ugly.

Doing the fork inside a small native helper avoids contaminating the JVM with process-control edge
cases it was never designed to expose directly.

### Success/failure reporting

The POSIX launcher should use an internal error-report pipe between the launcher parent and the
child-to-be-exec'd.

Sequence:

1. launcher parent creates an error-report pipe
2. launcher forks
3. child performs `setsid`, stdio setup, cwd/env setup, then `execve`
4. if `execve` fails, child writes structured failure data to the error pipe and exits
5. if `execve` succeeds, the pipe closes without an error payload
6. launcher parent reports success with the detached child's PID

This avoids a bad class of errors where the launcher says "started" but `exec` actually failed.

### Double-fork

Double-fork is not required for version 1.

Reason:

- `setsid` plus detached-safe stdio is enough for the initial `kotlin-exec` use case
- the main goal is session/process-group separation from the JVM parent

Double-fork may still be worth supporting later if we need stricter daemonization semantics.

## Windows

### Required behavior

The Windows launcher should use `CreateProcessW` directly.

It should support:

- `DETACHED_PROCESS`
- `CREATE_NEW_PROCESS_GROUP`
- `CREATE_NO_WINDOW` when appropriate

It must also:

- build the environment block correctly
- set working directory
- configure stdio handle inheritance correctly
- avoid inheriting parent handles accidentally

### Why this matters

Windows process creation semantics are flag-heavy and the exact combination matters.

`ProcessBuilder` does not expose those flags as first-class launch controls.

The launcher is valuable here even if the POSIX side did not exist.

## `RunningProcess` representation after true detach

True detach means we may not have a stable `java.lang.Process` object representing the launched child.

For that reason, the JVM handle type in `kotlin-exec` should evolve toward:

- always having a `ProcessHandle`
- optionally having a raw `Process`

That implies the JVM-specific handle should eventually look more like:

```kotlin
@PlatformSpecificExecApi
interface JvmRunningProcess : RunningProcess {
    val processHandle: ProcessHandle
    val rawProcessOrNull: Process?
}
```

Normal JVM `spawn`:

- `processHandle` present
- `rawProcessOrNull` present

True detached launch:

- `processHandle` present
- `rawProcessOrNull == null`

This is a better fit than forcing a fake `Process` for detached children.

## Packaging and distribution

The launcher should be distributed as native binaries, not built by end users during normal JVM
library consumption.

### Build outputs

Suggested targets:

- `linux-x86_64`
- `linux-aarch64`
- `macos-x86_64`
- `macos-aarch64`
- `windows-x86_64`

### Packaging options

Two viable approaches:

#### Option A: companion artifact with bundled binaries

- publish launcher binaries in a dedicated artifact
- `kotlin-exec` JVM depends on that artifact
- binaries are extracted at runtime

#### Option B: `kotlin-exec` artifact embeds binaries directly

- simpler consumption
- larger artifact
- tighter coupling

Option A is cleaner operationally.

### Runtime extraction

`kotlin-exec` should:

1. detect OS/arch
2. pick the matching launcher binary
3. extract it to a versioned cache directory
4. mark it executable on Unix
5. invoke it

The cache key should include:

- launcher version
- platform
- binary hash

This avoids stale or partially upgraded helpers.

## Why this design is useful beyond detach

The detach use case is the first justification, not the last.

The native launch core can also become the correct place for launch-time features that the JVM
either does not support or supports badly.

### 1. New process group without full detach

There are legitimate cases where we want:

- a new process group/session
- but not full daemon-style detachment

That can matter for:

- stronger tree-kill semantics
- better signal isolation
- separating launched tool trees from the JVM parent

### 2. Windows creation flags beyond detach

Examples:

- create without window
- create new console
- create suspended
- explicit creation flags for tooling processes

`ProcessBuilder` does not make these easy to express.

### 3. Better detached-process ownership

Once a child is truly detached, `ProcessBuilder` is no longer the right conceptual owner.

The native launch core can standardize:

- how detached PIDs are reported
- how detached trees are identified
- what metadata is returned to `kotlin-exec`

### 4. Future non-JVM `kotlin-exec`

This is the major Kotlin/Native-specific upside.

If we later add native `kotlin-exec` backends, they can reuse:

- launch request validation
- POSIX detach logic
- Windows launch logic

without going through a helper executable at all.

That reuse is the main reason to prefer Kotlin/Native here.

### 5. Better ownership-transfer launches

There are workflows where the caller wants to:

- launch a child
- verify it started correctly
- stop owning it structurally

Examples:

- long-lived tool daemons
- local development servers
- browser/process launchers

The native launch core can become the substrate for that kind of explicit ownership transfer.

## What the launcher should *not* absorb

It should not absorb the whole `kotlin-exec` runtime.

Specifically, version 1 should not move:

- managed stdout/stderr capture
- stdin pumping
- timeout handling for ordinary `exec`
- sink/capture policy
- output truncation logic

Those are already modeled well in the JVM implementation.

The launcher should remain focused on process creation semantics.

## Suggested implementation phases

### Phase 1

- Kotlin/Native launch core with `launch true_detach`
- Linux/macOS/Windows support
- cwd/env/stdout/stderr file-or-null support
- PID reporting
- structured error reporting

### Phase 2

- native launcher executable wrapping the launch core
- integrate with `JvmSpawnSpec.detach`
- return `ProcessHandle`-backed running processes
- add tests in `kotlin-exec`

### Phase 3

- consider new-process-group / session features beyond detach
- consider additional Windows creation flags
- consider better detached-process tree metadata
- start reusing the launch core in non-JVM `kotlin-exec`

## Testing strategy

### Unit-level

Launcher request validation:

- malformed JSON
- missing required fields
- invalid stdio combinations
- invalid cwd
- missing executable

### Integration-level

Per-platform:

1. spawn detached process
2. parent exits launcher path cleanly
3. child remains alive
4. child PID is valid
5. redirected file output is produced
6. `kotlin-exec` can later observe and kill the child by PID/handle

### Negative tests

- missing executable
- invalid cwd
- unwritable output redirection
- invalid environment entry

### Important operational test

Launch a detached child from JVM, terminate the JVM, and confirm the child remains alive.

That is the actual user-facing contract we care about.

## Open questions

1. Should POSIX version 1 use single-fork `setsid` only, or go straight to double-fork daemonization?

2. What exact `JvmRunningProcess` shape should replace the current mandatory raw `Process` exposure?

3. Should the native launch core eventually also support "new process group, but not detached" as a
   first-class mode?

4. Should launcher binaries live in a separate published artifact or be embedded into `kotlin-exec`?

5. Should the request schema encode launch intent semantically (`mode = true_detach`) or directly in
   low-level flags (`setsid`, `detached_process`, `create_new_process_group`)?

The first version should prefer semantic intent and let the native launch core map it to platform
flags.

## Recommendation

Build the launcher in Kotlin/Native.

Use it first for:

- true detached `spawn`

Keep it narrowly scoped to launch-time semantics that the JVM cannot express directly.

This gives `kotlin-exec` a real path to true detach while also preserving a path to reuse the same
launch logic in future non-JVM `kotlin-exec` backends.
