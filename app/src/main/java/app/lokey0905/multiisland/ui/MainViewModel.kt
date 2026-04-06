package app.lokey0905.multiisland.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.lokey0905.multiisland.data.model.DeviceCapabilities
import app.lokey0905.multiisland.data.model.OperationLog
import app.lokey0905.multiisland.data.model.ShellResult
import app.lokey0905.multiisland.data.model.UserInfo
import app.lokey0905.multiisland.data.repository.ProfileProvisionRepository
import app.lokey0905.multiisland.shell.RootShellExecutor
import app.lokey0905.multiisland.shell.ShizukuShellExecutor
import app.lokey0905.multiisland.shell.ShizukuServiceStatus
import app.lokey0905.multiisland.shell.ShellMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val mode: ShellMode = ShellMode.SHIZUKU,
    val shizukuStatus: ShizukuServiceStatus = ShizukuServiceStatus.NotInstalledOrUnavailable,
    val rootAvailable: Boolean = false,
    val capabilities: DeviceCapabilities = DeviceCapabilities(null, null, 0, emptyList()),
    val capabilitiesMode: ShellMode = ShellMode.SHIZUKU,
    val users: List<UserInfo> = emptyList(),
    val profileNameInput: String = "Island",
    val packageNameInput: String = "com.oasisfeng.island",
    val componentNameInput: String = "com.oasisfeng.island/.IslandDeviceAdminReceiver",
    val commandInput: String = "pm list users",
    val selectedUserId: Int = 0,
    val logs: List<OperationLog> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val showFirstHint: Boolean = true
)

class MainViewModel(
    private val shizukuShellExecutor: ShizukuShellExecutor,
    private val rootShellExecutor: RootShellExecutor
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        refreshStatuses()
        refreshDeviceCapabilities()
    }

    fun dismissFirstHint() {
        _uiState.update { it.copy(showFirstHint = false) }
    }

    fun switchMode(mode: ShellMode) {
        _uiState.update { it.copy(mode = mode) }
    }

    fun updateProfileName(value: String) {
        _uiState.update { it.copy(profileNameInput = value) }
    }

    fun updatePackageName(value: String) {
        _uiState.update { it.copy(packageNameInput = value) }
    }

    fun updateComponentName(value: String) {
        _uiState.update { it.copy(componentNameInput = value) }
    }

    fun updateCommand(value: String) {
        _uiState.update { it.copy(commandInput = value) }
    }

    fun updateSelectedUserId(value: Int) {
        _uiState.update { it.copy(selectedUserId = value) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun notify(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    fun exportLogs(): String {
        return uiState.value.logs.joinToString(separator = "\n\n") { log ->
            """[${log.time}] ${log.title}
Mode: ${log.mode}
Command: ${log.command}
Success: ${log.success}
stdout:
${log.stdout.ifBlank { "(empty)" }}
stderr:
${log.stderr.ifBlank { "(empty)" }}""".trimIndent()
        }
    }

    fun refreshStatuses() {
        viewModelScope.launch {
            val shizukuStatus = shizukuShellExecutor.getServiceStatus()
            val rootAvailable = rootShellExecutor.isAvailable()
            _uiState.update {
                it.copy(
                    shizukuStatus = shizukuStatus,
                    rootAvailable = rootAvailable
                )
            }
        }
    }

    fun refreshDeviceCapabilities() {
        runBusyAction("Refresh capabilities") { repo, modeUsed ->
            val caps = repo.getDeviceCapabilities()
            _uiState.update {
                val resolvedUserId = resolveSelectedUserId(
                    currentSelectedUserId = it.selectedUserId,
                    users = caps.users
                )
                it.copy(
                    capabilities = caps,
                    capabilitiesMode = modeUsed,
                    users = caps.users,
                    selectedUserId = resolvedUserId
                )
            }
            null
        }
    }

    fun createManagedProfile() {
        val state = uiState.value
        runBusyAction("Create managed profile") { repo, modeUsed ->
            repo.createManagedProfile(
                profileName = state.profileNameInput.trim(),
                packageName = state.packageNameInput.trim(),
                componentName = state.componentNameInput.trim()
            ).forEach { addResultLog("Create managed profile", it, modeUsed) }
            refreshDeviceCapabilities()
            null
        }
    }

    fun listUsers() {
        runBusyAction("List users") { repo, modeUsed ->
            val users = repo.listUsers()
            _uiState.update {
                val resolvedUserId = resolveSelectedUserId(
                    currentSelectedUserId = it.selectedUserId,
                    users = users
                )
                it.copy(
                    users = users,
                    selectedUserId = resolvedUserId
                )
            }
            addLog(
                title = "List users",
                mode = modeUsed,
                result = ShellResult("pm list users", 0, users.joinToString("\n"), "", true)
            )
            null
        }
    }

    fun startUser(userId: Int) {
        runSingleCommand("Start user", "am start-user $userId") { repo -> repo.startUser(userId) }
    }

    fun stopUser(userId: Int) {
        runSingleCommand("Stop user", "am stop-user -w $userId") { repo -> repo.stopUser(userId) }
    }

    fun removeUser(userId: Int) {
        runSingleCommand("Remove user", "pm remove-user $userId") { repo -> repo.removeUser(userId) }
    }

    fun installExistingForSelectedUser() {
        val state = uiState.value
        runSingleCommand(
            title = "Install existing for user ${state.selectedUserId}",
            command = "pm install-existing --user ${state.selectedUserId} ${state.packageNameInput.trim()}"
        ) { repo ->
            repo.installExistingForUser(state.selectedUserId, state.packageNameInput.trim())
        }
    }

    fun setProfileOwnerForSelectedUser() {
        val state = uiState.value
        runSingleCommand(
            title = "Set profile owner for user ${state.selectedUserId}",
            command = "dpm set-profile-owner --user ${state.selectedUserId} ${state.componentNameInput.trim()}"
        ) { repo ->
            repo.setProfileOwner(state.selectedUserId, state.componentNameInput.trim())
        }
    }

    fun runTestCommand() {
        val command = uiState.value.commandInput.trim()
        if (command.isBlank()) {
            _uiState.update { it.copy(message = "Command cannot be empty") }
            return
        }
        runSingleCommand("Test command", command) { repo -> repo.testCommand(command) }
    }

    private fun runSingleCommand(
        title: String,
        command: String,
        action: suspend (ProfileProvisionRepository) -> ShellResult
    ) {
        runBusyAction(title) { repo, modeUsed ->
            val result = action(repo)
            addResultLog(title, result, modeUsed)
            refreshDeviceCapabilities()
            command
        }
    }

    private fun runBusyAction(
        title: String,
        action: suspend (ProfileProvisionRepository, ShellMode) -> String?
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = null) }
            val modeUsed = _uiState.value.mode
            val repo = repositoryForMode(modeUsed)
            try {
                val command = action(repo, modeUsed)
                if (command != null) {
                    _uiState.update { it.copy(message = "$title finished") }
                }
                refreshStatuses()
            } catch (throwable: Throwable) {
                addLog(
                    title = title,
                    mode = modeUsed,
                    result = ShellResult(
                        command = title,
                        exitCode = -1,
                        stdout = "",
                        stderr = throwable.message ?: "Unknown error",
                        success = false
                    )
                )
                _uiState.update { it.copy(message = "$title failed: ${throwable.message}") }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    private fun repositoryForMode(mode: ShellMode): ProfileProvisionRepository {
        val executor = if (mode == ShellMode.SHIZUKU) {
            shizukuShellExecutor
        } else {
            rootShellExecutor
        }
        return ProfileProvisionRepository(executor)
    }

    private fun addResultLog(title: String, result: ShellResult, mode: ShellMode) {
        addLog(title = title, mode = mode, result = result)
    }

    // 保留目前選擇；若已不存在再 fallback，避免每次 refresh 都跳回 0。
    private fun resolveSelectedUserId(currentSelectedUserId: Int, users: List<UserInfo>): Int {
        if (users.any { it.id == currentSelectedUserId }) {
            return currentSelectedUserId
        }
        return users.firstOrNull()?.id ?: 0
    }

    private fun addLog(title: String, mode: ShellMode, result: ShellResult) {
        val log = OperationLog(
            time = System.currentTimeMillis(),
            title = title,
            mode = mode,
            command = result.command,
            success = result.success,
            stdout = result.stdout,
            stderr = result.stderr
        )
        _uiState.update { it.copy(logs = listOf(log) + it.logs) }
    }

    class Factory(
        private val shizukuShellExecutor: ShizukuShellExecutor,
        private val rootShellExecutor: RootShellExecutor
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(shizukuShellExecutor, rootShellExecutor) as T
        }
    }
}
