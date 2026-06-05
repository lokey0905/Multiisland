# Multiisland

> 中文版: [README.md](README.md)

An Android managed profile / work profile helper built with Kotlin, Jetpack Compose, and Material 3.

Multiisland uses Shizuku or Root shell access to create Island managed profiles and then runs post-provision repair for provisioning flags, Google services, Gboard, and launcher app cleanup.

## Recommended Companion Projects

Recommended for devices or ROMs that restrict multi-user support or user creation:

- [lokey0905/multiuserenabler](https://github.com/lokey0905/multiuserenabler)
- [icepony/AlwaysCreateUser](https://github.com/icepony/AlwaysCreateUser)

## Features

- Dual execution modes: Shizuku / Root
- Mode status card:
  - Shizuku/Sui availability
  - Shizuku permission status
  - Root availability
- Device capability card:
  - `pm get-max-users`
  - `pm get-max-running-users`
  - `pm list users`
- Create Island managed profile:
  1. `pm create-user --profileOf 0 --managed "<profileName>"`
  2. Parse the real userId; user ids are not hardcoded
  3. `am start-user <userId>`
  4. `pm install-existing --user <userId> com.oasisfeng.island`
  5. `dpm set-profile-owner --user <userId> com.oasisfeng.island/.IslandDeviceAdminReceiver`
- Post-provision repair after creation:
  - Set `user_setup_complete` / `device_provisioned`
  - Install and enable Play Store / GMS / GSF / Gboard
  - `unhide` / `unsuspend` critical packages
  - Set Gboard as the default input method
  - Trigger Island re-provision service
  - Open Island settings inside the profile
  - Verify GMS state
- Use official Island-created `user 10` as the cleanup reference:
  - `Preview cleanup like user 10`
  - `Cleanup like user 10`
  - Remove extra system launcher apps from the target profile, such as Google Meet
  - No fixed blacklist
  - No `cmd package suspend`
- Protected packages are never cleaned:
  - Island
  - Play Store
  - GMS
  - GSF
  - Gboard
  - DocumentsUI / WebView / Settings / package installer / MIUI security related packages
- User management:
  - Start user
  - Stop user
  - Remove user
- Install existing app for selected user
- Remove app for selected user
- Set profile owner
- Set Gboard as default input method
- Run arbitrary shell test commands
- Operation logs can be viewed, cleared, and copied
- Logs include:
  - command
  - stdout
  - stderr
  - exitCode
  - success

## Architecture

- UI: Jetpack Compose + Material 3
- Pattern: MVVM
- Background work: Kotlin Coroutine
- Shell abstraction: `ShellExecutor`
  - `ShizukuShellExecutor`
  - `RootShellExecutor`
- Repository: `ProfileProvisionRepository`

All provisioning and cleanup commands run through `ShellExecutor`, so Root and Shizuku modes share the same flow.

## Notes

- This app requires shell-level access through Shizuku/Sui or Root.
- Different ROMs may block multi-user, managed profile, or profile owner operations.
- `pm create-user`, `dpm set-profile-owner`, and `am start-user` may fail on some devices.
- Cleanup uses `user 10` as the reference template; if user 10 is not clean, cleanup results are affected.
- If GMS still shows `suspended=true`, you may need a modified Island APK or rebuild the profile after patching Island.
- Removing a user/profile deletes data inside that profile. Multiisland currently does not provide a one-click delete-and-rebuild test function.

## Requirements

- Android 9 (API 28) or later
- Android Studio
- Shizuku/Sui or Root
- Island installed in the owner user

## Suggested Flow

1. Open the app and select Shizuku or Root mode.
2. If using Shizuku, grant Shizuku permission first.
3. Press `Refresh` to check device capabilities and users.
4. Enter profile name, Island package, and profile owner component.
5. Press `開始建立`.
6. Check stdout/stderr/exitCode in the Log section.
7. To remove extra launcher apps, select the target user.
8. Press `Preview cleanup like user 10` first, then run `Cleanup like user 10` after verifying the list.
