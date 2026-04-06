package app.lokey0905.multiisland.shell

import android.content.Context
import android.content.pm.PackageManager
import app.lokey0905.multiisland.data.model.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import rikka.sui.Sui
import java.io.InputStream
import java.util.concurrent.TimeUnit

class ShizukuShellExecutor(
    private val context: Context
) : ShellExecutor {

    companion object {
        private const val PROCESS_TIMEOUT_SECONDS = 20L
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        getServiceStatus() == ShizukuServiceStatus.Ready
    }

    fun isShizukuAppInstalled(): Boolean {
        val packageManager = context.packageManager
        return runCatching { packageManager.getPackageInfo("moe.shizuku.manager", 0) }.isSuccess
    }

    fun isBinderAvailable(): Boolean {
        return runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    }

    fun isPermissionGranted(): Boolean {
        return runCatching {
            isBinderAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun getServiceStatus(): ShizukuServiceStatus {
        // 先嘗試 Sui 初始化；Sui 沒有 manager app，不能只靠 package name 判斷。
        val suiAvailable = runCatching { Sui.isSui() || Sui.init(context.packageName) }.getOrDefault(false)
        val managerInstalled = isShizukuAppInstalled()
        val binderAvailable = isBinderAvailable()

        if (binderAvailable) {
            return if (isPermissionGranted()) {
                ShizukuServiceStatus.Ready
            } else {
                ShizukuServiceStatus.RunningButUnauthorized
            }
        }

        if (managerInstalled || suiAvailable) {
            return ShizukuServiceStatus.InstalledButNotRunning
        }

        return ShizukuServiceStatus.NotInstalledOrUnavailable
    }

    fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

    override suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        when (getServiceStatus()) {
            ShizukuServiceStatus.NotInstalledOrUnavailable -> {
                return@withContext ShellResult(command, -1, "", "Shizuku/Sui not installed or unavailable", false)
            }
            ShizukuServiceStatus.InstalledButNotRunning -> {
                return@withContext ShellResult(command, -1, "", "Shizuku/Sui installed but service is not running", false)
            }
            ShizukuServiceStatus.RunningButUnauthorized -> {
                return@withContext ShellResult(command, -1, "", "Shizuku/Sui is running but permission is not granted", false)
            }
            ShizukuServiceStatus.Ready -> Unit
        }

        runCatching { runShizukuCommand(command) }
            .getOrElse { throwable ->
                val detail = buildString {
                    append(throwable.javaClass.simpleName)
                    throwable.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                    throwable.cause?.let { cause ->
                        append(" | cause=")
                        append(cause.javaClass.simpleName)
                        cause.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                    }
                }
                ShellResult(
                    command = command,
                    exitCode = -1,
                    stdout = "",
                    stderr = if (detail.isBlank()) "Shizuku exec error" else "Shizuku exec error: $detail",
                    success = false
                )
            }
    }

    private suspend fun runShizukuCommand(command: String): ShellResult = coroutineScope {
        val remoteProcess = createRemoteProcess(command)
            ?: return@coroutineScope ShellResult(
                command = command,
                exitCode = -1,
                stdout = "",
                stderr = "Shizuku process API unavailable; please use a Shizuku version that supports shell transition APIs",
                success = false
            )

        val inputStream = remoteProcess.javaClass.getMethod("getInputStream").invoke(remoteProcess) as InputStream
        val errorStream = remoteProcess.javaClass.getMethod("getErrorStream").invoke(remoteProcess) as InputStream

        // 併發讀取 stdout/stderr，避免遠端程序輸出塞住而無法結束。
        val stdoutDeferred = async(Dispatchers.IO) {
            inputStream.bufferedReader().use { it.readText() }
        }
        val stderrDeferred = async(Dispatchers.IO) {
            errorStream.bufferedReader().use { it.readText() }
        }

        val waitForMethod = remoteProcess.javaClass.getMethod("waitFor")
        val exitValueMethod = remoteProcess.javaClass.getMethod("exitValue")
        val destroyMethod = remoteProcess.javaClass.getMethod("destroy")
        val destroyForciblyMethod = runCatching {
            remoteProcess.javaClass.getMethod("destroyForcibly")
        }.getOrNull()

        // 某些實作沒有 waitFor(timeout)，這裡用 coroutine timeout 包住 waitFor() 兼容處理。
        val finished = withTimeoutOrNull(TimeUnit.SECONDS.toMillis(PROCESS_TIMEOUT_SECONDS)) {
            waitForMethod.invoke(remoteProcess)
            true
        } ?: false

        if (!finished) {
            destroyMethod.invoke(remoteProcess)
            destroyForciblyMethod?.invoke(remoteProcess)
        }

        val stdout = stdoutDeferred.await()
        val stderrRaw = stderrDeferred.await()
        val exitCode = if (finished) (exitValueMethod.invoke(remoteProcess) as Int) else -1
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

    private fun createRemoteProcess(command: String): Any? {
        return runCatching {
            // 這是 Shizuku 官方標示為遷移用途的舊 shell 介面，先保留 MVP 可用性。
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, arrayOf("sh", "-c", command), null, null)
        }.getOrNull()
    }
}
