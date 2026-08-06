package moe.chensi.volume.system

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import moe.chensi.volume.MyApplication
import java.util.WeakHashMap

class PackageManagerProxy private constructor(private val context: Context) {
    companion object {
        private val cache = WeakHashMap<Context, PackageManagerProxy>()

        fun get(context: Context): PackageManagerProxy {
            return cache.getOrPut(context) { PackageManagerProxy(context) }
        }
    }

    private val packageManager = context.packageManager

    val defaultActivityIcon by lazy { packageManager.defaultActivityIcon }

    val defaultActivityIconImageBitmap by lazy {
        defaultActivityIcon.toBitmap(128, 128).asImageBitmap()
    }

    fun getInstalledPackagesForAllUsers(): List<PackageInfo> {
        return try {
            packageManager.getInstalledPackages(PackageManager.GET_ACTIVITIES)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getDrawable(packageName: String, resId: Int, appInfo: ApplicationInfo): Drawable? {
        return try {
            packageManager.getDrawable(packageName, resId, appInfo)
        } catch (e: Exception) {
            null
        }
    }

    fun loadLabel(appInfo: ApplicationInfo): String {
        return try {
            appInfo.loadLabel(packageManager).toString()
        } catch (e: Exception) {
            appInfo.packageName
        }
    }
}
