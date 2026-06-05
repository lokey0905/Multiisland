# Multiisland

> English version: [README.en.md](README.en.md)

Kotlin + Jetpack Compose + Material 3 的 Android managed profile / work profile 輔助工具。

Multiisland 透過 Shizuku 或 Root shell 建立 Island managed profile，並在建立後補齊 provisioning、Google services、Gboard 與 launcher app cleanup 流程。

## 建議搭配

建議與下列專案搭配使用，以改善或放寬部分 ROM 的多使用者 / user 建立限制：

- [lokey0905/multiuserenabler](https://github.com/lokey0905/multiuserenabler)
- [icepony/AlwaysCreateUser](https://github.com/icepony/AlwaysCreateUser)

## 功能

- 雙模式執行：Shizuku / Root
- 首頁顯示模式狀態：
  - Shizuku/Sui 是否可用
  - Shizuku 是否授權
  - Root 是否可用
- 裝置能力資訊卡：
  - `pm get-max-users`
  - `pm get-max-running-users`
  - `pm list users`
- 建立 Island managed profile：
  1. `pm create-user --profileOf 0 --managed "<profileName>"`
  2. 解析實際 userId，不寫死 user id
  3. `am start-user <userId>`
  4. `pm install-existing --user <userId> com.oasisfeng.island`
  5. `dpm set-profile-owner --user <userId> com.oasisfeng.island/.IslandDeviceAdminReceiver`
- 建立後 post-provision 修復：
  - 設定 `user_setup_complete` / `device_provisioned`
  - 安裝與啟用 Play Store / GMS / GSF / Gboard
  - `unhide` / `unsuspend` critical packages
  - 設定 Gboard 為預設輸入法
  - 觸發 Island re-provision service
  - 開啟 profile 內 Island 設定頁
  - 驗證 GMS 狀態
- 以 Island 正規建立的 `user 10` 作為參考模板：
  - `Preview cleanup like user 10`
  - `Cleanup like user 10`
  - 清理 target profile 多出的 system launcher apps，例如 Google Meet
  - 不使用固定黑名單
  - 不使用 `cmd package suspend`
- 保護重要 packages 不被 cleanup：
  - Island
  - Play Store
  - GMS
  - GSF
  - Gboard
  - DocumentsUI / WebView / Settings / package installer / MIUI security 相關套件
- users 管理：
  - 啟動 user
  - 停止 user
  - 刪除 user
- 安裝 app 到指定 user
- 移除指定 user 的 app
- 設定 profile owner
- 設定 Gboard 預設輸入法
- 任意 shell 測試命令
- 操作 log 顯示、清除、複製
- Log 保留：
  - command
  - stdout
  - stderr
  - exitCode
  - success

## 架構

- UI: Jetpack Compose + Material 3
- Pattern: MVVM
- 背景工作: Kotlin Coroutine
- Shell 抽象: `ShellExecutor`
  - `ShizukuShellExecutor`
  - `RootShellExecutor`
- Repository: `ProfileProvisionRepository`

所有 provisioning / cleanup 指令都透過 `ShellExecutor` 執行，Root 與 Shizuku 共用同一套流程。

## 注意事項

- 本 App 核心能力依賴 Shell 身分，需 Shizuku/Sui 或 Root。
- 不同 ROM 可能封鎖多使用者、managed profile 或 profile owner 設定。
- `pm create-user`、`dpm set-profile-owner`、`am start-user` 在某些裝置上可能失敗。
- cleanup 以 `user 10` 作為參考模板；若 user 10 狀態不乾淨，cleanup 結果也會受影響。
- 若 GMS 仍顯示 `suspended=true`，可能需要修改 Island APK 或在 patch 後重建 profile。
- 刪除 user/profile 會移除該 profile 內資料。Multiisland 目前不提供一鍵刪除並重建測試功能。

## 需求環境

- Android 9 (API 28) 以上
- Android Studio
- Shizuku/Sui 或 Root
- owner user 已安裝 Island

## 執行建議流程

1. 開啟 App，選擇 Shizuku 或 Root 模式。
2. 若使用 Shizuku，先按 `Shizuku 授權`。
3. 按 `Refresh` 檢查裝置能力與 users。
4. 輸入 profile name、Island package、profile owner component。
5. 按 `開始建立`。
6. 從 Log 區塊查看每步 stdout/stderr/exitCode。
7. 如需清理多出的 launcher apps，先選取 target user。
8. 先按 `Preview cleanup like user 10`，確認清單後再按 `Cleanup like user 10`。
