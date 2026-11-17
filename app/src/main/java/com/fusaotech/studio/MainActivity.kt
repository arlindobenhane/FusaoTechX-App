package com.fusaotech.studio

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

/**
 * FusãoTechX Mz • Ecrã de preparação da app.
 * Mostra o código, os endereços para o PC e os botões de permissões.
 */
class MainActivity : Activity() {

    private val REQ_PROJ = 101
    private val REQ_PERM = 102
    private lateinit var info: TextView
    private lateinit var estado: TextView
    private lateinit var remoto: TextView

    private val cor = object {
        val bg = Color.parseColor("#0B1020"); val card = Color.parseColor("#141D38")
        val ac = Color.parseColor("#22D3EE"); val tx = Color.parseColor("#E8ECF8")
        val mu = Color.parseColor("#93A0C4"); val ok = Color.parseColor("#34D399")
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = ScrollView(this).apply { setBackgroundColor(cor.bg) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(28), dp(22), dp(40)) }
        root.addView(col)

        col.addView(ImageView(this).apply {
            try { setImageBitmap(android.graphics.BitmapFactory.decodeStream(assets.open("logo.png"))) } catch (_: Exception) {}
            layoutParams = LinearLayout.LayoutParams(dp(130), dp(130)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        })
        col.addView(TextView(this).apply { text = "FusãoTechX"; setTextColor(cor.ac); textSize = 30f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        col.addView(TextView(this).apply { text = "O seu telefone no PC — app própria, sem conta"; setTextColor(cor.mu); textSize = 14f; setPadding(0, dp(2), 0, dp(18)) })

        // Nome e código
        col.addView(rotulo("Nome deste telefone"))
        val eNome = campo(Prefs.name(this))
        col.addView(eNome)
        col.addView(rotulo("Código de acesso (o PC pede este código)"))
        val eCode = campo(Prefs.code(this)).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        col.addView(eCode)
        col.addView(rotulo("Código do Modo Avançado (anti-roubo: câmara, localização, alarme)"))
        val eCode2 = campo(Prefs.code2(this)).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        col.addView(eCode2)
        col.addView(botao("Guardar nome e códigos", cor.card) {
            val n = eNome.text.toString().trim(); if (n.isNotEmpty()) Prefs.setName(this, n)
            val c = eCode.text.toString().trim(); if (c.length in 4..12) Prefs.setCode(this, c)
            val c2 = eCode2.text.toString().trim(); if (c2.length in 4..12) Prefs.setCode2(this, c2)
            toast("Guardado"); refrescar()
        })

        // Info de ligação
        info = TextView(this).apply { setTextColor(cor.tx); textSize = 14f; setPadding(dp(14), dp(14), dp(14), dp(14)); setBackgroundColor(cor.card) }
        col.addView(espaco(14)); col.addView(info)

        col.addView(espaco(18))
        col.addView(TextView(this).apply { text = "1 · Permissões (uma vez)"; setTextColor(cor.ac); textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        col.addView(botao("Permitir controlo (Acessibilidade)", cor.card) { abrir(Settings.ACTION_ACCESSIBILITY_SETTINGS) })
        col.addView(botao("Permitir ler notificações", cor.card) { abrir(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) })
        col.addView(botao("Permitir SMS, chamadas, contactos, câmara, localização", cor.card) { pedirPermissoes() })
        col.addView(botao("Acesso a todos os ficheiros", cor.card) { ficheiros() })
        col.addView(botao("Não desligar em segundo plano (bateria)", cor.card) { bateria() })

        col.addView(espaco(18))
        col.addView(TextView(this).apply { text = "2 · Ligar"; setTextColor(cor.ac); textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        col.addView(botao("▶  LIGAR (partilhar ecrã e iniciar)", cor.ok) { iniciar() })
        col.addView(botao("Parar", cor.card) { parar() })

        // Ligação remota: telefone nos dados móveis, PC em qualquer rede.
        col.addView(espaco(18))
        col.addView(TextView(this).apply { text = "3 · Ligação remota (qualquer rede)"; setTextColor(cor.ac); textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        col.addView(rotulo("Para usar o telefone nos dados móveis com o PC noutra rede. No PC, abra studio.fusaotech.com e escreva o ID. Fica ligada sempre, mesmo com a app fechada ou após reiniciar — reconecta sozinha."))
        val cbRemoto = CheckBox(this).apply {
            text = "Ligação remota permanente (reconecta sozinha)"; setTextColor(cor.tx); isChecked = Prefs.relayOn(this@MainActivity)
            setOnCheckedChangeListener { _, v ->
                Prefs.setRelayOn(this@MainActivity, v)
                if (v) {
                    Prefs.setAutostart(this@MainActivity, true)
                    // Arranca o serviço permanente (sem ecrã) e o cão de guarda.
                    val si = Intent(this@MainActivity, CaptureService::class.java).setAction("remoto")
                    if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(si) else startService(si)
                    Watchdog.agendar(this@MainActivity)
                    pedirSemBateria()
                } else {
                    val si = Intent(this@MainActivity, CaptureService::class.java).setAction("stop")
                    startService(si); Watchdog.cancelar(this@MainActivity)
                }
                refrescar()
            }
        }
        col.addView(cbRemoto)
        remoto = TextView(this).apply { setTextColor(cor.tx); textSize = 15f; setPadding(dp(14), dp(14), dp(14), dp(14)); setBackgroundColor(cor.card) }
        col.addView(espaco(6)); col.addView(remoto)
        col.addView(botao("Gerar um ID novo", cor.card) {
            Relay.parar(); Prefs.novoRelayId(this)
            if (Prefs.relayOn(this) && CaptureService.ativo) Relay.iniciar(this)
            toast("ID novo: " + Prefs.relayId(this)); refrescar()
        })

        // 4 · Proteção anti-roubo: impedir desinstalação e disfarçar/ocultar o ícone.
        col.addView(espaco(18))
        col.addView(TextView(this).apply { text = "4 · Proteção anti-roubo"; setTextColor(cor.ac); textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        col.addView(rotulo("Impede o ladrão de desinstalar a app e de perceber que ela existe."))
        val btnAdmin = botao("", cor.card) {}
        fun atualizarAdmin() {
            val on = AdminReceiver.ativo(this)
            (btnAdmin as Button).text = if (on) "✔ Proteção de desinstalação ATIVA (tocar para desligar)" else "Ativar proteção contra desinstalação"
            btnAdmin.setBackgroundColor(if (on) cor.ok else cor.card)
        }
        btnAdmin.setOnClickListener {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            if (AdminReceiver.ativo(this)) {
                dpm.removeActiveAdmin(AdminReceiver.comp(this)); toast("Proteção desligada — já pode desinstalar")
                btnAdmin.postDelayed({ atualizarAdmin() }, 400)
            } else {
                val it = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                it.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, AdminReceiver.comp(this))
                it.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_desc))
                try { startActivity(it) } catch (_: Exception) { toast("Indisponível neste telefone") }
            }
        }
        atualizarAdmin()
        col.addView(btnAdmin)

        col.addView(rotulo("Ícone da app:"))
        val linhaDisf = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val estadoDisf = TextView(this).apply { setTextColor(cor.mu); textSize = 12.5f; setPadding(0, dp(6), 0, 0) }
        fun aplicarDisfarce(modo: String) {
            Disfarce.aplicar(this, modo)
            estadoDisf.text = when (modo) {
                Disfarce.DISFARCE -> "Disfarçado como «Serviços do sistema». Para reabrir: procure esse nome na lista de apps."
                Disfarce.OCULTO -> "Oculto. Para reabrir, marque  *#*#3272#*#*  no teclado de chamadas."
                else -> "Normal: ícone e nome FusãoTechX."
            }
            toast("Guardado")
        }
        for ((modo, txt) in listOf(Disfarce.NORMAL to "Normal", Disfarce.DISFARCE to "Disfarçar", Disfarce.OCULTO to "Ocultar")) {
            linhaDisf.addView(Button(this).apply {
                text = txt; setTextColor(Color.WHITE); setBackgroundColor(cor.card); isAllCaps = false; textSize = 14f
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); lp.marginEnd = dp(6); layoutParams = lp
                setOnClickListener {
                    if (modo == Disfarce.OCULTO) AlertDialogOcultar(modo, ::aplicarDisfarce) else aplicarDisfarce(modo)
                }
            })
        }
        col.addView(espaco(6)); col.addView(linhaDisf); col.addView(estadoDisf)
        estadoDisf.text = when (Disfarce.atual(this)) { Disfarce.DISFARCE -> "Atual: disfarçado."; Disfarce.OCULTO -> "Atual: oculto (reabre com *#*#3272#*#*)."; else -> "Atual: normal." }

        val cbAuto = CheckBox(this).apply {
            text = "Arrancar com o telefone"; setTextColor(cor.tx); isChecked = Prefs.autostart(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setAutostart(this@MainActivity, v) }
        }
        col.addView(espaco(8)); col.addView(cbAuto)

        estado = TextView(this).apply { setTextColor(cor.mu); textSize = 13f; setPadding(0, dp(16), 0, 0) }
        col.addView(estado)

        col.addView(espaco(18))
        col.addView(TextView(this).apply { text = "Instalar noutro telefone"; setTextColor(cor.ac); textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        col.addView(rotulo("Lê este QR com o outro telefone para descarregar a app:"))
        col.addView(ImageView(this).apply {
            try { setImageBitmap(qr("https://github.com/arlindobenhane/FusaoTechX-App/releases/download/apk/FusaoTechX.apk", 440)) } catch (_: Exception) {}
            layoutParams = LinearLayout.LayoutParams(dp(190), dp(190)).apply { topMargin = dp(6) }
            setBackgroundColor(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8))
        })
        col.addView(TextView(this).apply { text = "FusãoTechX Mz · +258 83 333 7906 · fusaotech.com"; setTextColor(cor.ac); textSize = 12f; setPadding(0, dp(24), 0, 0); gravity = Gravity.CENTER })

        setContentView(root)
    }

    private fun AlertDialogOcultar(modo: String, aplicar: (String) -> Unit) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Ocultar a app?")
            .setMessage("O ícone desaparece da lista de apps. Para voltar a abrir, marque  *#*#3272#*#*  no teclado de chamadas (como para uma chamada). Guarde este código.")
            .setPositiveButton("Ocultar") { _, _ -> aplicar(modo) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onResume() { super.onResume(); refrescar() }

    private fun refrescar() {
        val ips = Util.localIps()
        val sb = StringBuilder()
        sb.append("Código: ").append(Prefs.code(this)).append("\n\nNo PC, abra no navegador:\n")
        if (ips.isEmpty()) sb.append("  (ligue o Wi-Fi ou o hotspot do telefone)") else for (ip in ips) sb.append("  http://").append(ip).append(":").append(Prefs.PORT).append("\n")
        sb.append("\nMesma rede Wi-Fi ou hotspot do telefone.")
        info.text = sb.toString()
        val idf = Prefs.relayId(this).chunked(3).joinToString(" ")
        remoto.text = if (Prefs.relayOn(this))
            "No PC (qualquer rede):\n  studio.fusaotech.com\n\nID: $idf\nCódigo: ${Prefs.code(this)}\n\nEstado: " +
                (if (Relay.correr) Relay.estado else if (CaptureService.ativo) "a iniciar…" else "toque em LIGAR")
        else "Desativada. Ative para ligar o PC ao telefone a partir de qualquer rede.\nID deste telefone: $idf"
        remoto.removeCallbacks(tick); remoto.postDelayed(tick, 3000)
        val c = if (ControlService.inst != null) "ativo" else "por ativar"
        val n = if (NotifService.inst != null) "ativo" else "por ativar"
        val e = if (CaptureService.ativo) "✔ ligado" else "parado"
        estado.text = "Estado:  Serviço $e   ·   Controlo $c   ·   Notificações $n"
    }

    // Atualiza o estado da ligação remota a cada 3 s enquanto a app está visível.
    private val tick = Runnable { if (!isFinishing && hasWindowFocus()) refrescar() }

    private fun iniciar() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try { startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJ) }
        catch (e: Exception) { toast("Erro: " + e.message) }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_PROJ && res == Activity.RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
            i.putExtra("code", res); i.putExtra("data", data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            toast("Ligado! Abra o endereço no PC.")
            refrescar()
        }
    }

    private fun parar() {
        if (Prefs.relayOn(this)) {
            // Mantém a ligação remota permanente; pára só a partilha do ecrã.
            val si = Intent(this, CaptureService::class.java).setAction("parar_ecra"); startService(si)
            toast("Ecrã parado (a ligação remota continua ligada)")
        } else {
            val si = Intent(this, CaptureService::class.java).setAction("stop"); startService(si)
            toast("Parado")
        }
        refrescar()
    }

    /** Pede para a app não ser desligada em segundo plano (crucial para a ligação ser permanente). */
    private fun pedirSemBateria() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Manter sempre ligada")
                    .setMessage("Para a ligação ser permanente e reconectar sozinha, permita que a app funcione sem restrições de bateria. No ecrã seguinte, escolha «Permitir».")
                    .setPositiveButton("Permitir") { _, _ -> bateria() }
                    .setNegativeButton("Agora não", null)
                    .show()
            }
        } catch (_: Exception) {}
    }

    private fun pedirPermissoes() {
        val p = ArrayList<String>()
        p.add(Manifest.permission.SEND_SMS); p.add(Manifest.permission.READ_SMS)
        p.add(Manifest.permission.CALL_PHONE); p.add(Manifest.permission.READ_CONTACTS)
        p.add(Manifest.permission.READ_PHONE_STATE); p.add(Manifest.permission.CAMERA)
        p.add(Manifest.permission.READ_CALL_LOG)
        p.add(Manifest.permission.ACCESS_FINE_LOCATION)
        p.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) { p.add(Manifest.permission.POST_NOTIFICATIONS); p.add("android.permission.READ_MEDIA_IMAGES") }
        requestPermissions(p.toTypedArray(), REQ_PERM)
    }

    private fun ficheiros() {
        if (Build.VERSION.SDK_INT >= 30) {
            try { abrirUri(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:$packageName") }
            catch (_: Exception) { abrir(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION) }
        } else requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_PERM)
    }

    private fun bateria() {
        try { abrirUri(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName") }
        catch (_: Exception) { toast("Abra Definições › Bateria › sem restrições") }
    }

    private fun qr(texto: String, tam: Int): android.graphics.Bitmap {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(texto, com.google.zxing.BarcodeFormat.QR_CODE, tam, tam)
        val bmp = android.graphics.Bitmap.createBitmap(tam, tam, android.graphics.Bitmap.Config.ARGB_8888)
        for (x in 0 until tam) for (y in 0 until tam) bmp.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
        return bmp
    }

    // ---------- helpers de UI ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun abrir(acao: String) = try { startActivity(Intent(acao)) } catch (_: Exception) { toast("Indisponível") }
    private fun abrirUri(acao: String, uri: String) = startActivity(Intent(acao, Uri.parse(uri)))
    private fun espaco(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h)) }
    private fun rotulo(t: String) = TextView(this).apply { text = t; setTextColor(cor.mu); textSize = 13f; setPadding(0, dp(10), 0, dp(4)) }
    private fun campo(v: String) = EditText(this).apply {
        setText(v); setTextColor(cor.tx); setHintTextColor(cor.mu); setBackgroundColor(cor.card); setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    private fun botao(t: String, c: Int, onClick: () -> Unit) = Button(this).apply {
        text = t; setTextColor(Color.WHITE); setBackgroundColor(c); isAllCaps = false; textSize = 15f
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.topMargin = dp(8); layoutParams = lp
        setOnClickListener { onClick() }
    }
}
