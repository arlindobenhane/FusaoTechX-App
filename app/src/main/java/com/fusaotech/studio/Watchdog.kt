package com.fusaotech.studio

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * FusãoTechX Mz • "Cão de guarda" da ligação permanente.
 *
 * Alguns telefones (Xiaomi, Samsung, etc.) matam serviços em segundo plano para poupar bateria.
 * Este alarme repete-se a cada ~15 minutos e volta a ligar o serviço se ele tiver sido morto,
 * para a ligação remota ser permanente — reconecta sozinha até o dono desligar, desinstalar ou formatar.
 * Não depende de conta nenhuma: a identidade do aparelho (ID + segredo) já é permanente.
 */
object Watchdog {
    private const val COD = 90901
    private const val INTERVALO = 15 * 60 * 1000L

    private fun pi(c: Context): PendingIntent {
        val i = Intent(c, WatchdogReceiver::class.java).setAction("ftx.watch")
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(c, COD, i, flags)
    }

    fun agendar(c: Context) {
        try {
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + 60000, INTERVALO, pi(c))
        } catch (_: Exception) {}
    }

    fun cancelar(c: Context) {
        try { (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(c)) } catch (_: Exception) {}
    }

    /** Liga o serviço permanente (sem captura de ecrã) — sempre, em cada ciclo. */
    fun garantirServico(c: Context) {
        try {
            val s = Intent(c, CaptureService::class.java).setAction("remoto")
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s) else c.startService(s)
        } catch (_: Exception) {}
    }
}

/** Recebe o alarme do cão de guarda e revive o serviço. */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) { Watchdog.garantirServico(c) }
}
