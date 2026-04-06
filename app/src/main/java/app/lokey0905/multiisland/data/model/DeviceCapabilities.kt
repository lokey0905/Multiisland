package app.lokey0905.multiisland.data.model

data class DeviceCapabilities(
    val maxUsers: Int?,
    val maxRunningUsers: Int?,
    val currentUserCount: Int,
    val users: List<UserInfo>
)

