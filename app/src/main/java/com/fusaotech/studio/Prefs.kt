package com.fusaotech.studio

import android.content.Context
import android.os.Build

/**
 * FusãoTechX Mz • Identidade do aparelho (conta padrão, sem email nem login).
 * Na primeira abertura é criado um nome e um código de 4 dígitos, guardados
 * só neste telefone. Serve para o PC saber a qual telefone se está a ligar.
 */
object Prefs {
    private const val FILE = "ftx"
    const val PORT = 9999

    fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun code(c: Context): String {
        val sp = sp(c)
        var v = sp.getString("code", null)
        if (v == null) {
            v = (1000 + (Math.random() * 9000).toInt()).toString()
            sp.edit().putString("code", v).apply()
        }
        return v
    }

    fun setCode(c: Context, v: String) = sp(c).edit().putString("code", v).apply()

    fun name(c: Context): String {
        val sp = sp(c)
        var v = sp.getString("name", null)
        if (v == null) {
            v = (Build.MANUFACTURER + " " + Build.MODEL).trim().replace("_", " ")
            sp.edit().putString("name", v).apply()
        }
        return v
    }

    fun setName(c: Context, v: String) = sp(c).edit().putString("name", v).apply()

    fun relay(c: Context): String = sp(c).getString("relay", "") ?: ""
    fun setRelay(c: Context, v: String) = sp(c).edit().putString("relay", v).apply()

    fun autostart(c: Context): Boolean = sp(c).getBoolean("autostart", false)
    fun setAutostart(c: Context, v: Boolean) = sp(c).edit().putBoolean("autostart", v).apply()
}
