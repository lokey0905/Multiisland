package app.lokey0905.multiisland.data.model

data class ShellResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val success: Boolean
)

