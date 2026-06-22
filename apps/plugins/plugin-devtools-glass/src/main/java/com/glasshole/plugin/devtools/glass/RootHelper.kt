package com.glasshole.plugin.devtools.glass

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Tiny `su` wrapper. Spawns `Runtime.exec("su")`, pipes the command in,
 * collects stdout/stderr, hands the result back on the main thread.
 *
 * Magisk / SuperSU show a system-level prompt on the first call —
 * grant once and subsequent calls auto-approve (or auto-deny) based on
 * the user's chosen policy.
 *
 * Background-thread blocking I/O. The callback always lands on the
 * main thread.
 */
object RootHelper {

    private const val TAG = "DevToolsRoot"

    data class Result(
        val available: Boolean,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val ok: Boolean get() = available && exitCode == 0
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Cached at first detect(); reset() is intentionally NOT exposed — if
     *  root state changes we want a fresh check on next call instead. */
    @Volatile private var cachedAvailable: Boolean? = null

    /** Quick + cheap "is root available?" check. Spawns su, runs `id`,
     *  looks for uid=0. Caches the answer so subsequent calls don't
     *  re-prompt the user. */
    fun detect(onResult: (Boolean) -> Unit) {
        val cached = cachedAvailable
        if (cached != null) {
            mainHandler.post { onResult(cached) }
            return
        }
        Thread {
            val r = runInternal("id")
            val granted = r.available && r.exitCode == 0 && r.stdout.contains("uid=0")
            cachedAvailable = granted
            mainHandler.post { onResult(granted) }
        }.apply { isDaemon = true; name = "RootHelper-detect" }.start()
    }

    /** Run an arbitrary single-line shell command as root. Output is
     *  captured to [Result.stdout] and [Result.stderr]; the callback
     *  fires on the main thread. */
    fun run(command: String, onResult: (Result) -> Unit) {
        Thread {
            val r = runInternal(command)
            mainHandler.post { onResult(r) }
        }.apply { isDaemon = true; name = "RootHelper-run" }.start()
    }

    private fun runInternal(command: String): Result {
        val proc: Process = try {
            Runtime.getRuntime().exec("su")
        } catch (e: Exception) {
            Log.w(TAG, "exec su failed: ${e.message}")
            return Result(available = false, exitCode = -1,
                stdout = "", stderr = "su not found: ${e.message}")
        }
        return try {
            proc.outputStream.bufferedWriter().use { w ->
                w.write(command)
                w.newLine()
                w.write("exit")
                w.newLine()
                w.flush()
            }
            val stdout = drain(BufferedReader(InputStreamReader(proc.inputStream)))
            val stderr = drain(BufferedReader(InputStreamReader(proc.errorStream)))
            val exit = proc.waitFor()
            Result(available = true, exitCode = exit, stdout = stdout, stderr = stderr)
        } catch (e: Exception) {
            Log.w(TAG, "su pipe failed: ${e.message}")
            Result(available = true, exitCode = -1, stdout = "", stderr = "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { proc.destroy() } catch (_: Exception) {}
        }
    }

    private fun drain(r: BufferedReader): String = r.useLines { it.joinToString("\n") }
}
