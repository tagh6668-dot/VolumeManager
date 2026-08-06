package moe.chensi.volume

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService
import moe.chensi.volume.system.AudioPlaybackConfigurationProxy
import org.joor.Reflect
import java.util.ArrayList

class RootServiceImpl : RootService() {

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    private val binder = object : IRootService.Stub() {
        override fun getInstalledPackages(): List<PackageInfo> {
            val MATCH_ANY_USER = 0x00000400
            val GET_ACTIVITIES = 0x00000001
            return packageManager.getInstalledPackages(MATCH_ANY_USER or GET_ACTIVITIES)
        }

        override fun getForegroundTask(): ComponentName? {
            return try {
                val atm = getSystemService("activity_task")
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
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            return nm.currentInterruptionFilter
        }

        override fun setInterruptionFilter(filter: Int) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.setInterruptionFilter(filter)
        }

        override fun getActivePlaybackConfigurations(): List<Bundle> {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val configs = am.activePlaybackConfigurations
            val result = ArrayList<Bundle>()

            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningProcesses = activityManager.runningAppProcesses ?: emptyList()

            for (config in configs) {
                try {
                    val proxy = AudioPlaybackConfigurationProxy(config)
                    val pid = proxy.clientPid
                    val process = runningProcesses.find { it.pid == pid }
                    val packageName = process?.pkgList?.firstOrNull() ?: ""

                    val bundle = Bundle()
                    bundle.putString("packageName", packageName)
                    bundle.putInt("clientPid", pid)
                    bundle.putInt("playerType", proxy.playerType)
                    bundle.putInt("playerState", proxy.playerState.value)

                    val playerBinder = Reflect.on(config).call("getIPlayer").get<IBinder?>()
                    if (playerBinder != null) {
                        bundle.putBinder("player", playerBinder)
                    }

                    result.add(bundle)
                } catch (e: Exception) {
                    // Ignore and skip bad configurations
                }
            }
            return result
        }
    }
}
