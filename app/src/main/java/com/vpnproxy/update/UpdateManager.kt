package com.vpnproxy.update

import android.content.Context
import com.vpnproxy.logging.LogCollector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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
    private val checkMutex = Mutex()
    private var lastCheckAt = 0L
    private val checkCooldownMs = 60_000L

    suspend fun checkForUpdate(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - lastCheckAt < checkCooldownMs) {
            logCollector.debug("UpdateManager", "Skip check (cooldown)")
            return
        }
        checkMutex.withLock {
            if (!force && System.currentTimeMillis() - lastCheckAt < checkCooldownMs) {
                logCollector.debug("UpdateManager", "Skip check (already done)")
                return@withLock
            }
            _state.value = State.Checking
            val result = checker.check()
            lastCheckAt = System.currentTimeMillis()
            _state.value = when {
                result.error != null -> State.Error(result.error)
                result.hasUpdate && result.release != null -> State.UpdateAvailable(result.release)
                else -> State.UpToDate
            }
        }
    }

    suspend fun downloadUpdate(release: GitHubRelease) {
        val flavor = if (context.packageName.endsWith(".play")) "play" else "direct"
        val asset = release.findApkAsset(flavor)
        if (asset == null) {
            _state.value = State.Error("APK asset not found in release")
            return
        }
        _state.value = State.Downloading(0, release)
        val file = downloader.download(asset.downloadUrl, asset.name, asset.size) { percent ->
            _state.value = State.Downloading(percent.coerceIn(0, 100), release)
        }
        _state.value = if (file != null) State.Ready(file, release)
        else State.Error("Не удалось скачать или проверить APK. Повторите загрузку.")
    }

    fun installReady() {
        val ready = _state.value as? State.Ready ?: return
        if (!installer.canInstallPackages()) {
            installer.openInstallPermissionSettings()
            return
        }
        if (!installer.install(ready.file)) {
            _state.value = State.Error("Не удалось запустить установщик. APK сохранён в кэше; проверьте разрешение на установку и версию приложения.")
        }
    }

    fun reset() { _state.value = State.Idle }
    fun markUpToDate() { _state.value = State.UpToDate }
}
