package moe.chensi.volume.system

import android.media.AudioPlaybackConfiguration
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import org.joor.Reflect
import org.joor.ReflectException
import java.lang.reflect.InvocationTargetException

class AudioPlaybackConfigurationProxy {
    enum class PlayerState(val value: Int) {
        Unknown(-1), Released(0), Idle(1), Started(2), Paused(3), Stopped(4);
    }

    companion object {
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
        playerInstance = if (binder != null) {
            try {
                val iplayerStubClass = Class.forName("android.media.IPlayer\$Stub")
                val asInterfaceMethod = iplayerStubClass.getMethod("asInterface", IBinder::class.java)
                asInterfaceMethod.invoke(null, binder)
            } catch (e: Exception) {
                null
            }
        } else {
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
        if (playerInstance == null) return false
        return try {
            Reflect.on(playerInstance).call("setVolume", value)
            true
        } catch (e: Throwable) {
            false
        }
    }
}
