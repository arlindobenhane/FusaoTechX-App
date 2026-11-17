package com.fusaotech.studio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*

/**
 * FusãoTechX Mz • Ecrã-isco (disfarce). Parece uma app de Wi-Fi: procura redes a sério e
 * mostra-as. Um ladrão que a abra vê só isto. Para entrar no painel real, escreve FUSAO
 * na caixa de pesquisa (ou toca 7 vezes no título "Wi-Fi").
 */
class DecoyActivity : Activity() {

    private val fundo = Color.parseColor("#F2F3F5")
    private val azul = Color.parseColor("#1A73E8")
    private val txt = Color.parseColor("#202124")
    private val mu = Color.parseColor("#5F6368")
    private lateinit var lista: LinearLayout
    private var toques = 0

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.statusBarColor = Color.WHITE
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(fundo) }

        // Barra de topo
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE); setPadding(dp(18), dp(18), dp(18), dp(14)); gravity = Gravity.CENTER_VERTICAL }
        val titulo = TextView(this).apply { text = "Wi-Fi"; setTextColor(txt); textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        titulo.setOnClickListener { if (++toques >= 7) abrirReal() }
        top.addView(titulo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this).apply { isChecked = true }
        top.addView(sw)
        root.addView(top)

        // Caixa de pesquisa (o desbloqueio secreto: escrever FUSAO)
        val busca = EditText(this).apply {
            hint = "Procurar rede"; setHintTextColor(mu); setTextColor(txt); setBackgroundColor(Color.WHITE)
            setPadding(dp(18), dp(12), dp(18), dp(12)); textSize = 15f
        }
        busca.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) { if ((e?.toString() ?: "").trim().equals("FUSAO", true)) abrirReal() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        root.addView(busca)

        root.addView(View(this).apply { setBackgroundColor(Color.parseColor("#E0E0E0")); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)) })

        val sc = ScrollView(this)
        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        sc.addView(lista)
        root.addView(sc, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        procurar()
    }

    override fun onResume() { super.onResume(); procurar() }

    private fun procurar() {
        lista.removeAllViews()
        lista.addView(linhaInfo("A procurar redes…"))
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            try { @Suppress("DEPRECATION") wm.startScan() } catch (_: Exception) {}
            Handler(Looper.getMainLooper()).postDelayed({ mostrar(wm) }, 1200)
        } catch (_: Exception) { lista.removeAllViews(); lista.addView(linhaInfo("Ative o Wi-Fi para procurar redes.")) }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun mostrar(wm: WifiManager) {
        lista.removeAllViews()
        val vistos = HashSet<String>()
        val redes = ArrayList<Pair<String, Int>>()
        try {
            val atual = wm.connectionInfo?.ssid?.trim('"') ?: ""
            if (atual.isNotEmpty() && atual != "<unknown ssid>") { redes.add(atual to 4); vistos.add(atual) }
            for (r in (wm.scanResults ?: emptyList())) {
                val ssid = (r.SSID ?: "").trim()
                if (ssid.isEmpty() || !vistos.add(ssid)) continue
                redes.add(ssid to WifiManager.calculateSignalLevel(r.level, 5))
            }
        } catch (_: Exception) {}
        if (redes.isEmpty()) { lista.addView(linhaInfo("Nenhuma rede encontrada. Toque para procurar de novo.")); lista.setOnClickListener { procurar() }; return }
        for ((i, rede) in redes.withIndex()) {
            lista.addView(linhaRede(rede.first, rede.second, i == 0))
            lista.addView(View(this).apply { setBackgroundColor(Color.parseColor("#EEEEEE")); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)) })
        }
    }

    private fun linhaRede(ssid: String, nivel: Int, ligada: Boolean): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(18), dp(16), dp(18), dp(16)); gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.WHITE) }
        l.addView(TextView(this).apply { text = "📶"; textSize = 18f }, LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.WRAP_CONTENT))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = ssid; setTextColor(txt); textSize = 16f })
        col.addView(TextView(this).apply { text = if (ligada) "Ligado" else "Protegida · sinal " + nivel + "/5"; setTextColor(if (ligada) azul else mu); textSize = 12.5f })
        l.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        l.addView(TextView(this).apply { text = "🔒"; textSize = 15f })
        l.setOnClickListener { Toast.makeText(this, "Introduza a palavra-passe da rede", Toast.LENGTH_SHORT).show() }
        return l
    }

    private fun linhaInfo(t: String) = TextView(this).apply { text = t; setTextColor(mu); textSize = 14f; setPadding(dp(18), dp(18), dp(18), dp(18)) }

    private fun abrirReal() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
