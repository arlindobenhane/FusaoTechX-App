package com.fusaotech.studio

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserHandle

/**
 * Device Admin para anti-roubo e anti-desinstalação.
 * Com este admin ativo, a app não pode ser desinstalada diretamente.
 * Permite também: bloquear ecrã, apagar dados e detetar PIN errado.
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "⚠️ Proteção anti-roubo ativa. Desativar permite desinstalar esta aplicação de segurança."

    override fun onDisabled(context: Context, intent: Intent) {
        AntiTheft.onAdminDesativado(context)
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        AntiTheft.onPinErrado(context)
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {}

    companion object {
        fun comp(c: Context) = ComponentName(c, AdminReceiver::class.java)
        fun ativo(c: Context): Boolean =
            try {
                (c.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager)
                    .isAdminActive(comp(c))
            } catch (_: Exception) { false }
    }
}
