package app.lokey0905.multiisland

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import app.lokey0905.multiisland.shell.RootShellExecutor
import app.lokey0905.multiisland.shell.ShizukuServiceStatus
import app.lokey0905.multiisland.shell.ShizukuShellExecutor
import app.lokey0905.multiisland.ui.MainScreen
import app.lokey0905.multiisland.ui.MainViewModel
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    }

    private val shizukuExecutor by lazy { ShizukuShellExecutor(applicationContext) }
    private val rootExecutor by lazy { RootShellExecutor() }

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory(
            shizukuShellExecutor = shizukuExecutor,
            rootShellExecutor = rootExecutor
        )
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        viewModel.refreshStatuses()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        viewModel.notify("Shizuku service disconnected")
        viewModel.refreshStatuses()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                viewModel.notify("Shizuku permission granted")
            } else {
                viewModel.notify("Shizuku permission denied")
            }
            viewModel.refreshStatuses()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 先註冊 listener，避免 requestPermission 後收不到回調。
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }

        setContent {
            MaterialTheme {
                Surface {
                    MainScreen(
                        viewModel = viewModel,
                        onRequestShizukuPermission = {
                            requestShizukuPermission()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStatuses()
    }

    override fun onDestroy() {
        runCatching {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        }
        super.onDestroy()
    }

    private fun requestShizukuPermission() {
        when (shizukuExecutor.getServiceStatus()) {
            ShizukuServiceStatus.NotInstalledOrUnavailable -> {
                viewModel.notify("Shizuku/Sui not installed or unavailable")
                return
            }
            ShizukuServiceStatus.InstalledButNotRunning -> {
                viewModel.notify("Shizuku/Sui installed but service is not running")
                return
            }
            ShizukuServiceStatus.Ready -> {
                viewModel.notify("Shizuku permission already granted")
                viewModel.refreshStatuses()
                return
            }
            ShizukuServiceStatus.RunningButUnauthorized -> Unit
        }

        runCatching {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                viewModel.notify("Shizuku permission denied previously; please re-grant in Shizuku/Sui")
                return
            }
            shizukuExecutor.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            viewModel.notify("Please allow the Shizuku/Sui permission dialog")
        }.onFailure {
            viewModel.notify("Shizuku permission request failed: ${it.message}")
        }
    }
}
