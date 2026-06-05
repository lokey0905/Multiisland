package app.lokey0905.multiisland.data.repository

import app.lokey0905.multiisland.data.model.DeviceCapabilities
import app.lokey0905.multiisland.data.model.ShellResult
import app.lokey0905.multiisland.data.model.UserInfo
import app.lokey0905.multiisland.shell.ShellExecutor
import kotlinx.coroutines.delay

class ProfileProvisionRepository(
    private val shellExecutor: ShellExecutor
) {
    companion object {
        private const val ISLAND_PACKAGE = "com.oasisfeng.island"
        private const val ISLAND_PROVISIONING_COMPONENT = "$ISLAND_PACKAGE/.provisioning.IslandProvisioning"
        private const val ISLAND_SETTINGS_COMPONENT = "$ISLAND_PACKAGE/.settings.IslandSettingsActivity"
        private const val ACTION_PROVISION_MANAGED_PROFILE = "android.app.action.PROVISION_MANAGED_PROFILE"
        private const val ACTION_MAIN = "android.intent.action.MAIN"
        private const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
        private const val PACKAGE_PLAY_STORE = "com.android.vending"
        private const val PACKAGE_GMS = "com.google.android.gms"
        private const val PACKAGE_GSF = "com.google.android.gsf"
        private const val PACKAGE_GBOARD = "com.google.android.inputmethod.latin"
        private const val COMPONENT_GBOARD_IME = "$PACKAGE_GBOARD/com.android.inputmethod.latin.LatinIME"
        private const val REFERENCE_PROFILE_USER_ID = 10
        private const val INITIALIZATION_WAIT_MS = 3_000L
        private const val ISLAND_REPROVISION_WAIT_MS = 12_000L
        private const val GMS_SUSPENDED_WARNING =
            "GMS is still suspended by profile policy. This profile may require modified Island APK or rebuilding after patching Island."

        private val CRITICAL_PACKAGES = listOf(
            PACKAGE_PLAY_STORE,
            PACKAGE_GMS,
            PACKAGE_GSF,
            PACKAGE_GBOARD
        )

        private val PROTECTED_PACKAGES = setOf(
            ISLAND_PACKAGE,
            PACKAGE_PLAY_STORE,
            PACKAGE_GMS,
            PACKAGE_GSF,
            PACKAGE_GBOARD,
            "com.google.android.configupdater",
            "com.google.android.partnersetup",
            "com.google.android.webview",
            "com.android.webview",
            "com.android.settings",
            "com.android.documentsui",
            "com.google.android.documentsui",
            "com.android.providers.downloads.ui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.miui.securitycenter",
            "com.miui.securitycore"
        )
    }

    suspend fun createManagedProfile(
        profileName: String,
        packageName: String,
        componentName: String
    ): List<ShellResult> {
        val results = mutableListOf<ShellResult>()

        val createResult = shellExecutor.exec("pm create-user --profileOf 0 --managed ${shellQuote(profileName)}")
        results += createResult
        if (!createResult.success) return results

        val userId = parseCreatedUserId("${createResult.stdout}\n${createResult.stderr}")
        if (userId == null) {
            results += ShellResult(
                command = "parse userId from pm create-user",
                exitCode = -1,
                stdout = "${createResult.stdout}\n${createResult.stderr}".trim(),
                stderr = "Unable to parse userId from create-user output",
                success = false
            )
            return results
        }

        results += shellExecutor.exec("am start-user $userId")
        results += waitResult(
            command = "wait ${INITIALIZATION_WAIT_MS}ms for user/package/settings initialization",
            millis = INITIALIZATION_WAIT_MS
        )

        results += shellExecutor.exec("pm install-existing --user $userId ${shellQuote(packageName)}")

        val ownerResult = shellExecutor.exec("dpm set-profile-owner --user $userId ${shellQuote(componentName)}")
        results += ownerResult
        if (!ownerResult.success) return results

        results += repairProfileAfterCreate(userId)

        return results
    }

    suspend fun repairProfileAfterCreate(userId: Int): List<ShellResult> {
        val results = mutableListOf<ShellResult>()

        results += shellExecutor.exec("am start-user $userId")
        results += shellExecutor.exec("settings --user $userId put secure user_setup_complete 1")
        results += shellExecutor.exec("settings --user $userId put global device_provisioned 1")
        results += ensureCriticalPackagesForUser(userId)

        results += shellExecutor.exec("ime enable --user $userId ${shellQuote(COMPONENT_GBOARD_IME)}")
        results += shellExecutor.exec(
            "settings --user $userId put secure default_input_method ${shellQuote(COMPONENT_GBOARD_IME)}"
        )

        val foregroundServiceResult = shellExecutor.exec(
            "am start-foreground-service --user $userId " +
                "-a ${shellQuote(ACTION_PROVISION_MANAGED_PROFILE)} " +
                "-n ${shellQuote(ISLAND_PROVISIONING_COMPONENT)}"
        )
        results += foregroundServiceResult
        if (!foregroundServiceResult.success) {
            results += shellExecutor.exec(
                "am start-service --user $userId " +
                    "-a ${shellQuote(ACTION_PROVISION_MANAGED_PROFILE)} " +
                    "-n ${shellQuote(ISLAND_PROVISIONING_COMPONENT)}"
            )
        }

        results += waitResult(
            command = "wait ${ISLAND_REPROVISION_WAIT_MS}ms for Island re-provision",
            millis = ISLAND_REPROVISION_WAIT_MS
        )

        results += cleanupSystemLauncherAppsLikeReferenceProfile(userId)
        results += ensureCriticalPackagesForUser(userId)
        results += openIslandSettingsActivity(userId)
        results += verifyGmsState(userId)

        return results
    }

    suspend fun previewCleanupSystemLauncherAppsLikeReferenceProfile(
        userId: Int,
        referenceUserId: Int = REFERENCE_PROFILE_USER_ID
    ): List<ShellResult> {
        val results = mutableListOf<ShellResult>()
        val planResult = collectLauncherCleanupPlan(userId, referenceUserId)
        results += planResult.results
        results += buildCleanupSummaryResult(
            command = "preview cleanup system launchers like user $referenceUserId for user $userId",
            referenceUserId = referenceUserId,
            targetUserId = userId,
            plan = planResult.plan,
            failedPackages = emptyList(),
            hiddenFallbackPackages = emptyList(),
            skippedReason = planResult.skippedReason,
            dryRun = true
        )
        return results
    }

    suspend fun cleanupSystemLauncherAppsLikeReferenceProfile(
        userId: Int,
        referenceUserId: Int = REFERENCE_PROFILE_USER_ID
    ): List<ShellResult> {
        val results = mutableListOf<ShellResult>()
        val validationResult = validateCleanupUserId(userId)
        results += validationResult
        if (!validationResult.success) return results

        val planResult = collectLauncherCleanupPlan(userId, referenceUserId)
        results += planResult.results
        val plan = planResult.plan
        if (plan == null) {
            results += buildCleanupSummaryResult(
                command = "cleanup system launchers like user $referenceUserId for user $userId",
                referenceUserId = referenceUserId,
                targetUserId = userId,
                plan = null,
                failedPackages = emptyList(),
                hiddenFallbackPackages = emptyList(),
                skippedReason = planResult.skippedReason,
                dryRun = false
            )
            return results
        }

        val failedPackages = mutableListOf<String>()
        val hiddenFallbackPackages = mutableListOf<String>()
        plan.packagesToCleanup.forEach { packageName ->
            val uninstallResult = shellExecutor.exec("pm uninstall -k --user $userId ${shellQuote(packageName)}")
            results += uninstallResult
            if (!uninstallResult.success) {
                val hideResult = shellExecutor.exec("pm hide --user $userId ${shellQuote(packageName)}")
                results += hideResult
                if (hideResult.success) {
                    hiddenFallbackPackages += packageName
                } else {
                    failedPackages += packageName
                }
            }
        }

        results += buildCleanupSummaryResult(
            command = "cleanup system launchers like user $referenceUserId for user $userId",
            referenceUserId = referenceUserId,
            targetUserId = userId,
            plan = plan,
            failedPackages = failedPackages,
            hiddenFallbackPackages = hiddenFallbackPackages,
            skippedReason = null,
            dryRun = false
        )

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
        return shellExecutor.exec("pm install-existing --user $userId ${shellQuote(packageName)}")
    }

    suspend fun uninstallForUser(userId: Int, packageName: String): ShellResult {
        return shellExecutor.exec("pm uninstall --user $userId ${shellQuote(packageName)}")
    }

    suspend fun setProfileOwner(userId: Int, componentName: String): ShellResult {
        return shellExecutor.exec("dpm set-profile-owner --user $userId ${shellQuote(componentName)}")
    }

    suspend fun setDefaultImeForUser(userId: Int, imeComponent: String): ShellResult {
        return shellExecutor.exec("ime set --user $userId ${shellQuote(imeComponent)}")
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

    private suspend fun ensureCriticalPackagesForUser(userId: Int): List<ShellResult> {
        val results = mutableListOf<ShellResult>()
        CRITICAL_PACKAGES.forEach { packageName ->
            val packageArg = shellQuote(packageName)
            results += shellExecutor.exec("pm install-existing --user $userId $packageArg")
            results += shellExecutor.exec("pm enable --user $userId $packageArg")
            results += shellExecutor.exec("pm unhide --user $userId $packageArg")
            results += shellExecutor.exec("cmd package unsuspend --user $userId $packageArg")
            results += shellExecutor.exec("cmd appops reset --user $userId $packageArg")
        }
        return results
    }

    private suspend fun collectLauncherCleanupPlan(
        userId: Int,
        referenceUserId: Int
    ): LauncherCleanupPlanResult {
        val results = mutableListOf<ShellResult>()

        val referenceLaunchersResult = shellExecutor.exec(queryLauncherActivitiesCommand(referenceUserId))
        results += referenceLaunchersResult
        val targetLaunchersResult = shellExecutor.exec(queryLauncherActivitiesCommand(userId))
        results += targetLaunchersResult
        val referenceSystemPackagesResult = shellExecutor.exec(listSystemPackagesCommand(referenceUserId))
        results += referenceSystemPackagesResult
        val targetSystemPackagesResult = shellExecutor.exec(listSystemPackagesCommand(userId))
        results += targetSystemPackagesResult

        val sourceResults = listOf(
            referenceLaunchersResult,
            targetLaunchersResult,
            referenceSystemPackagesResult,
            targetSystemPackagesResult
        )
        val failedCommands = sourceResults.filterNot { it.success }.map { it.command }
        if (failedCommands.isNotEmpty()) {
            return LauncherCleanupPlanResult(
                results = results,
                plan = null,
                skippedReason = "Skipping cleanup because source query failed: ${failedCommands.joinToString()}"
            )
        }

        val referenceLauncherPackages = parseLauncherPackages(referenceLaunchersResult.stdout)
        val targetLauncherPackages = parseLauncherPackages(targetLaunchersResult.stdout)
        val referenceSystemPackages = parseListPackages(referenceSystemPackagesResult.stdout)
        val targetSystemPackages = parseListPackages(targetSystemPackagesResult.stdout)

        val referenceSystemLaunchers = referenceLauncherPackages.intersect(referenceSystemPackages)
        val targetSystemLaunchers = targetLauncherPackages.intersect(targetSystemPackages)
        val packagesToCleanup = (targetSystemLaunchers - referenceSystemLaunchers - PROTECTED_PACKAGES).sorted()

        return LauncherCleanupPlanResult(
            results = results,
            plan = LauncherCleanupPlan(
                referenceUserId = referenceUserId,
                targetUserId = userId,
                referenceSystemLaunchers = referenceSystemLaunchers.sorted(),
                targetSystemLaunchers = targetSystemLaunchers.sorted(),
                packagesToCleanup = packagesToCleanup
            ),
            skippedReason = null
        )
    }

    private fun queryLauncherActivitiesCommand(userId: Int): String {
        return "cmd package query-activities --user $userId --brief " +
            "-a ${shellQuote(ACTION_MAIN)} " +
            "-c ${shellQuote(CATEGORY_LAUNCHER)}"
    }

    private fun listSystemPackagesCommand(userId: Int): String {
        return "pm list packages --user $userId -s"
    }

    private fun buildCleanupSummaryResult(
        command: String,
        referenceUserId: Int,
        targetUserId: Int,
        plan: LauncherCleanupPlan?,
        failedPackages: List<String>,
        hiddenFallbackPackages: List<String>,
        skippedReason: String?,
        dryRun: Boolean
    ): ShellResult {
        val stdout = if (plan == null) {
            listOf(
                "referenceUserId=$referenceUserId",
                "targetUserId=$targetUserId",
                "referenceSystemLauncherCount=unknown",
                "targetSystemLauncherCount=unknown",
                "cleanupPackageCount=0",
                "cleanupPackages=(none)",
                "failedPackages=(none)"
            )
        } else {
            listOf(
                "referenceUserId=${plan.referenceUserId}",
                "targetUserId=${plan.targetUserId}",
                "referenceSystemLauncherCount=${plan.referenceSystemLaunchers.size}",
                "targetSystemLauncherCount=${plan.targetSystemLaunchers.size}",
                "cleanupPackageCount=${plan.packagesToCleanup.size}",
                "cleanupPackages=${plan.packagesToCleanup.ifEmpty { listOf("(none)") }.joinToString()}",
                "failedPackages=${failedPackages.ifEmpty { listOf("(none)") }.joinToString()}",
                "hiddenFallbackPackages=${hiddenFallbackPackages.ifEmpty { listOf("(none)") }.joinToString()}",
                "dryRun=$dryRun"
            )
        }.joinToString(separator = "\n")

        val success = skippedReason == null && failedPackages.isEmpty()
        return ShellResult(
            command = command,
            exitCode = if (success) 0 else -1,
            stdout = stdout,
            stderr = skippedReason.orEmpty(),
            success = success
        )
    }

    private suspend fun openIslandSettingsActivity(userId: Int): List<ShellResult> {
        val results = mutableListOf<ShellResult>()
        val resolveResult = shellExecutor.exec(
            "cmd package resolve-activity --user $userId --brief " +
                "-a ${shellQuote(ACTION_MAIN)} " +
                "-c ${shellQuote(CATEGORY_LAUNCHER)} " +
                shellQuote(ISLAND_PACKAGE)
        )
        results += resolveResult

        val resolvedComponent = if (resolveResult.success) {
            parseResolvedActivity("${resolveResult.stdout}\n${resolveResult.stderr}")
        } else {
            null
        }
        val componentToStart = resolvedComponent ?: ISLAND_SETTINGS_COMPONENT

        results += shellExecutor.exec("am start --user $userId -n ${shellQuote(componentToStart)}")

        return results
    }

    private suspend fun verifyGmsState(userId: Int): List<ShellResult> {
        val results = mutableListOf<ShellResult>()
        val dumpsysResult = shellExecutor.exec(
            "dumpsys package ${shellQuote(PACKAGE_GMS)} | " +
                "sed -n ${shellQuote("/User $userId:/,+12p")} | " +
                "head -n 13"
        )
        results += dumpsysResult

        if (!dumpsysResult.success) return results

        val rawOutput = "${dumpsysResult.stdout}\n${dumpsysResult.stderr}"
        val state = parsePackageUserState(rawOutput, userId)
        if (state == null) {
            results += ShellResult(
                command = "parse ${PACKAGE_GMS} state for user $userId",
                exitCode = -1,
                stdout = "",
                stderr = "Unable to parse ${PACKAGE_GMS} state for user $userId from dumpsys package output",
                success = false
            )
            return results
        }

        val suspended = state.suspended.equals("true", ignoreCase = true)
        results += ShellResult(
            command = "parse ${PACKAGE_GMS} state for user $userId",
            exitCode = if (suspended) 1 else 0,
            stdout = listOf(
                "installed=${state.installed ?: "unknown"}",
                "hidden=${state.hidden ?: "unknown"}",
                "suspended=${state.suspended ?: "unknown"}",
                "enabled=${state.enabled ?: "unknown"}"
            ).joinToString(separator = "\n"),
            stderr = if (suspended) GMS_SUSPENDED_WARNING else "",
            success = !suspended
        )

        return results
    }

    private suspend fun waitResult(command: String, millis: Long): ShellResult {
        delay(millis)
        return ShellResult(
            command = command,
            exitCode = 0,
            stdout = "Waited ${millis}ms",
            stderr = "",
            success = true
        )
    }

    private fun validateCleanupUserId(userId: Int): ShellResult {
        return when {
            userId <= 0 -> ShellResult(
                command = "validate cleanup targetUserId=$userId",
                exitCode = -1,
                stdout = "",
                stderr = "Refusing to cleanup owner/system user $userId",
                success = false
            )
            userId >= 900 -> ShellResult(
                command = "validate cleanup targetUserId=$userId",
                exitCode = -1,
                stdout = "",
                stderr = "Refusing to cleanup high-range user $userId",
                success = false
            )
            else -> ShellResult(
                command = "validate cleanup targetUserId=$userId",
                exitCode = 0,
                stdout = "targetUserId=$userId is allowed for launcher cleanup",
                stderr = "",
                success = true
            )
        }
    }

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

    private fun parseResolvedActivity(rawOutput: String): String? {
        val componentRegex = Regex("""[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+""")
        return rawOutput
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line -> componentRegex.find(line)?.value }
            .lastOrNull()
    }

    private fun parseLauncherPackages(rawOutput: String): Set<String> {
        val componentRegex = Regex("""\b([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)/[^\s]+""")
        val plainPackageRegex = Regex("""^([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)$""")
        return rawOutput
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                componentRegex.find(line)?.groupValues?.getOrNull(1)
                    ?: plainPackageRegex.find(line)?.groupValues?.getOrNull(1)
            }
            .toSet()
    }

    private fun parseListPackages(rawOutput: String): Set<String> {
        return rawOutput
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { line -> line.removePrefix("package:").substringBefore(' ').trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun parsePackageUserState(rawOutput: String, userId: Int): PackageUserState? {
        val userLineRegex = Regex("""\bUser\s+$userId:""")
        val userLine = rawOutput
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { userLineRegex.containsMatchIn(it) }
            ?: return null

        return PackageUserState(
            installed = parsePackageStateValue(userLine, "installed"),
            hidden = parsePackageStateValue(userLine, "hidden"),
            suspended = parsePackageStateValue(userLine, "suspended"),
            enabled = parsePackageStateValue(userLine, "enabled")
        )
    }

    private fun parsePackageStateValue(userLine: String, key: String): String? {
        return Regex("""\b$key=([^\s]+)""")
            .find(userLine)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun shellQuote(value: String): String {
        if (value.isEmpty()) return "''"
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private data class PackageUserState(
        val installed: String?,
        val hidden: String?,
        val suspended: String?,
        val enabled: String?
    )

    private data class LauncherCleanupPlanResult(
        val results: List<ShellResult>,
        val plan: LauncherCleanupPlan?,
        val skippedReason: String?
    )

    private data class LauncherCleanupPlan(
        val referenceUserId: Int,
        val targetUserId: Int,
        val referenceSystemLaunchers: List<String>,
        val targetSystemLaunchers: List<String>,
        val packagesToCleanup: List<String>
    )
}
