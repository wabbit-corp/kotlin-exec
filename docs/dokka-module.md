# Module kotlin-exec

Kotlin JVM process execution primitives with bounded capture, structured failures, explicit
environment policy, and clear process ownership.

Use `Exec.exec` or `Exec.execBlocking` for managed foreground commands. Managed execution owns the
child process lifecycle and terminates the process tree on timeout, cancellation, output-limit
failure, or cleanup failure.

Use `Exec.spawn` or `Exec.spawnBlocking` when the caller needs a running process handle and wants
timeouts on later waits to be non-destructive.

JVM-specific APIs are marked with `@PlatformSpecificExecApi`. Prefer the portable `ExecSpec` and
`SpawnSpec` types unless a call site needs JVM-only features such as `java.nio.file.Path`,
`InputStream`, `OutputStream`, `Charset`, raw `Process`, or virtual-thread policy control.
