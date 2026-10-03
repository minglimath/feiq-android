package com.feiq.droid.bridge

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** 本机局域网 IPv4：跳过回环与未启用的网卡。 */
internal fun localIp(): String? {
    try {
        Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { nif ->
            if (nif.isUp && !nif.isLoopback) {
                Collections.list(nif.inetAddresses).forEach { addr ->
                    if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress
                }
            }
        }
    } catch (_: Exception) {
    }
    return null
}

/** 仿飞秋版本段用的 12 位 HEX 伪 MAC：优先取本机网卡 MAC，取不到用固定值。 */
internal fun pseudoMac(): String {
    try {
        val mac = Collections.list(NetworkInterface.getNetworkInterfaces())
            .firstNotNullOfOrNull { it.hardwareAddress?.takeIf { b -> b.size == 6 } }
        if (mac != null) return mac.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    } catch (_: Exception) {
    }
    return "FEIQBRIDGE00"
}
