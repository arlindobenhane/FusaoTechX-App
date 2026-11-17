package com.fusaotech.studio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * FusãoTechX Mz • Reabrir a app quando está oculta.
 * Marcar *#*#3272#*#* no teclado de chamadas envia este código secreto e abre a app.
 */
class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        try {
            val host = i?.data?.host
            if (host == Disfarce.CODIGO_SEGREDO) {
                val a = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                c.startActivity(a)
            }
        } catch (_: Exception) {}
    }
}
