package app.lokey0905.multiisland.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lokey0905.multiisland.shell.ShellMode
import app.lokey0905.multiisland.shell.ShizukuServiceStatus

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestShizukuPermission: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    if (state.showFirstHint) {
        AlertDialog(
            onDismissRequest = viewModel::dismissFirstHint,
            title = { Text("首次使用提醒") },
            text = {
                Text(
                    "Shizuku 模式需要先安裝並啟動 Shizuku，Root 模式需要 su 可用。\n" +
                        "部分 ROM 可能禁用多使用者或 work profile，相關指令可能失敗。"
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissFirstHint) {
                    Text("了解")
                }
            }
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ModeSection(
                    state = state,
                    onModeChange = viewModel::switchMode,
                    onRefresh = {
                        viewModel.refreshStatuses()
                        viewModel.refreshDeviceCapabilities()
                    },
                    onRequestShizukuPermission = onRequestShizukuPermission
                )
            }
            item {
                DeviceCapabilitiesSection(state = state, onRefresh = viewModel::refreshDeviceCapabilities)
            }
            item {
                CreateProfileSection(
                    state = state,
                    onProfileNameChange = viewModel::updateProfileName,
                    onPackageNameChange = viewModel::updatePackageName,
                    onComponentNameChange = viewModel::updateComponentName,
                    onCreate = viewModel::createManagedProfile
                )
            }
            item {
                UsersSection(
                    state = state,
                    onListUsers = viewModel::listUsers,
                    onStartUser = viewModel::startUser,
                    onStopUser = viewModel::stopUser,
                    onRemoveUser = viewModel::removeUser,
                    onSelectUser = viewModel::updateSelectedUserId
                )
            }
            item {
                InstallExistingSection(
                    state = state,
                    onPackageNameChange = viewModel::updateInstallPackageName,
                    onInstall = viewModel::installExistingForSelectedUser,
                    onKeyboardQuickFix = viewModel::applyKeyboardQuickFixForSelectedUser
                )
            }
            item {
                UninstallSection(
                    state = state,
                    onPackageNameChange = viewModel::updateUninstallPackageName,
                    onUninstall = viewModel::uninstallForSelectedUser,
                    onRemoveSystemUpdater = viewModel::removeSystemUpdaterForSelectedUser
                )
            }
            item {
                SetProfileOwnerSection(
                    state = state,
                    onComponentNameChange = viewModel::updateComponentName,
                    onSetProfileOwner = viewModel::setProfileOwnerForSelectedUser
                )
            }
            item {
                TestCommandSection(
                    state = state,
                    onCommandChange = viewModel::updateCommand,
                    onExecute = viewModel::runTestCommand
                )
            }
            item {
                LogSection(
                    state = state,
                    onClear = viewModel::clearLogs,
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(viewModel.exportLogs()))
                        viewModel.notify("已複製全部 log")
                    }
                )
            }
        }
    }
}

@Composable
private fun ModeSection(
    state: MainUiState,
    onModeChange: (ShellMode) -> Unit,
    onRefresh: () -> Unit,
    onRequestShizukuPermission: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("模式切換", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = state.mode == ShellMode.SHIZUKU,
                    onClick = { onModeChange(ShellMode.SHIZUKU) }
                )
                Text("Shizuku")
                RadioButton(
                    selected = state.mode == ShellMode.ROOT,
                    onClick = { onModeChange(ShellMode.ROOT) }
                )
                Text("Root")
            }
            val shizukuStatusText = when (state.shizukuStatus) {
                ShizukuServiceStatus.NotInstalledOrUnavailable -> "NotInstalledOrUnavailable"
                ShizukuServiceStatus.InstalledButNotRunning -> "InstalledButNotRunning"
                ShizukuServiceStatus.RunningButUnauthorized -> "RunningButUnauthorized"
                ShizukuServiceStatus.Ready -> "Ready"
            }
            Text("Shizuku/Sui Status: $shizukuStatusText")
            Text("Root Available: ${if (state.rootAvailable) "Yes" else "No"}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRefresh) { Text("Refresh") }
                Button(
                    onClick = onRequestShizukuPermission,
                    enabled = state.shizukuStatus == ShizukuServiceStatus.RunningButUnauthorized
                ) { Text("Shizuku 授權") }
            }
        }
    }
}

@Composable
private fun DeviceCapabilitiesSection(state: MainUiState, onRefresh: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("裝置能力資訊卡", style = MaterialTheme.typography.titleMedium)
            Text("Max Users: ${state.capabilities.maxUsers?.toString() ?: "Unknown"}")
            Text("Max Running Users: ${state.capabilities.maxRunningUsers?.toString() ?: "Unsupported"}")
            Text("Current Users: ${state.capabilities.currentUserCount}")
            Text("Fetched By Mode: ${state.capabilitiesMode}")
            Button(onClick = onRefresh) { Text("Refresh") }
        }
    }
}

@Composable
private fun CreateProfileSection(
    state: MainUiState,
    onProfileNameChange: (String) -> Unit,
    onPackageNameChange: (String) -> Unit,
    onComponentNameChange: (String) -> Unit,
    onCreate: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("建立 Work Profile / Managed Profile", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.profileNameInput,
                onValueChange = onProfileNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Profile 名稱") }
            )
            OutlinedTextField(
                value = state.packageNameInput,
                onValueChange = onPackageNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("套件名稱") }
            )
            OutlinedTextField(
                value = state.componentNameInput,
                onValueChange = onComponentNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Profile Owner Component") }
            )
            Button(onClick = onCreate, enabled = !state.busy) { Text("開始建立") }
        }
    }
}

@Composable
private fun UsersSection(
    state: MainUiState,
    onListUsers: () -> Unit,
    onStartUser: (Int) -> Unit,
    onStopUser: (Int) -> Unit,
    onRemoveUser: (Int) -> Unit,
    onSelectUser: (Int) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Users 清單", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onListUsers) { Text("重新載入 users") }
            state.users.forEach { user ->
                Divider()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val userStatus = when (user.isRunning) {
                        true -> "已啟動"
                        false -> "未啟動"
                        null -> "未知"
                    }
                    Text("ID: ${user.id} | ${user.name}")
                    Text("狀態: $userStatus")
                    Text("flags: ${user.flagsRaw}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onSelectUser(user.id) }) { Text("選取") }
                        Button(onClick = { onStartUser(user.id) }) { Text("啟動") }
                        Button(onClick = { onStopUser(user.id) }) { Text("停止") }
                        if (user.id != 0) {
                            Button(onClick = { onRemoveUser(user.id) }) { Text("刪除") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstallExistingSection(
    state: MainUiState,
    onPackageNameChange: (String) -> Unit,
    onInstall: () -> Unit,
    onKeyboardQuickFix: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("安裝 App 到指定 user", style = MaterialTheme.typography.titleMedium)
            Text("Selected userId: ${state.selectedUserId}")
            OutlinedTextField(
                value = state.installPackageNameInput,
                onValueChange = onPackageNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Package name") }
            )
            Button(onClick = onInstall, enabled = !state.busy) { Text("pm install-existing") }
            Button(onClick = onKeyboardQuickFix, enabled = !state.busy) {
                Text("一鍵修復鍵盤（加 Gboard / 移除 Google TTS）")
            }
        }
    }
}

@Composable
private fun UninstallSection(
    state: MainUiState,
    onPackageNameChange: (String) -> Unit,
    onUninstall: () -> Unit,
    onRemoveSystemUpdater: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("移除指定 user 的 App", style = MaterialTheme.typography.titleMedium)
            Text("Selected userId: ${state.selectedUserId}")
            OutlinedTextField(
                value = state.uninstallPackageNameInput,
                onValueChange = onPackageNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Package name") }
            )
            Button(onClick = onUninstall, enabled = !state.busy) { Text("pm uninstall") }
            Button(onClick = onRemoveSystemUpdater, enabled = !state.busy) {
                Text("一鍵移除系統更新（com.android.updater）")
            }
        }
    }
}

@Composable
private fun SetProfileOwnerSection(
    state: MainUiState,
    onComponentNameChange: (String) -> Unit,
    onSetProfileOwner: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("設定 Profile Owner", style = MaterialTheme.typography.titleMedium)
            Text("Selected userId: ${state.selectedUserId}")
            OutlinedTextField(
                value = state.componentNameInput,
                onValueChange = onComponentNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Component name") }
            )
            Button(onClick = onSetProfileOwner, enabled = !state.busy) { Text("dpm set-profile-owner") }
        }
    }
}

@Composable
private fun TestCommandSection(
    state: MainUiState,
    onCommandChange: (String) -> Unit,
    onExecute: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("測試命令", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.commandInput,
                onValueChange = onCommandChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Shell command") }
            )
            Button(onClick = onExecute, enabled = !state.busy) { Text("執行") }
        }
    }
}

@Composable
private fun LogSection(
    state: MainUiState,
    onClear: () -> Unit,
    onCopy: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Log", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onClear) { Text("清除 log") }
                Button(onClick = onCopy) { Text("複製全部 log") }
            }
            if (state.logs.isEmpty()) {
                Text("尚無 log")
            } else {
                state.logs.take(100).forEach { log ->
                    val time = DateFormat.format("yyyy-MM-dd HH:mm:ss", log.time)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("[$time] ${log.title}")
                            Text("Mode: ${log.mode}")
                            Text("Command: ${log.command}")
                            Text("Result: ${if (log.success) "Success" else "Failed"}")
                            Text("stdout: ${log.stdout.ifBlank { "(empty)" }}")
                            Text("stderr: ${log.stderr.ifBlank { "(empty)" }}")
                        }
                    }
                }
            }
        }
    }
}
