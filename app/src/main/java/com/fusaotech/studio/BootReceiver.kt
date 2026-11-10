package com.fusaotech.studio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Arranca o serviço ao ligar o telefone, se o utilizador o tiver ativado. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        if (i?.action == Intent.ACTION_BOOT_COMPLETED && Prefs.autostart(c)) {
            // Sem captura de ecrã (precisa de toque do utilizador), mas liga
            // SMS/chamadas/notificações/ficheiros logo no arranque.
            val s = Intent(c, CaptureService::class.java).setAction("boot")
            if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(s) else c.startService(s)
        }
    }
}
