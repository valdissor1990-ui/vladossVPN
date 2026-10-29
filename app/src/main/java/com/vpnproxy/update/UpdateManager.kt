package com.vpnproxy.update

import android.content.Context
import com.vpnproxy.BuildConfig
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
    private val downloadMutex = Mutex()
    private var lastCheckAt = 0L
    private val checkCooldownMs = 60_000L
    private val pendingStore = PendingUpdateStore(context)

    init {
        restoreReadyFromDisk()
    }

    /** If a previously downloaded APK is still newer than installed, surface Ready. */
    fun restoreReadyFromDisk() {
        val pending = pendingStore.read() ?: return
        val file = pendingStore.readyFile() ?: return
        val synthetic = GitHubRelease(
            tagName = pending.tagName,
            name = pending.versionName,
            body = pending.releaseBody.ifBlank {
                "Обновление ${pending.versionName} уже скачано. Нажмите «Установить»."
            }
        )
        if (pending.versionCode > BuildConfig.VERSION_CODE) {
            _state.value = State.Ready(file, synthetic)
            logCollector.info(
                "UpdateManager",
                "Restored pending APK ${pending.versionName} (code=${pending.versionCode})"
            )
        }
    }

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
            // Keep Ready if we already have a download at least as new as remote later.
            val previousReady = _state.value as? State.Ready
            _state.value = State.Checking
            val result = checker.check()
            lastCheckAt = System.currentTimeMillis()
            _state.value = when {
                result.error != null -> State.Error(result.error)
                result.hasUpdate && result.release != null -> {
                    val remoteCode = result.release.extractVersionCode()
                    val localPending = pendingStore.read()
                    val pendingFile = pendingStore.readyFile()
                    when {
                        pendingFile != null && localPending != null &&
                            localPending.versionCode >= remoteCode -> {
                            logCollector.info(
                                "UpdateManager",
                                "Pending APK already covers remote code=$remoteCode"
                            )
                            State.Ready(
                                pendingFile,
                                previousReady?.release?.takeIf {
                                    previousReady.file.absolutePath == pendingFile.absolutePath
                                } ?: result.release
                            )
                        }
                        else -> State.UpdateAvailable(result.release)
                    }
                }
                else -> {
                    // Installed is current — drop stale pending
                    pendingStore.clear()
                    pendingStore.deleteOtherApks(keep = null)
                    State.UpToDate
                }
            }
        }
    }

    /**
     * Background path: check → download newest APK → Ready.
     * If a newer release appears before the user installs, the new APK replaces the old one.
     */
    suspend fun checkAndPrefetch() {
        checkForUpdate(force = true)
        val available = _state.value as? State.UpdateAvailable ?: return
        downloadUpdate(available.release)
    }

    suspend fun downloadUpdate(release: GitHubRelease) = downloadMutex.withLock {
        val flavor = if (context.packageName.endsWith(".play")) "play" else "direct"
        val asset = release.findApkAsset(flavor)
        if (asset == null) {
            _state.value = State.Error("APK asset not found in release")
            return@withLock
        }

        val remoteCode = release.extractVersionCode()
        val pending = pendingStore.read()
        val pendingFile = pendingStore.readyFile()
        if (pendingFile != null && pending != null && pending.versionCode >= remoteCode) {
            logCollector.info(
                "UpdateManager",
                "Skip download — pending ${pending.versionName} >= remote $remoteCode"
            )
            _state.value = State.Ready(pendingFile, release)
            return@withLock
        }

        _state.value = State.Downloading(0, release)
        val file = downloader.download(asset.downloadUrl, asset.name, asset.size) { percent ->
            _state.value = State.Downloading(percent.coerceIn(0, 100), release)
        }
        if (file != null) {
            pendingStore.save(release, file)
            logCollector.info(
                "UpdateManager",
                "Prefetched APK ${release.extractVersionName()} → ${file.name}"
            )
            _state.value = State.Ready(file, release)
        } else {
            _state.value = State.Error("Не удалось скачать или проверить APK. Повторите загрузку.")
        }
    }

    fun installReady() {
        val ready = _state.value as? State.Ready ?: return
        if (!installer.canInstallPackages()) {
            logCollector.warn("UpdateManager", "Install blocked: unknown-sources permission not granted")
            installer.openInstallPermissionSettings()
            return
        }
        if (!installer.install(ready.file)) {
            _state.value = State.Error(
                "Не удалось открыть установщик APK. Разрешите установку из этого источника в настройках Android и повторите."
            )
        }
    }

    fun reset() {
        // Keep file on disk for later install; only clear UI state.
        restoreReadyFromDisk()
        if (_state.value !is State.Ready) {
            _state.value = State.Idle
        }
    }

    fun markUpToDate() { _state.value = State.UpToDate }
}
