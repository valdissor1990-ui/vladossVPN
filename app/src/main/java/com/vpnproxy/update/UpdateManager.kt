package com.vpnproxy.update

import android.content.Context
import com.vpnproxy.logging.LogCollector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Центральный фасад обновления.
 *
 * State machine:
 *   Idle → Checking → UpdateAvailable → Downloading(0..100) → Ready(file) → Installing
 *           ↓                ↓
 *       UpToDate        Error(msg)
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val checker: UpdateChecker,
    private val downloader: UpdateDownloader,
    private val installer: UpdateInstaller,
    private val logCollector: LogCollector
) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class UpdateAvailable(val release: GitHubRelease) : State
        data class Downloading(val percent: Int, val release: GitHubRelease) : State
        data class Ready(val file: File, val release: GitHubRelease) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Проверка обновлений. */
    suspend fun checkForUpdate() {
        _state.value = State.Checking
        val result = checker.check()
        _state.value = when {
            result.error != null -> State.Error(result.error)
            result.hasUpdate && result.release != null -> State.UpdateAvailable(result.release)
            else -> State.UpToDate
        }
    }

    /** Скачивание APK. */
    suspend fun downloadUpdate(release: GitHubRelease) {
        val flavor = if (context.packageName.endsWith(".play")) "play" else "direct"
        val asset = release.findApkAsset(flavor)
        if (asset == null) {
            _state.value = State.Error("APK asset not found in release")
            return
        }

        _state.value = State.Downloading(0, release)
        val file = downloader.download(asset.downloadUrl, asset.name) { percent ->
            _state.value = State.Downloading(percent, release)
        }

        if (file != null) {
            _state.value = State.Ready(file, release)
        } else {
            _state.value = State.Error("Download failed")
        }
    }

    /** Установка готового APK. */
    fun installReady() {
        val ready = _state.value as? State.Ready ?: return
        if (!installer.canInstallPackages()) {
            installer.openInstallPermissionSettings()
            return
        }
        installer.install(ready.file)
    }

    /** Отмена / сброс. */
    fun reset() { _state.value = State.Idle }
    fun markUpToDate() { _state.value = State.UpToDate }
}
