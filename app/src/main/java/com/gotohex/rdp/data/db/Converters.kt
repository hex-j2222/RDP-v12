package com.gotohex.rdp.data.db

import androidx.room.TypeConverter
import com.gotohex.rdp.data.model.ProtocolType
import com.gotohex.rdp.data.model.SshAuthType

/**
 * Room type converters for the enum fields introduced when multi-protocol
 * support (RDP / VNC / SSH) was added to [com.gotohex.rdp.data.model.RdpProfile].
 */
class Converters {
    @TypeConverter
    fun fromProtocolType(value: ProtocolType): String = value.name

    @TypeConverter
    fun toProtocolType(value: String): ProtocolType = ProtocolType.fromName(value)

    @TypeConverter
    fun fromSshAuthType(value: SshAuthType): String = value.name

    @TypeConverter
    fun toSshAuthType(value: String): SshAuthType =
        SshAuthType.entries.firstOrNull { it.name == value } ?: SshAuthType.PASSWORD
}
