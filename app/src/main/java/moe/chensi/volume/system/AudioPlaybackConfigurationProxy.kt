package moe.chensi.volume.system

import android.media.AudioPlaybackConfiguration
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.util.Log
import org.joor.Reflect
import org.joor.ReflectException
import java.lang.reflect.InvocationTargetException

class AudioPlaybackConfigurationProxy {
    enum class PlayerState(val value: Int) {
        Unknown(-1), Released(0), Idle(1), Started(2), Paused(3), Stopped(4);
    }

    companion object {
        private const val TAG = "AppVolManager.Proxy"
        val classReflect: Reflect = Reflect.onClass(AudioPlaybackConfiguration::class.java)

        fun Int.toPlayerState(): PlayerState {
            for (state in PlayerState.entries) {
                if (state.value == this) {
                    return state
                }
            }
            return PlayerState.Unknown
        }
    }

    val packageName: String
    val clientPid: Int
    val playerType: Int
    val playerState: PlayerState
    private val playerInstance: Any?

    constructor(raw: AudioPlaybackConfiguration) {
        val reflect = Reflect.on(raw)
        packageName = try {
            reflect.get<String?>("mClientPackageName") ?: ""
        } catch (e: Exception) {
            ""
        }
        clientPid = reflect.get("mClientPid")
        playerType = reflect.get("mPlayerType")
        val stateVal: Int = reflect.get("mPlayerState")
        playerState = stateVal.toPlayerState()
        playerInstance = reflect.call("getIPlayer").get<Any?>()
    }

    constructor(bundle: Bundle) {
        packageName = bundle.getString("packageName") ?: ""
        clientPid = bundle.getInt("clientPid")
        playerType = bundle.getInt("playerType")
        val stateVal = bundle.getInt("playerState")
        playerState = stateVal.toPlayerState()

        val binder = bundle.getBinder("player")
        Log.d(TAG, "Bundle constructor: packageName=$packageName, pid=$clientPid, playerType=$playerType, state=$playerState, hasBinder=${binder != null}")
        playerInstance = if (binder != null) {
            try {
                val iplayerStubClass = Class.forName("android.media.IPlayer\$Stub")
                val asInterfaceMethod = iplayerStubClass.getMethod("asInterface", IBinder::class.java)
                val player = asInterfaceMethod.invoke(null, binder)
                Log.d(TAG, "IPlayer proxy created successfully for $packageName: $player")
                player
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create IPlayer proxy for $packageName", e)
                null
            }
        } else {
            Log.w(TAG, "No player binder available for $packageName (pid=$clientPid)")
            null
        }
    }

    val hasPlayer: Boolean
        get() = playerInstance != null

    val playerTypeName: String by lazy {
        classReflect.call("toLogFriendlyPlayerType", playerType).get()
    }

    val playerStateName: String by lazy {
        classReflect.call("playerStateToString", playerState.value).get()
    }

    val isPlaying: Boolean
        get() {
            if (playerType == 3) {
                return true
            }

            return playerState == PlayerState.Started
        }

    fun setVolume(value: Float): Boolean {
        if (playerInstance == null) {
            Log.w(TAG, "setVolume($value) called but playerInstance is null for $packageName")
            return false
        }
        return try {
            Reflect.on(playerInstance).call("setVolume", value)
            Log.d(TAG, "setVolume($value) succeeded for $packageName")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "setVolume($value) FAILED for $packageName (playerInstance=$playerInstance)", e)
            false
        }
    }
}
