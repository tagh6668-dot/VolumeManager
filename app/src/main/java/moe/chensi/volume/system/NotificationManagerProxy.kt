package moe.chensi.volume.system

import android.content.Context
import moe.chensi.volume.MyApplication
import java.util.WeakHashMap

class NotificationManagerProxy private constructor(private val context: Context) {
    companion object {
        private val cache = WeakHashMap<Context, NotificationManagerProxy>()

        operator fun invoke(context: Context): NotificationManagerProxy {
            return cache.getOrPut(context) { NotificationManagerProxy(context) }
        }
    }

    fun getCurrentInterruptionFilter(): Int {
        val appManager = (context.applicationContext as? MyApplication)?.manager
        return appManager?.rootService?.interruptionFilter ?: 1
    }

    fun setInterruptionFilter(filter: Int) {
        val appManager = (context.applicationContext as? MyApplication)?.manager
        appManager?.rootService?.setInterruptionFilter(filter)
    }
}
