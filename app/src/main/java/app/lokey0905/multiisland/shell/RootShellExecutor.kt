package app.lokey0905.multiisland.shell

import app.lokey0905.multiisland.data.model.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class RootShellExecutor : ShellExecutor {

    companion object {
        private const val PROCESS_TIMEOUT_SECONDS = 20L
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val result = execWithSu("id")
            result.exitCode == 0
        }.getOrDefault(false)
    }

    override suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext ShellResult(
                command = command,
                exitCode = -1,
                stdout = "",
                stderr = "Root (su) is not available",
                success = false
            )
        }

        runCatching { execWithSu(command) }
            .getOrElse { throwable ->
                ShellResult(
                    command = command,
                    exitCode = -1,
                    stdout = "",
                    stderr = throwable.message ?: "Root exec error",
                    success = false
                )
            }
    }

    private suspend fun execWithSu(command: String): ShellResult = coroutineScope {
        val process = ProcessBuilder("su", "-c", command).start()

        // 併發讀取 stdout/stderr，避免其中一個 buffer 塞滿造成卡死。
        val stdoutDeferred = async(Dispatchers.IO) {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        val stderrDeferred = async(Dispatchers.IO) {
            process.errorStream.bufferedReader().use { it.readText() }
        }

        val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }

        val stdout = stdoutDeferred.await()
        val stderrRaw = stderrDeferred.await()
        val exitCode = if (finished) process.exitValue() else -1
        val stderr = if (finished) stderrRaw else {
            val suffix = "Process timed out after ${PROCESS_TIMEOUT_SECONDS}s"
            if (stderrRaw.isBlank()) suffix else "$stderrRaw\n$suffix"
        }

        ShellResult(
            command = command,
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            success = finished && exitCode == 0
        )
    }
}
