package moe.chensi.volume

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import moe.chensi.volume.system.AudioPlaybackConfigurationProxy
import org.joor.Reflect
import java.util.ArrayList

class RootServiceImpl : RootService() {
    companion object {
        private const val TAG = "AppVolManager.Root"
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    private val binder = object : IRootService.Stub() {
        // Store IPlayer references for direct volume control from root context
        private val playersByPackage = mutableMapOf<String, MutableList<Any>>()

        override fun getInstalledPackages(): List<PackageInfo> {
            return try {
                val MATCH_ANY_USER = 0x00000400
                val GET_ACTIVITIES = 0x00000001
                packageManager.getInstalledPackages(MATCH_ANY_USER or GET_ACTIVITIES)
            } catch (e: Exception) {
                emptyList()
            }
        }

        override fun getForegroundTask(): ComponentName? {
            return try {
                val atm = getSystemService("activity_task") ?: return null
                val tasks = Reflect.on(atm).call("getTasks", 1).get<List<ActivityManager.RunningTaskInfo>>()
                if (!tasks.isNullOrEmpty()) {
                    tasks[0].topActivity
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        override fun getInterruptionFilter(): Int {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
            return nm?.currentInterruptionFilter ?: 1 // INTERRUPTION_FILTER_ALL
        }

        override fun setInterruptionFilter(filter: Int) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
            nm?.setInterruptionFilter(filter)
        }

        override fun getActivePlaybackConfigurations(): List<Bundle> {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager?
            val configs = am?.activePlaybackConfigurations ?: emptyList()
            val result = ArrayList<Bundle>()

            Log.d(TAG, "getActivePlaybackConfigurations: found ${configs.size} configs")

            // Clear old player references before refreshing
            synchronized(playersByPackage) { playersByPackage.clear() }

            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
            val runningProcesses = activityManager?.runningAppProcesses ?: emptyList()

            for (config in configs) {
                try {
                    val proxy = AudioPlaybackConfigurationProxy(config)
                    val pid = proxy.clientPid
                    val process = runningProcesses.find { it.pid == pid }
                    val packageName = process?.pkgList?.firstOrNull()
                        ?: proxy.packageName.ifEmpty { "" }

                    Log.d(TAG, "Config: pkg=$packageName, pid=$pid, type=${proxy.playerTypeName}, state=${proxy.playerStateName}")

                    val bundle = Bundle()
                    bundle.putString("packageName", packageName)
                    bundle.putInt("clientPid", pid)
                    bundle.putInt("playerType", proxy.playerType)
                    bundle.putInt("playerState", proxy.playerState.value)

                    // Get the IPlayer object (IPlayer.Stub.Proxy, NOT IBinder)
                    val iPlayer = Reflect.on(config).call("getIPlayer").get<Any?>()
                    
                    // Store IPlayer reference for root-side volume control
                    if (iPlayer != null) {
                        synchronized(playersByPackage) { playersByPackage.getOrPut(packageName) { mutableListOf() }.add(iPlayer) }
                    }
                    
                    // Extract the actual IBinder using asBinder() - IPlayer.Stub.Proxy
                    // does NOT implement IBinder, so we must call asBinder() to get it
                    val playerBinder: IBinder? = if (iPlayer != null) {
                        try {
                            Reflect.on(iPlayer).call("asBinder").get<IBinder?>()
                        } catch (e: Exception) {
                            Log.e(TAG, "  Failed to get IBinder via asBinder() for $packageName", e)
                            null
                        }
                    } else null
                    
                    Log.d(TAG, "  IPlayer binder for $packageName: ${if (playerBinder != null) "OK (${playerBinder.javaClass.name})" else "NULL!"}")
                    if (playerBinder != null) {
                        bundle.putBinder("player", playerBinder)
                    } else {
                        Log.w(TAG, "  WARNING: No IPlayer binder for $packageName - volume control will NOT work for this player!")
                    }

                    result.add(bundle)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process playback config", e)
                }
            }
            Log.d(TAG, "Returning ${result.size} playback configurations")
            return result
        }

        override fun setAppPlayAudio(packageName: String, allow: Boolean) {
            try {
                val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                val uid = packageManager.getPackageUid(packageName, 0)
                val mode = if (allow) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED
                // OP_PLAY_AUDIO = 28
                Reflect.on(appOps).call("setMode", 28, uid, packageName, mode)
                Log.d(TAG, "setAppPlayAudio: $packageName allow=$allow uid=$uid mode=$mode - SUCCESS")
            } catch (e: Exception) {
                Log.e(TAG, "setAppPlayAudio FAILED for $packageName allow=$allow", e)
            }
        }

        override fun setAppVolume(packageName: String, volume: Float) {
            val players = synchronized(playersByPackage) { playersByPackage[packageName]?.toList() } ?: run {
                Log.w(TAG, "setAppVolume: no players found for $packageName")
                return
            }
            for (player in players) {
                try {
                    Reflect.on(player).call("setVolume", volume)
                    Log.d(TAG, "setAppVolume: $packageName volume=$volume - SUCCESS (via root)")
                } catch (e: Exception) {
                    Log.e(TAG, "setAppVolume: $packageName volume=$volume - FAILED", e)
                }
            }
        }
    }
}
