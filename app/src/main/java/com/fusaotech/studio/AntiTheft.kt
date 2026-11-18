package com.fusaotech.studio

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import org.json.JSONObject

/**
 * Gestão do modo anti-roubo.
 * Quando ativo:
 *  - Bloqueia botão de energia (via Accessibility key intercept)
 *  - Detecta PIN errado e tira selfie silenciosa
 *  - Permite bloquear ecrã, tocar alarme, apagar dados — tudo remotamente
 */
object AntiTheft {

    private fun sp(c: Context) = c.getSharedPreferences("ftx_at", Context.MODE_PRIVATE)

    // ── Estado ──────────────────────────────────────────────────────────────

    fun isAtivo(c: Context) = sp(c).getBoolean("at_ativo", false)

    fun ativar(c: Context) {
        sp(c).edit()
            .putBoolean("at_ativo", true)
            .putLong("at_ativado_em", System.currentTimeMillis())
            .apply()
    }

    fun desativar(c: Context) {
        sp(c).edit().putBoolean("at_ativo", false).apply()
        pararAlarme()
    }

    fun estado(c: Context): JSONObject {
        val dpm = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return JSONObject()
            .put("ativo", isAtivo(c))
            .put("admin", AdminReceiver.ativo(c))
            .put("alarme", alarmeAtivo)
            .put("pin_errados", sp(c).getInt("pin_err", 0))
            .put("ativado_em", sp(c).getLong("at_ativado_em", 0))
            .put("admin_off_em", sp(c).getLong("at_admin_off", 0))
    }

    // ── Bloquear ecrã ───────────────────────────────────────────────────────

    fun bloquear(c: Context): Boolean = try {
        val dpm = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (AdminReceiver.ativo(c)) { dpm.lockNow(); true } else false
    } catch (_: Exception) { false }

    // ── Apagar dados (factory reset) ────────────────────────────────────────

    fun apagarDados(c: Context): Boolean = try {
        val dpm = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (AdminReceiver.ativo(c)) { dpm.wipeData(0); true } else false
    } catch (_: Exception) { false }

    // ── Alarme ──────────────────────────────────────────────────────────────

    @Volatile private var ringtone: Ringtone? = null
    @Volatile var alarmeAtivo = false

    fun ativarAlarme(c: Context) {
        try {
            pararAlarme()
            alarmeAtivo = true
            val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(c, uri)
            if (Build.VERSION.SDK_INT >= 28) ringtone?.isLooping = true
            ringtone?.play()
        } catch (_: Exception) {}
    }

    fun pararAlarme() {
        alarmeAtivo = false
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
    }

    // ── PIN errado → selfie silenciosa ──────────────────────────────────────

    fun onPinErrado(c: Context) {
        sp(c).edit().putInt("pin_err", sp(c).getInt("pin_err", 0) + 1).apply()
        if (!isAtivo(c)) return
        Thread {
            try {
                val bytes = if (Build.VERSION.SDK_INT >= 31)
                    CameraActivity.tirarFoto(c, true, 8000)
                else
                    Camera.capturar(c, true)
                if (bytes != null) {
                    val nome = "AT_PIN_${System.currentTimeMillis()}.jpg"
                    Device.guardarImagem(c, bytes, nome)
                }
            } catch (_: Exception) {}
        }.start()
    }

    // ── Admin desativado ─────────────────────────────────────────────────────

    fun onAdminDesativado(c: Context) {
        sp(c).edit().putLong("at_admin_off", System.currentTimeMillis()).apply()
    }
}
