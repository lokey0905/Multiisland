package app.lokey0905.multiisland.data.model

import app.lokey0905.multiisland.shell.ShellMode

data class OperationLog(
    val time: Long,
    val title: String,
    val mode: ShellMode,
    val command: String,
    val success: Boolean,
    val stdout: String,
    val stderr: String
)
