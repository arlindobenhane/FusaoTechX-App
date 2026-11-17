package com.fusaotech.studio

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * FusãoTechX Mz • Disfarce / ocultar o ícone (anti-roubo).
 *
 * Três modos para que um ladrão não perceba que a app está instalada:
 *  - NORMAL: ícone e nome FusãoTechX.
 *  - DISFARCE: ícone e nome discretos ("Serviços do sistema").
 *  - OCULTO: sem ícone na lista de apps. Reabre-se marcando *#*#3272#*#* no teclado de chamadas.
 *
 * Nota: a app mantém sempre uma notificação permanente enquanto está ligada (exigência do Android
 * e também um aviso de que está a funcionar). O disfarce esconde o ícone, não a notificação.
 */
object Disfarce {
    const val NORMAL = "normal"
    const val DISFARCE = "disfarce"
    const val OCULTO = "oculto"
    const val CODIGO_SEGREDO = "3272" // marcar *#*#3272#*#* reabre a app quando está oculta

    private fun normal(c: Context) = ComponentName(c, "com.fusaotech.studio.AliasNormal")
    private fun disfarce(c: Context) = ComponentName(c, "com.fusaotech.studio.AliasDisfarce")

    private fun set(c: Context, comp: ComponentName, ligado: Boolean) {
        try {
            c.packageManager.setComponentEnabledSetting(
                comp,
                if (ligado) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}
    }

    fun aplicar(c: Context, modo: String) {
        // A MainActivity fica sempre ativa (para o código secreto a poder reabrir); mexemos só nos atalhos.
        when (modo) {
            DISFARCE -> { set(c, disfarce(c), true); set(c, normal(c), false) }
            OCULTO -> { set(c, disfarce(c), false); set(c, normal(c), false) }
            else -> { set(c, normal(c), true); set(c, disfarce(c), false) }
        }
        Prefs.setDisfarce(c, modo)
    }

    fun atual(c: Context): String = Prefs.disfarce(c)

}
