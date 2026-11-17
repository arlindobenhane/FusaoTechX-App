package com.fusaotech.studio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Arranca o serviço ao ligar o telefone, se o utilizador o tiver ativado. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        val arrancar = i?.action == Intent.ACTION_BOOT_COMPLETED ||
            i?.action == "android.intent.action.QUICKBOOT_POWERON" ||
            i?.action == Intent.ACTION_MY_PACKAGE_REPLACED // depois de atualizar a app
        if (arrancar) {
            // Arranca sempre — acesso permanente sem precisar tocar no telefone.
            Watchdog.garantirServico(c)
            Watchdog.agendar(c)
        }
    }
}
