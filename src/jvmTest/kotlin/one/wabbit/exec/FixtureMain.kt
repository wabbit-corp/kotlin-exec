// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.exec

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * A tiny subprocess target used by tests.
 *
 * This lives in test sources so it is available on the test runtime classpath and can be launched
 * by `java -cp <test classpath> one.wabbit.exec.FixtureMain ...`.
 *
 * Supported flags (order-independent): --pid-file <path> Write current pid to a file.
 * --child-pid-file <path> Path for spawned child's pid file. --spawn-child-sleep-ms <ms> Spawn a
 * child FixtureMain that writes its pid and sleeps. --print-env <NAME> Print env var value or
 * "<null>" to stdout (with newline). --out <text> Write text to stdout (no newline). --err <text>
 * Write text to stderr (no newline). --cat Read stdin fully and copy bytes to stdout. --spam-out
 * <n> Write n bytes to stdout (patterned). --spam-err <n> Write n bytes to stderr (patterned).
 * --sleep-ms <ms> Sleep before exiting. --exit <code> Exit code (default 0).
 */
object FixtureMain {
    @JvmStatic
    fun main(args: Array<String>) {
        var pidFile: Path? = null
        var childPidFile: Path? = null
        var spawnChildSleepMs: Long? = null

        val envPrints = mutableListOf<String>()
        var outText: String? = null
        var errText: String? = null
        var cat = false
        var spamOut: Int? = null
        var spamErr: Int? = null
        var sleepMs: Long = 0
        var exit: Int = 0

        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "--pid-file" -> {
                    pidFile = Path.of(args[i + 1])
                    i += 2
                }
                "--child-pid-file" -> {
                    childPidFile = Path.of(args[i + 1])
                    i += 2
                }
                "--spawn-child-sleep-ms" -> {
                    spawnChildSleepMs = args[i + 1].toLong()
                    i += 2
                }
                "--print-env" -> {
                    envPrints += args[i + 1]
                    i += 2
                }
                "--out" -> {
                    outText = args[i + 1]
                    i += 2
                }
                "--err" -> {
                    errText = args[i + 1]
                    i += 2
                }
                "--cat" -> {
                    cat = true
                    i += 1
                }
                "--spam-out" -> {
                    spamOut = args[i + 1].toInt()
                    i += 2
                }
                "--spam-err" -> {
                    spamErr = args[i + 1].toInt()
                    i += 2
                }
                "--sleep-ms" -> {
                    sleepMs = args[i + 1].toLong()
                    i += 2
                }
                "--exit" -> {
                    exit = args[i + 1].toInt()
                    i += 2
                }
                else -> {
                    System.err.write(("unknown arg: $a\n").toByteArray(StandardCharsets.UTF_8))
                    System.err.flush()
                    exitProcess(2)
                }
            }
        }

        // pid file first: tests rely on it.
        pidFile?.let {
            Files.writeString(it, ProcessHandle.current().pid().toString(), StandardCharsets.UTF_8)
        }

        // Optional child spawn.
        if (spawnChildSleepMs != null) {
            val childFile =
                requireNotNull(childPidFile) {
                    "--child-pid-file required with --spawn-child-sleep-ms"
                }
            spawnChild(childFile, spawnChildSleepMs!!)
            // Wait briefly for child pid file to appear (best-effort).
            val deadline = System.nanoTime() + 2_000_000_000L
            while (!Files.exists(childFile) && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {}
            }
        }

        // Env prints (newline terminated to keep parsing easy).
        for (k in envPrints) {
            val v = System.getenv(k)
            val out = (v ?: "<null>") + "\n"
            System.out.write(out.toByteArray(StandardCharsets.UTF_8))
        }
        System.out.flush()

        // Simple stdout/stderr writes (no newline).
        outText?.let {
            System.out.write(it.toByteArray(StandardCharsets.UTF_8))
            System.out.flush()
        }
        errText?.let {
            System.err.write(it.toByteArray(StandardCharsets.UTF_8))
            System.err.flush()
        }

        // Spam output.
        spamOut?.let { n -> spam(System.out, n, byte = 'A'.code.toByte()) }
        spamErr?.let { n -> spam(System.err, n, byte = 'E'.code.toByte()) }

        // Cat stdin.
        if (cat) {
            val bytes = System.`in`.readBytes()
            System.out.write(bytes)
            System.out.flush()
        }

        if (sleepMs > 0) {
            try {
                Thread.sleep(sleepMs)
            } catch (_: InterruptedException) {}
        }

        exitProcess(exit)
    }

    private fun spam(ps: java.io.PrintStream, total: Int, byte: Byte) {
        if (total <= 0) return
        val buf = ByteArray(8192) { byte }
        var remaining = total
        while (remaining > 0) {
            val n = minOf(remaining, buf.size)
            ps.write(buf, 0, n)
            remaining -= n
        }
        ps.flush()
    }

    private fun spawnChild(pidFile: Path, sleepMs: Long) {
        val javaHome = Path.of(System.getProperty("java.home"))
        val exe =
            if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        val javaExe = javaHome.resolve("bin").resolve(exe).toString()

        val cp = System.getProperty("java.class.path")
        val cpLine =
            if (cp.any { it.isWhitespace() } || cp.contains('"')) "\"${cp.replace("\"", "\\\"")}\""
            else cp
        val argFile =
            Files.createTempFile("fixture-child-cp-", ".args").also { it.toFile().deleteOnExit() }
        Files.writeString(
            argFile,
            "-cp\n$cpLine\none.wabbit.exec.FixtureMain\n",
            StandardCharsets.UTF_8,
        )

        val argv =
            listOf(
                javaExe,
                "@${argFile.toString()}",
                "--pid-file",
                pidFile.toString(),
                "--sleep-ms",
                sleepMs.toString(),
            )

        ProcessBuilder(argv)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .start()
    }
}
