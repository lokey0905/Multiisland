package app.lokey0905.multiisland.shell

import app.lokey0905.multiisland.data.model.ShellResult

interface ShellExecutor {
    suspend fun exec(command: String): ShellResult
    suspend fun isAvailable(): Boolean
}

enum class ShellMode {
    SHIZUKU,
    ROOT
}

