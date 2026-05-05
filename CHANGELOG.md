# Changelog

## 0.0.1

- Initial JVM publication of `kotlin-exec`.
- Provides managed process execution with bounded stdout/stderr capture.
- Provides spawned process handles for non-destructive waiting and explicit process-tree
  termination.
- Provides structured execution errors through `ExecError` and `ExecException`.
- Provides portable `ExecSpec`/`SpawnSpec` models plus JVM-specific opt-in APIs.
