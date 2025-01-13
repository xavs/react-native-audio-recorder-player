package com.dooboolab.audiorecorderplayer

import android.os.Parcel
import android.os.Parcelable
import androidx.core.app.NotificationCompat

data class NotificationConfig(
    val channelId: String = "recording_channel",
    val channelName: String = "Recording Service",
    val channelDescription: String = "Audio recording in progress",
    val notificationId: Int = 1,
    val notificationTitle: String = "Recording Audio",
    val notificationText: String = "Recording in progress",
    val notificationIcon: Int = android.R.drawable.ic_btn_speak_now,
    val notificationPriority: Int = NotificationCompat.PRIORITY_LOW
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "recording_channel",
        parcel.readString() ?: "Recording Service",
        parcel.readString() ?: "Audio recording in progress",
        parcel.readInt(),
        parcel.readString() ?: "Recording Audio",
        parcel.readString() ?: "Recording in progress",
        parcel.readInt(),
        parcel.readInt()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(channelId)
        parcel.writeString(channelName)
        parcel.writeString(channelDescription)
        parcel.writeInt(notificationId)
        parcel.writeString(notificationTitle)
        parcel.writeString(notificationText)
        parcel.writeInt(notificationIcon)
        parcel.writeInt(notificationPriority)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<NotificationConfig> {
        override fun createFromParcel(parcel: Parcel): NotificationConfig {
            return NotificationConfig(parcel)
        }

        override fun newArray(size: Int): Array<NotificationConfig?> {
            return arrayOfNulls(size)
        }
    }
}