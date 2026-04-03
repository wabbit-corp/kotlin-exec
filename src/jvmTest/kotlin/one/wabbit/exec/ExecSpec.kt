@file:OptIn(PlatformSpecificExecApi::class)

package one.wabbit.exec

import kotlinx.coroutines.async
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * End-to-end tests for kotlin-exec.
 *
 * Uses a small JVM fixture program (FixtureMain) compiled into test-classes and launched as a
 * subprocess. This makes tests cross-platform and avoids relying on shell utilities.
 */
class ExecTest {

    // ---- Helpers -------------------------------------------------------------------------------

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    private fun javaExe(): String {
        val home = Path.of(System.getProperty("java.home"))
        val exe = if (isWindows()) "java.exe" else "java"
        return home.resolve("bin").resolve(exe).toString()
    }

    /**
     * Base argfile to avoid Windows command-line length limits.
     * Contains: -cp <java.class.path> one.wabbit.exec.FixtureMain
     */
    private val baseArgFile: Path by lazy {
        val cp = System.getProperty("java.class.path")
        val cpLine = quoteForJavaArgfileIfNeeded(cp)
        val p = Files.createTempFile("kotlin-exec-test-cp-", ".args")
        Files.writeString(
            p,
            "-cp\n$cpLine\none.wabbit.exec.FixtureMain\n",
            StandardCharsets.UTF_8
        )
        p.toFile().deleteOnExit()
        p
    }

    private fun quoteForJavaArgfileIfNeeded(s: String): String {
        // Conservative: quote if any whitespace. Also escape quotes if present.
        val needs = s.any { it.isWhitespace() } || s.contains('"')
        if (!needs) return s
        val escaped = s.replace("\"", "\\\"")
        return "\"$escaped\""
    }

    private fun fixtureArgv(vararg args: String): List<String> =
        listOf(javaExe(), "@${baseArgFile.toString()}") + args.toList()

    private fun spec(
        vararg fixtureArgs: String,
        stdin: JvmExecSpec.Input = JvmExecSpec.Input.None,
        stdout: JvmExecSpec.StdoutSpec = JvmExecSpec.StdoutSpec.Pipe(JvmExecSpec.SinkSpec.Capture(maxBytes = 1024, keep = ExecSpec.Keep.Head)),
        stderr: JvmExecSpec.StderrSpec = JvmExecSpec.StderrSpec.Pipe(JvmExecSpec.SinkSpec.Capture(maxBytes = 1024, keep = ExecSpec.Keep.Tail)),
        env: EnvPolicy = EnvPolicy.Inherit(),
        timeout: Duration? = 2.seconds,
        shutdown: ShutdownPolicy = ShutdownPolicy.KillTree,
        cleanupTimeout: Duration = 2.seconds,
        exitPolicy: ExitPolicy = ExitPolicy.Return,
    ): JvmExecSpec =
        JvmExecSpec(
            argv = fixtureArgv(*fixtureArgs),
            stdin = stdin,
            stdout = stdout,
            stderr = stderr,
            env = env,
            timeout = timeout,
            shutdown = shutdown,
            cleanupTimeout = cleanupTimeout,
            exitPolicy = exitPolicy,
        )

    private fun readBytes(p: Path): ByteArray = Files.readAllBytes(p)
    private fun readText(p: Path): String = Files.readString(p, StandardCharsets.UTF_8).trim()

    /**
     * Wait for a file to exist *and* have non-blank UTF-8 contents.
     * (Temp files created via createTempFile() start life as empty, which is not a PID.)
     */
    private fun waitForNonBlankFileText(p: Path, within: Duration = 1.seconds): String? {
        val deadline = System.nanoTime() + within.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            try {
                if (Files.exists(p)) {
                    val s = Files.readString(p, StandardCharsets.UTF_8).trim()
                    if (s.isNotEmpty()) return s
                }
            } catch (_: IOException) {
                // transient; keep polling
            } catch (_: SecurityException) {
                // unlikely in tests; keep polling
            }
            Thread.sleep(15)
        }
        return null
    }

    /**
     * Suspending variant: required for tests that run alongside coroutines on runBlocking's event loop.
     * Using Thread.sleep() there can starve launched coroutines and prevent the subprocess from ever spawning.
     */
    private suspend fun waitForNonBlankFileTextSuspend(p: Path, within: Duration = 1.seconds): String? {
        val deadline = System.nanoTime() + within.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            try {
                if (Files.exists(p)) {
                    val s = Files.readString(p, StandardCharsets.UTF_8).trim()
                    if (s.isNotEmpty()) return s
                }
            } catch (_: IOException) {
                // transient; keep polling
            } catch (_: SecurityException) {
                // unlikely in tests; keep polling
            }
            delay(15)
        }
        return null
    }

    private fun parsePidFileOrThrow(p: Path, within: Duration = 1.seconds): Long {
        val s = waitForNonBlankFileText(p, within)
            ?: error("pid file did not become non-empty in time: $p")
        return s.toLong()
    }

    private suspend fun parsePidFileOrThrowSuspend(p: Path, within: Duration = 2.seconds): Long {
        val s = waitForNonBlankFileTextSuspend(p, within)
            ?: error("pid file did not become non-empty in time: $p")
        return s.toLong()
    }

    private fun isAlive(pid: Long): Boolean =
        ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private fun assertDeadEventually(pid: Long, within: Duration = 1.seconds) {
        val deadline = System.nanoTime() + within.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            if (!isAlive(pid)) return
            Thread.sleep(15)
        }
        assertFalse(isAlive(pid), "Process $pid should be dead")
    }

    /** A temp file path that does *not* exist initially. Useful for pid files. */
    private fun tmpPath(prefix: String, suffix: String = ".txt"): Path {
        val p = Files.createTempFile(prefix, suffix)
        // Delete immediately; we only wanted a unique path.
        runCatching { Files.deleteIfExists(p) }
        // Register for cleanup if created later.
        p.toFile().deleteOnExit()
        return p
    }

    private fun tmpFile(prefix: String, suffix: String = ".txt"): Path =
        Files.createTempFile(prefix, suffix).also { it.toFile().deleteOnExit() }

    // ---- Blocking runner -----------------------------------------------------------------------

    @Test
    fun execBlocking_success_captures_stdout_and_stderr() {
        val r = Exec.execBlocking(
            spec(
                "--out", "hello",
                "--err", "oops",
            )
        )
        assertEquals(0, r.exitCode.value)
        assertTrue(r.ok)
        assertEquals("hello", r.stdout!!.text())
        assertEquals("oops", r.stderr!!.text())
        assertNotNull(r.stdoutStats)
        assertNotNull(r.stderrStats)
        assertFalse(r.stdoutStats!!.truncated)
        assertFalse(r.stderrStats!!.truncated)
    }

    @Test
    fun execBlocking_exitNonZero_return_policy() {
        val r = Exec.execBlocking(
            spec(
                "--out", "ok",
                "--err", "bad",
                "--exit", "7",
                exitPolicy = ExitPolicy.Return
            )
        )
        assertEquals(7, r.exitCode.value)
        assertFalse(r.ok)
        assertEquals("ok", r.stdout!!.text())
        assertEquals("bad", r.stderr!!.text())
    }

    @Test
    fun execBlocking_exitNonZero_throw_policy_includes_captures() {
        val ex =
            assertFailsWith<ExecException> {
                Exec.execBlocking(
                    spec(
                        "--out", "ok",
                        "--err", "bad",
                        "--exit", "7",
                        exitPolicy = ExitPolicy.ThrowOnNonZero
                    )
                )
            }
        val err = ex.error
        assertTrue(err is ExecError.ExitNonZero)
        assertEquals(7, (err as ExecError.ExitNonZero).exitCode)
        assertEquals("ok", err.captures!!.stdout!!.text())
        assertEquals("bad", err.captures!!.stderr!!.text())
    }

    @Test
    fun execBlocking_stderr_to_stdout_merges_streams() {
        val r =
            Exec.execBlocking(
                spec(
                    "--out", "hello",
                    "--err", "oops",
                    stdout = JvmExecSpec.StdoutSpec.Pipe(JvmExecSpec.SinkSpec.Capture(maxBytes = 1024, keep = ExecSpec.Keep.Head)),
                    stderr = JvmExecSpec.StderrSpec.ToStdout,
                )
            )
        assertTrue(r.ok)
        val out = r.stdout!!.text()
        assertContains(out, "hello")
        assertContains(out, "oops")
        assertNull(r.stderr) // merged
    }

    @Test
    fun execBlocking_stdin_text_cat_roundtrip_bytes_exact() {
        val input = "hello\nworld\n".toByteArray(StandardCharsets.UTF_8)
        val r =
            Exec.execBlocking(
                spec(
                    "--cat",
                    stdin = JvmExecSpec.Input.Text("hello\nworld\n", StandardCharsets.UTF_8),
                )
            )
        assertTrue(r.ok)
        assertTrue(r.stdout!!.bytes.contentEquals(input))
    }

    @Test
    fun execBlocking_stdin_from_path_cat_roundtrip() {
        val p = tmpFile("stdin-", ".bin")
        val input = ByteArray(2048) { (it % 251).toByte() }
        Files.write(p, input)

        val r =
            Exec.execBlocking(
                spec(
                    "--cat",
                    stdin = JvmExecSpec.Input.FromPath(p),
                    stdout = JvmExecSpec.StdoutSpec.Pipe(
                        JvmExecSpec.SinkSpec.Capture(
                            maxBytes = input.size + 16,
                            keep = ExecSpec.Keep.Head,
                            overflow = ExecSpec.OverflowPolicy.DrainAndTruncate
                        )
                    ),
                    stderr = JvmExecSpec.StderrSpec.Discard,
                )
            )
        assertTrue(r.ok)
        assertTrue(r.stdout!!.bytes.contentEquals(input))
    }

    @Test
    fun execBlocking_stdin_from_stream_cat_roundtrip() {
        val input = ByteArray(4096) { (it % 239).toByte() }
        val r =
            Exec.execBlocking(
                spec(
                    "--cat",
                    stdin = JvmExecSpec.Input.FromStream { ByteArrayInputStream(input) },
                    stdout = JvmExecSpec.StdoutSpec.Pipe(
                        JvmExecSpec.SinkSpec.Capture(
                            maxBytes = input.size + 16,
                            keep = ExecSpec.Keep.Head,
                            overflow = ExecSpec.OverflowPolicy.DrainAndTruncate
                        )
                    ),
                    stderr = JvmExecSpec.StderrSpec.Discard,
                )
            )
        assertTrue(r.ok)
        assertTrue(r.stdout!!.bytes.contentEquals(input))
    }

    @Test
    fun execBlocking_capture_truncates_without_killing() {
        val r =
            Exec.execBlocking(
                spec(
                    "--spam-out", "10000",
                    stdout = JvmExecSpec.StdoutSpec.Pipe(
                        JvmExecSpec.SinkSpec.Capture(
                            maxBytes = 128, keep = ExecSpec.Keep.Head,
                            overflow = ExecSpec.OverflowPolicy.DrainAndTruncate)
                    ),
                    stderr = JvmExecSpec.StderrSpec.Discard,
                )
            )
        assertTrue(r.ok)
        assertEquals(128, r.stdout!!.bytes.size)
        assertTrue(r.stdout!!.truncated)
        assertEquals(10_000L, r.stdoutStats!!.bytesRead)
    }

    @Test
    fun execBlocking_capture_kill_on_overflow_throws_OutputLimitExceeded() {
        val ex =
            assertFailsWith<ExecException> {
                Exec.execBlocking(
                    spec(
                        "--spam-out", "10000",
                        stdout = JvmExecSpec.StdoutSpec.Pipe(
                            JvmExecSpec.SinkSpec.Capture(
                                maxBytes = 128, keep = ExecSpec.Keep.Head,
                                overflow = ExecSpec.OverflowPolicy.KillProcess)
                        ),
                        stderr = JvmExecSpec.StderrSpec.Discard,
                        shutdown = ShutdownPolicy.KillTree,
                    )
                )
            }
        val err = ex.error
        assertTrue(err is ExecError.OutputLimitExceeded)
        assertEquals(StreamId.STDOUT, (err as ExecError.OutputLimitExceeded).stream)
        assertNotNull(err.captures)
        assertTrue(err.captures!!.stdoutStats!!.truncated)
        assertTrue(err.captures!!.stdoutStats!!.bytesRead > 128)
    }

    @Test
    fun execBlocking_file_sink_unbounded_specializes_to_redirect() {
        val outFile = tmpFile("stdout-", ".txt")
        val r =
            Exec.execBlocking(
                spec(
                    "--out", "hello",
                    stdout = JvmExecSpec.StdoutSpec.Pipe(JvmExecSpec.SinkSpec.File(
                        outFile,
                        // Must be eager to be eligible for ProcessBuilder redirect specialization.
                        // Append(false) means eager=false (lazy open), which requires pumping and thus stats.
                        FileWritePolicy.Append(eager = true),
                        maxBytes = null
                    )),
                    stderr = JvmExecSpec.StderrSpec.Discard,
                )
            )
        assertTrue(r.ok)
        assertNull(r.stdout)
        assertNull(r.stdoutStats) // no pumping => no stats
        assertEquals("hello", readText(outFile))
    }

    @Test
    fun execBlocking_file_sink_bounded_truncates_and_reports_stats() {
        val outFile = tmpFile("stdout-bounded-", ".bin")
        val r =
            Exec.execBlocking(
                spec(
                    "--spam-out", "10000",
                    stdout = JvmExecSpec.StdoutSpec.Pipe(
                        JvmExecSpec.SinkSpec.File(
                            path = outFile,
                            write = FileWritePolicy.Append(false),
                            maxBytes = 100,
                            overflow = ExecSpec.OverflowPolicy.DrainAndTruncate,
                        )
                    ),
                    stderr = JvmExecSpec.StderrSpec.Discard,
                )
            )
        assertTrue(r.ok)
        assertNull(r.stdout)
        assertNotNull(r.stdoutStats)
        assertTrue(r.stdoutStats!!.truncated)
        assertEquals(10_000L, r.stdoutStats!!.bytesRead)
        assertEquals(100L, Files.size(outFile))
    }

    @Test
    fun execBlocking_tee_primary_capture_returned_and_file_branch_written() {
        val branchFile = tmpFile("tee-", ".txt")
        val r =
            Exec.execBlocking(
                spec(
                    "--out", "tee",
                    stdout = JvmExecSpec.StdoutSpec.Pipe(
                        JvmExecSpec.SinkSpec.Tee(
                            primary = JvmExecSpec.SinkSpec.Capture(maxBytes = 1024, keep = ExecSpec.Keep.Head),
                            branches = listOf(JvmExecSpec.SinkSpec.File(branchFile, write = FileWritePolicy.Append(false), maxBytes = null))
                        )
                    ),
                    stderr = JvmExecSpec.StderrSpec.Discard,
                )
            )
        assertTrue(r.ok)
        assertEquals("tee", r.stdout!!.text())
        assertEquals("tee", readText(branchFile))
    }

    @Test
    fun cleanupTimeout_must_be_positive() {
        assertFailsWith<IllegalArgumentException> {
            Exec.execBlocking(
                spec(
                    "--out", "x",
                    cleanupTimeout = Duration.ZERO
                )
            )
        }
    }

    @Test
    fun execBlocking_timeout_initiates_shutdown_and_process_is_dead() {
        val pidFile = tmpFile("pid-", ".txt")
        val ex =
            assertFailsWith<ExecException> {
                Exec.execBlocking(
                    spec(
                        "--pid-file", pidFile.toString(),
                        "--sleep-ms", "5000",
                        timeout = 500.milliseconds,
                        shutdown = ShutdownPolicy.KillTree,
                        stderr = JvmExecSpec.StderrSpec.Discard,
                    )
                )
            }
        assertTrue(ex.error is ExecError.TimedOut)
        val pid =
            if (Files.exists(pidFile)) parsePidFileOrThrow(pidFile, within = 2.seconds)
            else ex.error.meta.pid ?: error("No pid file and no meta.pid")
        assertDeadEventually(pid, within = 2.seconds)
    }

    @Test
    fun execBlocking_kills_process_descendants_on_timeout() {
        val parentPid = tmpFile("parent-pid-", ".txt")
        val childPid = tmpFile("child-pid-", ".txt")

        val ex =
            assertFailsWith<ExecException> {
                Exec.execBlocking(
                    spec(
                        "--pid-file", parentPid.toString(),
                        "--child-pid-file", childPid.toString(),
                        "--spawn-child-sleep-ms", "5000",
                        "--sleep-ms", "5000",
                        timeout = 1.seconds,
                        shutdown = ShutdownPolicy.KillTree,
                        stderr = JvmExecSpec.StderrSpec.Discard,
                    )
                )
            }

        assertTrue(ex.error is ExecError.TimedOut)
        val pPid = if (Files.exists(parentPid)) parsePidFileOrThrow(parentPid, within = 2.seconds) else ex.error.meta.pid ?: 0L
        val cPid = parsePidFileOrThrow(childPid, within = 2.seconds)

        if (pPid != 0L) assertDeadEventually(pPid, within = 2.seconds)
        assertDeadEventually(cPid, within = 2.seconds)
    }

    // ---- Env policies --------------------------------------------------------------------------

    @Test
    fun envPolicy_clearAndSet_drops_PATH_while_hermetic_sets_it() {
        val key = "WABBIT_TEST_ENV"

        val clearBase: Map<String, String> =
            if (isWindows()) {
                // Keep Windows happy enough to run java, but still omit PATH.
                val sysRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
                val tmp = System.getenv("TEMP") ?: System.getenv("TMP") ?: System.getProperty("java.io.tmpdir")
                mapOf(
                    key to "1",
                    "SystemRoot" to sysRoot,
                    "TEMP" to tmp,
                    "TMP" to tmp,
                )
            } else {
                mapOf(key to "1")
            }

        val rClear =
            Exec.execBlocking(
                spec(
                    "--print-env", key,
                    env = EnvPolicy.ClearAndSet(clearBase),
                    stderr = JvmExecSpec.StderrSpec.Discard,
                )
            )
        assertEquals("1", rClear.stdout!!.text())

        val rPathClear =
            Exec.execBlocking(
                spec(
                    "--print-env", "PATH",
                    env = EnvPolicy.ClearAndSet(clearBase),
                    stderr = JvmExecSpec.StderrSpec.Discard,
                )
            )
        assertEquals("<null>", rPathClear.stdout!!.text())

        val rPathHerm =
            Exec.execBlocking(
                spec(
                    "--print-env", "PATH",
                    env = EnvPolicy.Hermetic(mapOf(key to "1")),
                    stderr = JvmExecSpec.StderrSpec.Discard,
                )
            )
        assertTrue(rPathHerm.stdout!!.text() != "<null>")
    }

    // ---- Suspend runner ------------------------------------------------------------------------

    @Test
    fun exec_suspend_success() = runBlocking {
        val r =
            Exec.exec(
                spec(
                    "--out", "hello",
                    "--err", "oops",
                )
            )
        assertTrue(r.ok)
        assertEquals("hello", r.stdout!!.text())
        assertEquals("oops", r.stderr!!.text())
    }

    @Test
    fun exec_suspend_timeout_throws_TimedOut() = runBlocking {
        val pidFile = tmpPath("pid-s-", ".txt")
        val ex =
            assertFailsWith<ExecException> {
                Exec.exec(
                    spec(
                        "--pid-file", pidFile.toString(),
                        "--sleep-ms", "5000",
                        timeout = 500.milliseconds,
                        shutdown = ShutdownPolicy.KillTree,
                        stderr = JvmExecSpec.StderrSpec.Discard,
                    )
                )
            }
        assertTrue(ex.error is ExecError.TimedOut)
        val pid =
            if (Files.exists(pidFile)) parsePidFileOrThrow(pidFile, within = 2.seconds)
            else ex.error.meta.pid ?: error("No pid file and no meta.pid")
        assertDeadEventually(pid, within = 2.seconds)
    }

    @Test
    fun exec_suspend_cancellation_kills_process_tree() = runBlocking {
        val pidFile = tmpPath("pid-cancel-", ".txt")
        val s =
            spec(
                "--pid-file", pidFile.toString(),
                "--sleep-ms", "5000",
                timeout = null,
                shutdown = ShutdownPolicy.KillTree,
                stderr = JvmExecSpec.StderrSpec.Discard,
            )

        val job = launch {
            Exec.exec(s) // will be cancelled
        }

        // Wait until the subprocess writes its pid (file non-empty).
        // Must be suspending; blocking here can starve the launched coroutine on runBlocking's event loop.
        val pid = parsePidFileOrThrowSuspend(pidFile, within = 5.seconds)
        job.cancelAndJoin()
        assertDeadEventually(pid, within = 5.seconds)
    }

    @Test
    fun exec_suspend_capture_kill_on_overflow_throws_OutputLimitExceeded_and_includes_captures() = runBlocking {
        val ex =
            assertFailsWith<ExecException> {
                Exec.exec(
                    spec(
                        "--spam-out", "10000",
                        stdout = JvmExecSpec.StdoutSpec.Pipe(
                            JvmExecSpec.SinkSpec.Capture(
                                maxBytes = 128,
                                keep = ExecSpec.Keep.Head,
                                overflow = ExecSpec.OverflowPolicy.KillProcess
                            )
                        ),
                        stderr = JvmExecSpec.StderrSpec.Discard,
                        shutdown = ShutdownPolicy.KillTree,
                    )
                )
            }
        val err = ex.error
        assertTrue(err is ExecError.OutputLimitExceeded)
        assertEquals(StreamId.STDOUT, (err as ExecError.OutputLimitExceeded).stream)
        assertNotNull(err.captures)
        assertTrue(err.captures!!.stdoutStats!!.truncated)
        assertTrue(err.captures!!.stdoutStats!!.bytesRead > 128)
    }

    // ---- Loom runner ---------------------------------------------------------------------------

    @Test
    fun execVirtual_behaves_reasonably_on_supported_jdks() {
        val ver = javaMajor()
        val s =
            spec(
                "--out", "hello",
                "--err", "oops",
            )
        if (ver >= 21) {
            val r = Exec.execBlocking(s, virtualThreads = VirtualThreadsPolicy.Require)
            assertTrue(r.ok)
            assertEquals("hello", r.stdout!!.text())
            assertEquals("oops", r.stderr!!.text())
        } else {
            assertFailsWith<IllegalStateException> {
                Exec.execBlocking(s, virtualThreads = VirtualThreadsPolicy.Require)
            }
        }
    }

    // ---- Spawn runner -------------------------------------------------------------------------

    @Test
    fun spawnBlocking_detach_can_timebox_wait_without_killing_and_killTree_works() {
        val pidFile = tmpPath("pid-spawn-", ".txt")

        val rp =
            Exec.spawnBlocking(
                JvmSpawnSpec(
                    argv =
                        fixtureArgv(
                            "--pid-file", pidFile.toString(),
                            "--sleep-ms", "5000",
                        ),
                    stdin = JvmSpawnSpec.Input.None,
                    stdout = JvmSpawnSpec.StdoutSpec.Discard,
                    stderr = JvmSpawnSpec.StderrSpec.Discard,
                    shutdown = ShutdownPolicy.KillTree,
                )
            )

        val pid = parsePidFileOrThrow(pidFile, within = 2.seconds)
        assertTrue(isAlive(pid))

        val exit = rp.awaitExitBlocking(200.milliseconds)
        assertNull(exit) // should still be running; timeout must not kill

        rp.killTree()
        assertDeadEventually(pid, within = 2.seconds)
    }

    // ---- Exception Handling

    /**
     * Regression: do not swallow truly fatal JVM errors even if they occur after shutdown was initiated.
     *
     * This specifically targets the stdin Writer path where a "kill.wasKilled()" branch used to
     * `return` and silently swallow everything (including VirtualMachineError).
     */
    @Test
    fun execBlocking_timeout_then_stdin_writer_fatal_is_propagated_even_if_killed() {
        // Ensure our test thread starts clean (and doesn't poison the suite if the regression reappears).
        Thread.interrupted()

        val pidFile = tmpPath("pid-fatal-stdin-", ".txt")

        try {
            val ex =
                assertFailsWith<OutOfMemoryError> {
                    Exec.execBlocking(
                        spec(
                            "--pid-file", pidFile.toString(),
                            "--sleep-ms", "5000",
                            // Ensure the run initiates shutdown quickly.
                            timeout = 100.milliseconds,
                            shutdown = ShutdownPolicy.KillTree,
                            cleanupTimeout = 2.seconds,
                            // Keep I/O simple: only stdin task exists.
                            stdout = JvmExecSpec.StdoutSpec.Discard,
                            stderr = JvmExecSpec.StderrSpec.Discard,
                            stdin =
                                JvmExecSpec.Input.Writer { _ ->
                                    // Let timeout fire and killOnce() happen before we throw.
                                    Thread.sleep(300)
                                    throw OutOfMemoryError("boom-from-stdin-writer")
                                },
                        ),
                        virtualThreads = VirtualThreadsPolicy.Never,
                    )
                }
            assertContains(ex.message ?: "", "boom-from-stdin-writer")
        } finally {
            // If the process wrote its pid, it must be dead (no orphans).
            if (Files.exists(pidFile)) {
                val pid = parsePidFileOrThrow(pidFile, within = 2.seconds)
                assertDeadEventually(pid, within = 5.seconds)
            }
            // Clear any interrupt status we might have inherited from the universe.
            Thread.interrupted()
        }

        // Critical: worker failures must not set the caller thread's interrupt flag.
        assertFalse(Thread.currentThread().isInterrupted)
    }

    /**
     * Regression: a worker task throwing InterruptedException must NOT "teleport" interruption onto
     * the caller thread by restoring the caller's interrupt flag.
     *
     * This targets the "unwrap ExecutionException cause; propagateIfNeeded(cause)" path. If that call
     * uses the default policy (restoreInterrupt=true), the caller thread gets interrupted, which is
     * garbage behavior.
     */
    @Test
    fun execBlocking_worker_InterruptedException_does_not_interrupt_caller_thread() {
        Thread.interrupted() // clear

        val pidFile = tmpPath("pid-interrupt-xthread-", ".txt")

        try {
            assertFailsWith<InterruptedException> {
                Exec.execBlocking(
                    spec(
                        "--pid-file", pidFile.toString(),
                        "--spam-out", "10000",
                        timeout = null,
                        // Force a worker-side InterruptedException from the stdout consumer.
                        stdout =
                            JvmExecSpec.StdoutSpec.Pipe(
                                JvmExecSpec.SinkSpec.Stream(
                                    onChunk = { _, _, _ -> throw InterruptedException("boom-from-worker") },
                                    copyChunks = false,
                                    maxBytes = null,
                                    overflow = ExecSpec.OverflowPolicy.DrainAndTruncate,
                                ),
                            ),
                        stderr = JvmExecSpec.StderrSpec.Discard,
                    ),
                    virtualThreads = VirtualThreadsPolicy.Never,
                )
            }

            // If this becomes true, you reintroduced cross-thread interrupt poisoning.
            assertFalse(
                Thread.currentThread().isInterrupted,
                "caller thread interrupt flag must not be set by worker InterruptedException",
            )
        } finally {
            // Best-effort cleanup: process should be dead anyway, but don't leave orphans in a failing build.
            if (Files.exists(pidFile)) {
                val pid = parsePidFileOrThrow(pidFile, within = 2.seconds)
                assertDeadEventually(pid, within = 5.seconds)
            }
            Thread.interrupted()
        }
    }

    /**
     * Regression: if the caller thread is interrupted while waiting for the child to exit,
     * we must preserve the interrupt flag (InterruptedException clears it) and treat the run as cancelled
     * unless the interrupt was truly "late" (process already done and cleanup finished).
     *
     * This also guards against "best-effort" probes (waitFor/exitValue) accidentally swallowing
     * InterruptedException via runCatching(Throwable).
     */
    @Test
    fun execBlocking_interrupt_cancels_and_preserves_interrupt_flag() {
        Thread.interrupted() // clear

        val pidFile = tmpPath("pid-interrupt-main-", ".txt")
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val me = Thread.currentThread()

        val interrupter =
            Thread({
                // Wait until the subprocess is definitely running, then interrupt the caller thread.
                // (We don't care what the PID is here; we just want "process definitely started".)
                waitForNonBlankFileText(pidFile, within = 2.seconds) ?: return@Thread
                if (done.get()) return@Thread
                try { Thread.sleep(200) } catch (_: InterruptedException) {}
                if (!done.get()) me.interrupt()
            }, "execBlocking-interrupter").apply {
                isDaemon = true
                start()
            }

        try {
            val ex =
                assertFailsWith<ExecException> {
                    Exec.execBlocking(
                        spec(
                            "--pid-file", pidFile.toString(),
                            "--sleep-ms", "5000",
                            timeout = null,
                            shutdown = ShutdownPolicy.KillTree,
                            stdout = JvmExecSpec.StdoutSpec.Discard,
                            stderr = JvmExecSpec.StderrSpec.Discard,
                        ),
                        virtualThreads = VirtualThreadsPolicy.Never,
                    )
                }

            assertTrue(ex.error is ExecError.Cancelled)
            assertTrue(
                Thread.currentThread().isInterrupted,
                "caller thread interrupt flag must be preserved",
            )
        } finally {
            done.set(true)

            // This test intentionally leaves the current thread interrupted (that's the point).
            // Unfortunately, our test cleanup helpers use Thread.sleep() polling, and sleep throws
            // immediately when the interrupt flag is set. Clear it *before* cleanup so we don't
            // fail the test suite in the cleanup path.
            Thread.interrupted()

            // Best-effort: reduce the race where the interrupter thread fires after we've started cleanup.
            try { interrupter.join(1000) } catch (_: InterruptedException) { Thread.interrupted() }

            // If the process wrote its pid, it must be dead (no orphans).
            if (Files.exists(pidFile)) {
                val pid = parsePidFileOrThrow(pidFile, within = 2.seconds)
                assertDeadEventually(pid, within = 5.seconds)
            }
            Thread.interrupted() // clear for the rest of the suite
        }
    }

    /**
     * Regression: coroutine cancellation must win over "normal" failures even if the process has
     * already exited and the function is in its final bookkeeping path.
     *
     * Without an explicit cancellation check near the end (e.g. ensureActive()), it's possible to
     * throw ExecException(ExitNonZero) even though the caller cancelled the Job after the process exited.
     */
    @Test
    fun exec_suspend_cancel_after_process_exit_beats_exitNonZero() = runBlocking {
        val pidFile = tmpPath("pid-cancel-after-exit-", ".txt")

        // Enough output to create a real window for cancellation after process exit,
        // while exec is still finishing/assembling results.
        val spamBytes = 8 * 1024 * 1024 // 8 MiB

        val s =
            spec(
                "--pid-file", pidFile.toString(),
                "--spam-out", spamBytes.toString(),
                "--exit", "7",
                timeout = null,
                shutdown = ShutdownPolicy.KillTree,
                cleanupTimeout = 5.seconds,
                exitPolicy = ExitPolicy.ThrowOnNonZero,
                stdout =
                    JvmExecSpec.StdoutSpec.Pipe(
                        JvmExecSpec.SinkSpec.Capture(
                            maxBytes = spamBytes + 1024,
                            keep = ExecSpec.Keep.Head,
                            overflow = ExecSpec.OverflowPolicy.DrainAndTruncate,
                        ),
                    ),
                stderr = JvmExecSpec.StderrSpec.Discard,
            )

        val d = async { Exec.exec(s) }

        val pid = parsePidFileOrThrowSuspend(pidFile, within = 5.seconds)

        // Wait until the child exits (process boundary crossed), then cancel the coroutine.
        while (isAlive(pid)) {
            delay(1)
        }

        // Cancellation isn't preemptive; be annoying about it for a short window.
        repeat(500) {
            if (d.isCompleted) return@repeat
            d.cancel()
            delay(1)
        }

        assertFailsWith<CancellationException> { d.await() }
        assertDeadEventually(pid, within = 5.seconds)
    }



    private fun javaMajor(): Int {
        val v = System.getProperty("java.specification.version") ?: return 0
        return if (v.startsWith("1.")) v.substringAfter("1.").toIntOrNull() ?: 0
        else v.toIntOrNull() ?: 0
    }
}
