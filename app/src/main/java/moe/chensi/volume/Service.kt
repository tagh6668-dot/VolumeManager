package moe.chensi.volume

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityButtonController.AccessibilityButtonCallback
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.provider.MediaStore
import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import moe.chensi.volume.compose.AppVolumeList
import moe.chensi.volume.compose.SystemVolumePanel
import moe.chensi.volume.compose.VolumeChangeObserver
import moe.chensi.volume.system.ActivityTaskManagerProxy
import moe.chensi.volume.ui.theme.VolumeManagerTheme
import org.joor.Reflect
import java.util.Objects

@SuppressLint("AccessibilityPolicy")
class Service : AccessibilityService() {
    companion object {
        const val ACTION_SHOW_VIEW = "moe.chensi.volume.ACTION_SHOW_VIEW"

        private const val TAG = "VolumeManager.Service"

        private const val ANIMATION_DURATION = 300L

        private const val IDLE_TIMEOUT = 5000L
        private const val AUTO_REPEAT_DELAY = 100L
        private const val AUTO_REPEAT_INITIAL_DELAY = 500L
    }

    private val windowManager: WindowManager by lazy {
        Objects.requireNonNull(
            getSystemService(
                WindowManager::class.java
            )!!
        )
    }
    private lateinit var manager: Manager

    private val handler = object : Handler(Looper.getMainLooper()) {
        fun hideView() {
            if (viewVisible) {
                Log.i(TAG, "animate out")
                animateAlpha(layoutParams.alpha, 0f, ANIMATION_DURATION) {
                    if (!viewVisible) {
                        Log.i(TAG, "remove view")
                        view!!.background = null
                        lifecycle?.currentState = Lifecycle.State.DESTROYED
                        windowManager.removeView(view)
                        view = null
                    }
                }
                viewVisible = false
            }
        }

        private val hideViewRunnable = Runnable(::hideView)

        fun startIdleTimer() {
            removeCallbacks(hideViewRunnable)
            postDelayed(hideViewRunnable, IDLE_TIMEOUT)
        }

        private var repeatAdjustVolumeDirection = 0
        private val repeatAdjustVolumeRunnable: Runnable = Runnable {
            adjustVolume()
            postDelayed(repeatAdjustVolumeRunnable, AUTO_REPEAT_DELAY)
        }

        private fun adjustVolume() {
            manager.audioManager.adjustSuggestedStreamVolume(
                repeatAdjustVolumeDirection, AudioManager.USE_DEFAULT_STREAM_TYPE, 0
            )
            VolumeChangeObserver.notifyVolumeChanged()
            startIdleTimer()
        }

        fun startRepeatAdjustVolume(direction: Int) {
            repeatAdjustVolumeDirection = direction
            adjustVolume()
            postDelayed(repeatAdjustVolumeRunnable, AUTO_REPEAT_INITIAL_DELAY)
        }

        fun stopRepeatAdjustVolume() {
            removeCallbacks(repeatAdjustVolumeRunnable)
            startIdleTimer()
        }
    }

    private var lifecycle: LifecycleRegistry? = null

    private fun createView(): View {
        return object : AbstractComposeView(this) {
            init {
                val owner = object : SavedStateRegistryOwner {
                    private val lifecycleRegistry = LifecycleRegistry(this)

                    private val savedStateRegistryController =
                        SavedStateRegistryController.create(this)

                    init {
                        savedStateRegistryController.performRestore(null)
                        lifecycleRegistry.currentState = Lifecycle.State.STARTED
                        this@Service.lifecycle = lifecycleRegistry
                    }

                    override val lifecycle: Lifecycle
                        get() = lifecycleRegistry

                    override val savedStateRegistry: SavedStateRegistry
                        get() = savedStateRegistryController.savedStateRegistry
                }

                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
            }

            override fun onAttachedToWindow() {
                super.onAttachedToWindow()

                Log.i(TAG, "onAttachedToWindow manufacturer: ${Build.MANUFACTURER}")

                @Suppress("SpellCheckingInspection") if (windowManager.isCrossWindowBlurEnabled && isHardwareAccelerated && Build.MANUFACTURER != "realme") {
                    try {
                        background =
                            Reflect.on(rootSurfaceControl).call("createBackgroundBlurDrawable").apply {
                                call("setBlurRadius", 200)
                                call("setCornerRadius", 40f)
                            }.get()
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to create background blur drawable", e)
                    }
                }

                this@Service.handler.startIdleTimer()
            }

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean {
                Log.i(TAG, "onTouchEvent ${event.actionMasked}")

                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    this@Service.handler.hideView()
                    return true
                }

                return super.onTouchEvent(event)
            }

            @Composable
            override fun Content() {
                return VolumeManagerTheme {
                    Surface(
                        color = Color(1f, 1f, 1f, 0.3f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(40f)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp, 16.dp)
                        ) {
                            AppVolumeList(
                                apps = manager.apps.values,
                                showAll = false,
                                onChange = this@Service.handler::startIdleTimer
                            ) {
                                item("system_volume_panel") {
                                    SystemVolumePanel(
                                        audioManager = manager.audioManager,
                                        notificationManagerProxy = manager.notificationManagerProxy,
                                        showCallVolumeAlways = false,
                                        applyVisibilityFilter = true,
                                        allowVisibilityConfig = false,
                                        isSliderVisible = manager::isSystemSliderVisible,
                                        onSliderVisibilityChange = manager::setSystemSliderVisible,
                                        onChange = this@Service.handler::startIdleTimer
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private val layoutParams by lazy {
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, // Width
            WindowManager.LayoutParams.WRAP_CONTENT, // Height
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT // Make the background translucent
        ).apply {
            gravity = Gravity.CENTER // Center the view
        }
    }

    private var view: View? = null
    private var viewVisible = false

    private fun showView() {
        if (view == null) {
            Log.i(TAG, "add view")
            // The view doesn't respond to input events if reused
            view = createView()
            layoutParams.alpha = 0f
            windowManager.addView(view, layoutParams)
        }

        if (!viewVisible) {
            Log.i(TAG, "animate in")
            animateAlpha(layoutParams.alpha, 1f, ANIMATION_DURATION)
            viewVisible = true
        }

        handler.startIdleTimer()
    }

    private var currentAnimator: ValueAnimator? = null

    private fun animateAlpha(from: Float, to: Float, duration: Long, onEnd: (() -> Unit)? = null) {
        currentAnimator?.cancel()

        val animator = ValueAnimator.ofFloat(from, to)
        animator.duration = duration
        animator.interpolator = AccelerateDecelerateInterpolator()

        animator.addUpdateListener { animation ->
            if (view != null) {
                layoutParams.alpha = animation.animatedValue as Float
                windowManager.updateViewLayout(view, layoutParams)
            }
        }

        animator.addListener(object : Animator.AnimatorListener {
            var canceled = false

            override fun onAnimationStart(animation: Animator) {}

            override fun onAnimationEnd(animation: Animator) {
                if (canceled) {
                    return
                }

                layoutParams.alpha = to
                windowManager.updateViewLayout(view, layoutParams)

                onEnd?.invoke()
            }

            override fun onAnimationCancel(animation: Animator) {
                canceled = true
            }

            override fun onAnimationRepeat(animation: Animator) {}
        })

        animator.start()
        currentAnimator = animator
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "onReceive ${intent.action}")
            if (intent.action == ACTION_SHOW_VIEW) {
                showView()
            }
        }
    }

    private val cameraManager by lazy {
        getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    @Volatile
    private var isCameraInUse = false
    private val openCameras = mutableSetOf<String>()

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            synchronized(openCameras) {
                openCameras.add(cameraId)
                isCameraInUse = openCameras.isNotEmpty()
            }
            Log.i(TAG, "Camera $cameraId unavailable (active), isCameraInUse=$isCameraInUse")
        }

        override fun onCameraAvailable(cameraId: String) {
            synchronized(openCameras) {
                openCameras.remove(cameraId)
                isCameraInUse = openCameras.isNotEmpty()
            }
            Log.i(TAG, "Camera $cameraId available (closed), isCameraInUse=$isCameraInUse")
        }
    }

    private val knownCameraPackages = mutableSetOf<String>()

    private fun refreshKnownCameraPackages() {
        try {
            val intents = listOf(
                Intent(MediaStore.ACTION_IMAGE_CAPTURE),
                Intent(MediaStore.ACTION_IMAGE_CAPTURE_SECURE),
                Intent(MediaStore.ACTION_VIDEO_CAPTURE),
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE),
                Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA),
                Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_CAMERA")
            )
            val pkgs = mutableSetOf<String>()
            for (intent in intents) {
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.queryIntentActivities(
                        intent,
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                }
                for (resolveInfo in list) {
                    resolveInfo.activityInfo?.packageName?.let { pkgs.add(it) }
                }
            }
            synchronized(knownCameraPackages) {
                knownCameraPackages.clear()
                knownCameraPackages.addAll(pkgs)
            }
            Log.i(TAG, "Discovered camera packages: $knownCameraPackages")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh camera packages", e)
        }
    }

    private fun isCameraApp(packageName: String?, className: String? = null): Boolean {
        if (packageName.isNullOrEmpty()) return false
        val pkgLower = packageName.lowercase()

        synchronized(knownCameraPackages) {
            if (knownCameraPackages.contains(packageName)) return true
        }

        // Common camera package indicators
        if (pkgLower.contains("camera") ||
            pkgLower.contains(".cam") ||
            pkgLower.endsWith(".cam") ||
            pkgLower.contains("snapcam") ||
            pkgLower.contains("aperture") ||
            pkgLower.contains("photopro") ||
            pkgLower.contains("cinemapro") ||
            pkgLower.contains("videopro") ||
            pkgLower.contains("proshot") ||
            pkgLower.contains("hedgecam") ||
            pkgLower.contains("filmic") ||
            pkgLower.contains("blackmagiccam") ||
            pkgLower.contains("gcam") ||
            pkgLower == "com.samsung.android.scan3d" ||
            pkgLower == "com.android.camera" ||
            pkgLower == "com.android.camera2"
        ) {
            return true
        }

        if (className != null) {
            val clsLower = className.lowercase()
            if (clsLower.contains("camera") ||
                clsLower.contains("photo") ||
                clsLower.contains("viewfinder") ||
                clsLower.contains("capture") ||
                clsLower.contains("shutter")
            ) {
                return true
            }
        }

        return false
    }

    override fun onServiceConnected() {
        Log.i(TAG, "onServiceConnected")

        val application = super.getApplication() as MyApplication
        manager = application.manager

        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = info
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update serviceInfo flags", e)
        }

        try {
            accessibilityButtonController.registerAccessibilityButtonCallback(object :
                AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController?) {
                    if (manager.rootStatus == Manager.RootStatus.Connected) {
                        showView()
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Accessibility button callback registration skipped", e)
        }

        registerReceiver(broadcastReceiver, IntentFilter(ACTION_SHOW_VIEW), RECEIVER_NOT_EXPORTED)

        try {
            cameraManager.registerAvailabilityCallback(cameraCallback, handler)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register camera availability callback", e)
        }

        Thread {
            refreshKnownCameraPackages()
        }.start()

        Log.i(TAG, "onServiceConnected done ${serviceInfo.capabilities.toString(2)}")
    }

    private var foregroundPackage: String? = null
    private var foregroundActivity: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                if (!pkg.isNullOrEmpty() && pkg != packageName) {
                    foregroundPackage = pkg
                    foregroundActivity = event.className?.toString()
                }
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                try {
                    val rootNode = rootInActiveWindow
                    val pkg = rootNode?.packageName?.toString()
                    if (!pkg.isNullOrEmpty() && pkg != packageName) {
                        foregroundPackage = pkg
                        foregroundActivity = rootNode.className?.toString()
                    }
                } catch (e: Exception) {
                    // Ignored
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "onInterrupt")
    }

    override fun onDestroy() {
        super.onDestroy()

        Log.i(TAG, "onDestroy")

        Toast.makeText(this, "Accessibility service died!", Toast.LENGTH_SHORT).show()

        try {
            cameraManager.unregisterAvailabilityCallback(cameraCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister camera availability callback", e)
        }

        unregisterReceiver(broadcastReceiver)
    }

    val activityTaskManager by lazy { ActivityTaskManagerProxy(this) }

    private data class ForegroundInfo(val packageName: String?, val className: String?)

    private fun getForegroundApp(): ForegroundInfo {
        // 1. Check rootInActiveWindow (direct, in-process active window)
        try {
            val rootNode = rootInActiveWindow
            val rootPkg = rootNode?.packageName?.toString()
            val rootCls = rootNode?.className?.toString()
            if (!rootPkg.isNullOrEmpty() && rootPkg != packageName) {
                foregroundPackage = rootPkg
                foregroundActivity = rootCls
                return ForegroundInfo(rootPkg, rootCls)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect rootInActiveWindow", e)
        }

        // 2. Check windows list (active or focused window)
        try {
            val activeWindow = windows.find { it.isActive || it.isFocused }
            val winPkg = activeWindow?.root?.packageName?.toString()
            val winCls = activeWindow?.root?.className?.toString()
            if (!winPkg.isNullOrEmpty() && winPkg != packageName) {
                foregroundPackage = winPkg
                foregroundActivity = winCls
                return ForegroundInfo(winPkg, winCls)
            }
        } catch (e: Exception) {
            // Ignored
        }

        // 3. Fallback to activityTaskManager (authoritative top task from OS via root)
        try {
            val task = activityTaskManager.getForegroundTask()
            if (task != null && task.app.isNotEmpty()) {
                foregroundPackage = task.app
                foregroundActivity = task.activityName.className
                return ForegroundInfo(task.app, task.activityName.className)
            }
        } catch (e: Exception) {
            // Ignored
        }

        // 4. Fallback to cached foreground info from accessibility events
        return ForegroundInfo(foregroundPackage, foregroundActivity)
    }

    private fun isCameraActiveInForeground(info: ForegroundInfo): Boolean {
        // A. Foreground package or activity is recognized as camera
        if (isCameraApp(info.packageName, info.className)) {
            return true
        }

        // B. Active camera hardware sensor is open and device is not in a phone/VoIP call
        if (isCameraInUse) {
            val inCall = manager.audioManager.mode == AudioManager.MODE_IN_COMMUNICATION ||
                         manager.audioManager.mode == AudioManager.MODE_IN_CALL
            if (!inCall) {
                Log.i(TAG, "Camera sensor in use and not in call, bypassing volume keys")
                return true
            }
        }

        return false
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Only handle `VOLUME_UP` and `VOLUME_DOWN`
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }

        val fgInfo = getForegroundApp()
        Log.i(
            TAG,
            "onKeyEvent action = ${event.action}, key code = ${event.keyCode}, fgPkg = ${fgInfo.packageName}, fgCls = ${fgInfo.className}, cameraInUse = $isCameraInUse"
        )

        // Bypass volume button interception for camera (allows taking photo/recording video)
        if (isCameraActiveInForeground(fgInfo)) {
            Log.i(TAG, "Camera active in foreground, bypassing volume button interception")
            return false
        }

        val fgPkg = fgInfo.packageName
        if (fgPkg != null) {
            val app = manager.apps[fgPkg]
            if (app != null && app.disableVolumeButtons) {
                return false
            }
        }

        // If root is checking or denied, trigger reconnect in background but continue intercepting volume keys
        if (manager.rootStatus != Manager.RootStatus.Connected) {
            manager.connectRoot(this)
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                handler.startRepeatAdjustVolume(
                    if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                        AudioManager.ADJUST_RAISE
                    } else {
                        AudioManager.ADJUST_LOWER
                    }
                )
                showView()
            }

            KeyEvent.ACTION_UP -> handler.stopRepeatAdjustVolume()
        }

        return true
    }
}
