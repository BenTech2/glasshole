package com.glasshole.glass.sdk

import android.os.Parcel
import android.os.Parcelable

data class GlassPluginMessage(
    val type: String,
    val payload: String,
    val binaryData: ByteArray? = null
) : Parcelable {

    constructor(parcel: Parcel) : this(
        type = parcel.readString() ?: "",
        payload = parcel.readString() ?: "",
        binaryData = parcel.createByteArray()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(type)
        parcel.writeString(payload)
        parcel.writeByteArray(binaryData)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GlassPluginMessage) return false
        return type == other.type && payload == other.payload &&
            binaryData.contentEquals(other.binaryData)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + payload.hashCode()
        result = 31 * result + (binaryData?.contentHashCode() ?: 0)
        return result
    }

    companion object CREATOR : Parcelable.Creator<GlassPluginMessage> {
        override fun createFromParcel(parcel: Parcel): GlassPluginMessage = GlassPluginMessage(parcel)
        override fun newArray(size: Int): Array<GlassPluginMessage?> = arrayOfNulls(size)
    }
}
