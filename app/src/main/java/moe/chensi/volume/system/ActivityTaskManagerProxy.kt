package moe.chensi.volume.system

import android.content.ComponentName
import android.content.Context
import moe.chensi.volume.MyApplication

class ActivityTaskManagerProxy(private val context: Context) {

    data class Task(val app: String, val activityName: ComponentName)

    fun getForegroundTask(): Task? {
        val appManager = (context.applicationContext as? MyApplication)?.manager
        val topActivity = appManager?.rootService?.foregroundTask ?: return null
        return Task(topActivity.packageName, topActivity)
    }
}
