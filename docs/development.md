# Development

`kotlin-exec` is a Kotlin Multiplatform-shaped project with a JVM target. In the monorepo workspace,
build and test it through the `dev` tooling:

```bash
./dev build kotlin-exec
```

From the project directory, or from a standalone checkout, run Gradle directly:

```bash
./gradlew build
```

## Documentation Checks

Run repository documentation checks with:

```bash
./dev verify docs kotlin-exec
```

Build generated API docs with:

```bash
cd kotlin-exec
./gradlew dokkaGeneratePublicationHtml
```

## Publication Dry Run

Before release publication, run:

```bash
./dev publish --dry-run kotlin-exec
```

## Documentation Standards

Public KDoc should describe:

- process ownership: managed execution versus spawn
- timeout and cancellation behavior
- output capture limits and overflow policy
- stdin and output sink lifecycle
- environment inheritance and hermetic behavior
- JVM-specific opt-in requirements

Hand-written docs should include examples for the ordinary path and call out semantic traps such as
shell parsing, destructive managed timeouts, non-destructive spawn waits, and bounded capture.
