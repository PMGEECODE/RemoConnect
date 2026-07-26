package com.famage.remoconnect.data.updater

import java.io.File

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val releaseNotes: String = "",
    val downloadUrl: String = "",
    val isMandatory: Boolean = false,
    val publishedAt: String = ""
)

sealed interface UpdateState {
    object Idle : UpdateState
    object Checking : UpdateState
    data class UpToDate(val currentVersionName: String, val currentVersionCode: Int) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Int) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo, val apkFile: File) : UpdateState
    data class Error(val message: String) : UpdateState
}
