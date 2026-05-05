package one.wabbit.exec

/**
 * Marks JVM-specific APIs that are not part of the portable `kotlin-exec` surface.
 *
 * Opt in when a call site intentionally depends on JVM-only types or controls, such as
 * `java.nio.file.Path`, `InputStream`, `OutputStream`, charset-specific stdin, raw [Process]
 * access, or explicit [VirtualThreadsPolicy] selection.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message =
        "This API is JVM-specific and not Kotlin Multiplatform-friendly. Prefer ExecSpec, SpawnSpec, and the common Exec overloads where possible. Use this only when you need JVM-only features such as charset-specific text input, stream-backed stdin, writer-backed stdin, java.nio.file.Path interoperability, or explicit virtual-thread control.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
annotation class PlatformSpecificExecApi
