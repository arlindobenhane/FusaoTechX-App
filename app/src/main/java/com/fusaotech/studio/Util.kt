package com.fusaotech.studio

import android.content.Context
import java.net.NetworkInterface

object Util {
    /** Endereços IPv4 locais do telefone (Wi-Fi ou hotspot), para o PC se ligar. */
    fun localIps(): List<String> {
        val out = ArrayList<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.inetAddresses) {
                    val h = ia.hostAddress ?: continue
                    if (ia.isLoopbackAddress) continue
                    if (h.contains(":")) continue // só IPv4
                    out.add(h)
                }
            }
        } catch (_: Exception) {}
        // Ordena para mostrar primeiro redes comuns de telemóvel/hotspot
        return out.sortedBy { if (it.startsWith("192.168.43") || it.startsWith("192.168")) 0 else 1 }
    }

    fun esc(s: String?): String {
        if (s == null) return ""
        val b = StringBuilder()
        for (ch in s) when (ch) {
            '\\' -> b.append("\\\\"); '"' -> b.append("\\\"")
            '\n' -> b.append("\\n"); '\r' -> b.append("\\r"); '\t' -> b.append("\\t")
            else -> if (ch < ' ') b.append(String.format("\\u%04x", ch.code)) else b.append(ch)
        }
        return b.toString()
    }
}
