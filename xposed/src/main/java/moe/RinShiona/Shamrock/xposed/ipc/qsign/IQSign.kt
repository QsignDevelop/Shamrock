package moe.RinShiona.Shamrock.xposed.ipc.qsign

import android.os.Parcel
import android.os.Parcelable

/**
 * Parcelable Kotlin counterpart of the AIDL `parcelable IQSign;` declaration.
 */
data class IQSign(
    @JvmField var token: ByteArray = ByteArray(0),
    @JvmField var sign: ByteArray = ByteArray(0),
    @JvmField var extra: ByteArray = ByteArray(0),
    @JvmField var o3did: String = "",
    @JvmField var callbacks: List<IQSignCallback> = emptyList()
) : Parcelable {

    constructor(parcel: Parcel) : this(
        token = parcel.createByteArray() ?: ByteArray(0),
        sign = parcel.createByteArray() ?: ByteArray(0),
        extra = parcel.createByteArray() ?: ByteArray(0),
        o3did = parcel.readString() ?: "",
        callbacks = parcel.createTypedArrayList(IQSignCallback.CREATOR) ?: emptyList()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeByteArray(token)
        parcel.writeByteArray(sign)
        parcel.writeByteArray(extra)
        parcel.writeString(o3did)
        parcel.writeTypedList(callbacks)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IQSign) return false
        return token.contentEquals(other.token) &&
            sign.contentEquals(other.sign) &&
            extra.contentEquals(other.extra) &&
            o3did == other.o3did &&
            callbacks == other.callbacks
    }

    override fun hashCode(): Int {
        var h = token.contentHashCode()
        h = 31 * h + sign.contentHashCode()
        h = 31 * h + extra.contentHashCode()
        h = 31 * h + o3did.hashCode()
        h = 31 * h + callbacks.hashCode()
        return h
    }

    companion object CREATOR : Parcelable.Creator<IQSign> {
        override fun createFromParcel(parcel: Parcel): IQSign = IQSign(parcel)
        override fun newArray(size: Int): Array<IQSign?> = arrayOfNulls(size)
    }
}
