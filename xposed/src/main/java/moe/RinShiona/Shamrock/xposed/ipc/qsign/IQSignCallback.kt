package moe.RinShiona.Shamrock.xposed.ipc.qsign

import android.os.Parcel
import android.os.Parcelable

data class IQSignCallback(
    @JvmField var cmd: String = "",
    @JvmField var body: String = "",
    @JvmField var callbackId: Long = 0L
) : Parcelable {

    constructor(parcel: Parcel) : this(
        cmd = parcel.readString() ?: "",
        body = parcel.readString() ?: "",
        callbackId = parcel.readLong()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(cmd)
        parcel.writeString(body)
        parcel.writeLong(callbackId)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<IQSignCallback> {
        override fun createFromParcel(parcel: Parcel): IQSignCallback = IQSignCallback(parcel)
        override fun newArray(size: Int): Array<IQSignCallback?> = arrayOfNulls(size)
    }
}
