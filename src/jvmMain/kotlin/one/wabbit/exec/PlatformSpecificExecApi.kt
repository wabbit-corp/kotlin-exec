package one.wabbit.exec

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
