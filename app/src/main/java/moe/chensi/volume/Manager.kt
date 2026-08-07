package moe.chensi.volume

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import moe.chensi.volume.data.App
import moe.chensi.volume.data.AppPreferencesStore
import moe.chensi.volume.system.AudioPlaybackConfigurationProxy
import moe.chensi.volume.system.NotificationManagerProxy
import moe.chensi.volume.system.PackageManagerProxy
import org.joor.Reflect

@SuppressLint("PrivateApi")
class Manager(context: Context, dataStore: DataStore<Preferences>) {

    companion object {
        private const val TAG = "AppVolManager"
    }

    enum class RootStatus {
        Checking, Denied, Connected
    }

    private var _rootStatus by mutableStateOf(RootStatus.Checking)
    val rootStatus
        get() = _rootStatus

    var rootService: IRootService? = null
        private set

    fun handleServiceDeath() {
        rootService = null
        _rootStatus = RootStatus.Denied
    }

    private val rootConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName, service: android.os.IBinder) {
            rootService = IRootService.Stub.asInterface(service)
            _rootStatus = RootStatus.Connected
            start()
        }

        override fun onServiceDisconnected(name: android.content.ComponentName) {
            rootService = null
            _rootStatus = RootStatus.Checking
        }
    }

    fun connectRoot(context: Context) {
        _rootStatus = RootStatus.Checking
        Thread {
            try {
                val cached = com.topjohnwu.superuser.Shell.getCachedShell()
                if (cached != null && !cached.isRoot) {
                    try {
                        cached.close()
                    } catch (ignored: Exception) {}
                }

                val shell = com.topjohnwu.superuser.Shell.getShell()
                if (shell.isRoot) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            val intent = android.content.Intent(context, RootServiceImpl::class.java)
                            com.topjohnwu.superuser.ipc.RootService.bind(intent, rootConnection)
                        } catch (e: Exception) {
                            _rootStatus = RootStatus.Denied
                        }
                    }
                } else {
                    _rootStatus = RootStatus.Denied
                }
            } catch (e: Exception) {
                _rootStatus = RootStatus.Denied
            }
        }.start()
    }

    val audioManager = context.getSystemService(AudioManager::class.java)!!
    val activityManager = context.getSystemService(ActivityManager::class.java)!!
    private val packageManager by lazy { PackageManagerProxy.get(context) }
    val notificationManagerProxy = NotificationManagerProxy(context)

    private val appPreferencesStore = AppPreferencesStore(dataStore)
    private val _systemSliderVisibility = mutableStateMapOf<String, Boolean>()
    val systemSliderVisibility: Map<String, Boolean>
        get() = _systemSliderVisibility

    fun isSystemSliderVisible(id: String): Boolean {
        return _systemSliderVisibility[id] ?: true
    }

    fun setSystemSliderVisible(id: String, visible: Boolean) {
        if ((_systemSliderVisibility[id] ?: true) == visible) {
            return
        }

        _systemSliderVisibility[id] = visible
        appPreferencesStore.setSystemSliderVisible(id, visible)
    }

    val apps = mutableStateMapOf<String, App>()

    private fun reloadApps() {
        for (packageInfo in packageManager.getInstalledPackagesForAllUsers()) {
            val appInfo = packageInfo.applicationInfo ?: continue
            if (!apps.containsKey(packageInfo.packageName)) {
                val app = App(
                    packageManager,
                    packageInfo,
                    packageManager.loadLabel(appInfo),
                    appPreferencesStore.getOrCreate(packageInfo.packageName),
                    appPreferencesStore::save
                )
                app.muteCallback = { pkg, mute ->
                    try {
                        rootService?.setAppPlayAudio(pkg, !mute)
                    } catch (e: Exception) {
                        if (e is android.os.DeadObjectException || e is android.os.RemoteException) {
                            handleServiceDeath()
                        }
                    }
                }
                apps[packageInfo.packageName] = app
            }
        }
    }

    private fun getApp(packageName: String): App? {
        val app = apps[packageName]
        if (app != null) {
            return app
        }

        // Maybe just installed?
        reloadApps()
        return apps[packageName]
    }

    private fun queryActivePlaybackConfigurations(): List<AudioPlaybackConfigurationProxy> {
        return try {
            val bundles = rootService?.activePlaybackConfigurations ?: emptyList()
            Log.d(TAG, "queryActivePlaybackConfigurations: got ${bundles.size} bundles from root service")
            bundles.map { AudioPlaybackConfigurationProxy(it) }
        } catch (e: Exception) {
            Log.e(TAG, "queryActivePlaybackConfigurations FAILED", e)
            if (e is android.os.DeadObjectException || e is android.os.RemoteException) {
                handleServiceDeath()
            }
            emptyList()
        }
    }

    private fun initialize() {
        reloadApps()

        val playbackConfigurations = queryActivePlaybackConfigurations()
        processAudioPlaybackConfigurations(playbackConfigurations)

        audioManager.registerAudioPlaybackCallback(
            object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                    Log.d(TAG, "onPlaybackConfigChanged: ${configs.size} configs from system callback")
                    for (app in apps.values) {
                        app.clearPlayers()
                    }
                    val allConfigs = queryActivePlaybackConfigurations()
                    processAudioPlaybackConfigurations(allConfigs)
                }
            }, null
        )
    }

    fun processAudioPlaybackConfigurations(proxies: List<AudioPlaybackConfigurationProxy>) {
        Log.d(TAG, "processAudioPlaybackConfigurations: processing ${proxies.size} proxies")
        for (proxy in proxies) {
            val packageName = proxy.packageName
            if (packageName.isEmpty()) {
                Log.w(TAG, "  Skipping proxy with empty packageName (pid=${proxy.clientPid})")
                continue
            }
            val app = getApp(packageName)
            if (app == null) {
                Log.w(TAG, "  No App found for $packageName")
                continue
            }
            Log.d(TAG, "  Adding player for $packageName (hasPlayer=${proxy.hasPlayer}, volume=${app.volume})")
            app.addPlayer(proxy)
        }
    }

    init {
        connectRoot(context)
    }

    private fun start() {
        appPreferencesStore.track { first ->
            for ((packageName, index) in appPreferencesStore.indices) {
                if (!first) {
                    // Replace with new reference
                    getApp(packageName)?.setPreferences(appPreferencesStore.values[index])
                }
            }

            _systemSliderVisibility.clear()
            _systemSliderVisibility.putAll(appPreferencesStore.systemSliderVisibility)

            if (first) {
                initialize()
            }
        }
    }
}
