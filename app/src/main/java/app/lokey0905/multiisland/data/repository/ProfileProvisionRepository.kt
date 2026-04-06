package app.lokey0905.multiisland.data.repository

import app.lokey0905.multiisland.data.model.DeviceCapabilities
import app.lokey0905.multiisland.data.model.ShellResult
import app.lokey0905.multiisland.data.model.UserInfo
import app.lokey0905.multiisland.shell.ShellExecutor

class ProfileProvisionRepository(
    private val shellExecutor: ShellExecutor
) {

    suspend fun createManagedProfile(
        profileName: String,
        packageName: String,
        componentName: String
    ): List<ShellResult> {
        val results = mutableListOf<ShellResult>()

        val createResult = shellExecutor.exec("pm create-user --profileOf 0 --managed $profileName")
        results += createResult
        if (!createResult.success) return results

        // 解析 create-user 回傳，確保 userId 不是寫死
        val userId = parseCreatedUserId("${createResult.stdout}\n${createResult.stderr}")
        if (userId == null) {
            results += ShellResult(
                command = "parse userId",
                exitCode = -1,
                stdout = createResult.stdout,
                stderr = "Unable to parse userId from create-user output",
                success = false
            )
            return results
        }

        val startResult = shellExecutor.exec("am start-user $userId")
        results += startResult

        val installResult = shellExecutor.exec("pm install-existing --user $userId $packageName")
        results += installResult

        val ownerResult = shellExecutor.exec("dpm set-profile-owner --user $userId $componentName")
        results += ownerResult

        return results
    }

    suspend fun listUsers(): List<UserInfo> {
        val result = shellExecutor.exec("pm list users")
        if (!result.success && result.stdout.isBlank()) return emptyList()
        return parseUsers(result.stdout)
    }

    suspend fun startUser(userId: Int): ShellResult = shellExecutor.exec("am start-user $userId")

    suspend fun stopUser(userId: Int): ShellResult = shellExecutor.exec("am stop-user -w $userId")

    suspend fun removeUser(userId: Int): ShellResult = shellExecutor.exec("pm remove-user $userId")

    suspend fun installExistingForUser(userId: Int, packageName: String): ShellResult {
        return shellExecutor.exec("pm install-existing --user $userId $packageName")
    }

    suspend fun uninstallForUser(userId: Int, packageName: String): ShellResult {
        return shellExecutor.exec("pm uninstall --user $userId $packageName")
    }

    suspend fun setProfileOwner(userId: Int, componentName: String): ShellResult {
        return shellExecutor.exec("dpm set-profile-owner --user $userId $componentName")
    }

    suspend fun getMaxUsers(): Int? {
        val result = shellExecutor.exec("pm get-max-users")
        return parseFirstInt(result.stdout)
    }

    suspend fun getMaxRunningUsers(): Int? {
        val result = shellExecutor.exec("pm get-max-running-users")
        if (!result.success) return null
        return parseFirstInt(result.stdout)
    }

    suspend fun getDeviceCapabilities(): DeviceCapabilities {
        val users = listUsers()
        return DeviceCapabilities(
            maxUsers = getMaxUsers(),
            maxRunningUsers = getMaxRunningUsers(),
            currentUserCount = users.size,
            users = users
        )
    }

    suspend fun testCommand(command: String): ShellResult = shellExecutor.exec(command)

    private fun parseCreatedUserId(rawOutput: String): Int? {
        val regex = Regex("""created user id\s+(\d+)""", RegexOption.IGNORE_CASE)
        return regex.find(rawOutput)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun parseUsers(rawOutput: String): List<UserInfo> {
        val regex = Regex("""UserInfo\{(\d+):([^:}]+):([^}]+)\}(?:\s+(\w+))?""")
        return rawOutput
            .lineSequence()
            .mapNotNull { line ->
                val match = regex.find(line.trim()) ?: return@mapNotNull null
                val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val name = match.groupValues[2].trim()
                val flags = match.groupValues[3].trim()
                val stateTag = match.groupValues.getOrNull(4)?.trim()
                UserInfo(
                    id = id,
                    name = name,
                    flagsRaw = flags,
                    isRunning = when {
                        stateTag.equals("running", ignoreCase = true) -> true
                        stateTag.equals("stopped", ignoreCase = true) -> false
                        stateTag.isNullOrBlank() -> false
                        else -> null
                    }
                )
            }
            .toList()
    }

    private fun parseFirstInt(rawOutput: String): Int? {
        val match = Regex("""(-?\d+)""").find(rawOutput.trim()) ?: return null
        return match.value.toIntOrNull()
    }
}
