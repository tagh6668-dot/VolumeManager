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
        return try {
            appManager?.rootService?.interruptionFilter ?: 1
        } catch (e: Exception) {
            if (e is android.os.DeadObjectException || e is android.os.RemoteException) {
                appManager?.handleServiceDeath()
            }
            1
        }
    }

    fun setInterruptionFilter(filter: Int) {
        val appManager = (context.applicationContext as? MyApplication)?.manager
        try {
            appManager?.rootService?.setInterruptionFilter(filter)
        } catch (e: Exception) {
            if (e is android.os.DeadObjectException || e is android.os.RemoteException) {
                appManager?.handleServiceDeath()
            }
        }
    }
}
