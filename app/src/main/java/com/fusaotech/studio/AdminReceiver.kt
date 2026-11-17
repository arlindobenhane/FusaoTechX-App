package com.fusaotech.studio

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

/**
 * FusãoTechX Mz • Administrador do dispositivo (anti-roubo).
 * Enquanto estiver ativo, a app NÃO pode ser desinstalada sem primeiro ser desativada
 * aqui (o que exige abrir a app e o código). Também permite bloquear o ecrã à distância.
 * É o próprio dono que o ativa, na app. Para desinstalar: abrir a app › desligar a proteção.
 */
class AdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun comp(c: Context) = ComponentName(c, AdminReceiver::class.java)
        fun ativo(c: Context): Boolean =
            try { (c.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager).isAdminActive(comp(c)) } catch (_: Exception) { false }
    }
}
