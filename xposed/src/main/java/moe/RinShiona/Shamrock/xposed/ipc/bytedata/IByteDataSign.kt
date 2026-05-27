package moe.RinShiona.Shamrock.xposed.ipc.bytedata

import android.os.Parcel
import android.os.Parcelable

/**
 * Parcelable wrapper used by IByteData.sign(...).
 *
 * Mirrors the structure used by QQ's own `com.tencent.mobileqq.qsec.qsecprotocol.ByteData`
 * — except here `sign` is the only field we surface to the HTTP layer.
 */
data class IByteDataSign(
    @JvmField var sign: ByteArray? = null
) : Parcelable {

    constructor(parcel: Parcel) : this(sign = parcel.createByteArray())

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeByteArray(sign)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IByteDataSign) return false
        val a = sign
        val b = other.sign
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        return a.contentEquals(b)
    }

    override fun hashCode(): Int = sign?.contentHashCode() ?: 0

    companion object CREATOR : Parcelable.Creator<IByteDataSign> {
        override fun createFromParcel(parcel: Parcel): IByteDataSign = IByteDataSign(parcel)
        override fun newArray(size: Int): Array<IByteDataSign?> = arrayOfNulls(size)
    }
}
