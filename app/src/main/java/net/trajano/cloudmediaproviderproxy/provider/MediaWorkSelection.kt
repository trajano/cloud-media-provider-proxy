package net.trajano.cloudmediaproviderproxy.provider

import androidx.work.Data

internal data class MediaWorkSelection(val root: String, val epoch: Long, val compatible: Boolean) {
    fun matches(current: MediaWorkSelection?): Boolean = this == current

    fun toData(): Data = Data.Builder().putString("root", root).putLong("epoch", epoch)
        .putBoolean("compatible", compatible).build()

    companion object {
        fun fromData(data: Data): MediaWorkSelection? = data.getString("root")?.let {
            MediaWorkSelection(it, data.getLong("epoch", -1L), data.getBoolean("compatible", false))
        }
    }
}
