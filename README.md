# multiisland

Kotlin + Jetpack Compose + Material 3 的 Android 多開管理器 MVP。

## 功能

- 雙模式執行：Shizuku / Root
- 首頁顯示模式狀態：
  - Shizuku 是否安裝
  - Shizuku 是否授權
  - Root 是否可用
- 裝置能力資訊卡：
  - `pm get-max-users`
  - `pm get-max-running-users`（不支援時顯示 Unsupported）
  - `pm list users` 解析目前 users
- 建立 managed profile 流程：
  1. `pm create-user --profileOf 0 --managed <profileName>`
  2. 解析 userId
  3. `am start-user <userId>`
  4. `pm install-existing --user <userId> <packageName>`
  5. `dpm set-profile-owner --user <userId> <componentName>`
- users 管理：啟動 / 停止 / 刪除（user 0 不可刪）
- 安裝 app 到指定 user
- 設定 profile owner
- 任意 shell 測試命令
- 操作 log 顯示、清除、複製

## 架構

- UI: Jetpack Compose
- Pattern: MVVM
- 背景工作: Kotlin Coroutine
- Shell 抽象: `ShellExecutor`
  - `ShizukuShellExecutor`
  - `RootShellExecutor`
- Repository: `ProfileProvisionRepository`

## 注意事項

- 本 App 核心能力依賴 Shell 身分（Shizuku 或 Root），非一般權限 App 即可完成。
- 不同 ROM 可能封鎖多使用者或 work profile，命令可能失敗。
- `create-user`、`set-profile-owner`、`start-user` 在某些裝置上可能受系統限制。

## 需求環境

- Android Studio（建議最新穩定版）
- Android 9 (API 28) 以上
- 若使用 Shizuku 模式：需先安裝並啟動 Shizuku
- 若使用 Root 模式：需可用 `su`

## 執行建議流程

1. 開啟 App，確認模式狀態。
2. 在「裝置能力資訊卡」按 `Refresh`。
3. 選擇 Shizuku 或 Root 模式。
4. 若使用 Shizuku，先按 `Shizuku 授權`。
5. 進行建立 profile / users 管理 / 安裝 / owner 設定。
6. 從 Log 區塊查看每步 stdout/stderr。

