package com.fusaotech.studio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** FusãoTechX Mz • Funções do telefone usadas pelo servidor. */
object Device {

    fun estado(c: Context): JSONObject {
        val o = JSONObject()
        try {
            val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            o.put("bateria", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            o.put("carregar", bm.isCharging)
        } catch (_: Exception) {}
        try {
            val s = StatFs(Environment.getExternalStorageDirectory().path)
            o.put("total", s.totalBytes)
            o.put("livre", s.availableBytes)
        } catch (_: Exception) {}
        try {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            o.put("ramTotal", mi.totalMem)
            o.put("ramLivre", mi.availMem)
        } catch (_: Exception) {}
        o.put("nome", Prefs.name(c))
        o.put("modelo", android.os.Build.MODEL)
        o.put("marca", android.os.Build.MANUFACTURER)
        o.put("android", android.os.Build.VERSION.RELEASE)
        o.put("controlo", ControlService.inst != null)
        o.put("notif", NotifService.inst != null)
        return o
    }

    @Suppress("MissingPermission", "DEPRECATION", "HardwareIds")
    fun sims(c: Context): JSONArray {
        val arr = JSONArray()
        try {
            val sm = c.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
            val lista = sm.activeSubscriptionInfoList
            if (lista != null) {
                for (s in lista) {
                    val o = JSONObject()
                    o.put("slot", s.simSlotIndex + 1)
                    o.put("operadora", s.carrierName?.toString() ?: "")
                    o.put("nome", s.displayName?.toString() ?: "")
                    o.put("numero", try { s.number ?: "" } catch (_: Exception) { "" })
                    o.put("pais", s.countryIso?.uppercase() ?: "")
                    o.put("roaming", sm.isNetworkRoaming(s.subscriptionId))
                    arr.put(o)
                }
            }
        } catch (_: Exception) {}
        // Dados adicionais (rede, sinal) do SIM principal
        try {
            val tm = c.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            if (arr.length() == 0 && tm.simState == android.telephony.TelephonyManager.SIM_STATE_READY) {
                val o = JSONObject(); o.put("slot", 1); o.put("operadora", tm.networkOperatorName ?: ""); o.put("numero", "")
                arr.put(o)
            }
        } catch (_: Exception) {}
        return arr
    }

    fun ligar(c: Context, numero: String) {
        try {
            val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + numero))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i)
        } catch (_: Exception) {
            try {
                val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + numero))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(i)
            } catch (_: Exception) {}
        }
    }

    fun enviarSms(c: Context, numero: String, texto: String): Boolean {
        return try {
            val sm = if (android.os.Build.VERSION.SDK_INT >= 31)
                c.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
            val partes = sm.divideMessage(texto)
            if (partes.size > 1) sm.sendMultipartTextMessage(numero, null, partes, null, null)
            else sm.sendTextMessage(numero, null, texto, null, null)
            true
        } catch (_: Exception) { false }
    }

    fun smsInbox(c: Context, limite: Int = 60): JSONArray {
        val arr = JSONArray()
        try {
            val cur = c.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null, null, Telephony.Sms.DATE + " DESC"
            )
            cur?.use {
                var n = 0
                while (it.moveToNext() && n < limite) {
                    val o = JSONObject()
                    o.put("de", it.getString(0) ?: "")
                    o.put("texto", it.getString(1) ?: "")
                    o.put("data", it.getLong(2))
                    o.put("enviada", it.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT)
                    arr.put(o); n++
                }
            }
        } catch (_: Exception) {}
        return arr
    }

    fun contactos(c: Context): JSONArray {
        val arr = JSONArray()
        try {
            val cur = c.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            val vistos = HashSet<String>()
            cur?.use {
                while (it.moveToNext()) {
                    val num = (it.getString(1) ?: "").replace(" ", "").replace("-", "")
                    if (num.isEmpty() || !vistos.add(num)) continue
                    val o = JSONObject()
                    o.put("nome", it.getString(0) ?: num)
                    o.put("num", num)
                    arr.put(o)
                }
            }
        } catch (_: Exception) {}
        return arr
    }

    fun apps(c: Context): JSONArray {
        val arr = JSONArray()
        try {
            val pm = c.packageManager
            val apps = pm.getInstalledApplications(0)
            for (a in apps) {
                if ((a.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (a.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue
                val o = JSONObject()
                o.put("pkg", a.packageName)
                o.put("nome", pm.getApplicationLabel(a).toString())
                arr.put(o)
            }
        } catch (_: Exception) {}
        return arr
    }

    fun abrirApp(c: Context, pkg: String) {
        try {
            val i = c.packageManager.getLaunchIntentForPackage(pkg)
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(i) }
        } catch (_: Exception) {}
    }

    fun abrirUrl(c: Context, url: String) {
        try {
            var u = url
            if (!u.matches(Regex("^[a-z]+://.*"))) u = "https://$u"
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(u)); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(i)
        } catch (_: Exception) {}
    }

    fun abrirCamera(c: Context) {
        try {
            val i = Intent("android.media.action.STILL_IMAGE_CAMERA"); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(i)
        } catch (_: Exception) {}
    }

    fun guardarImagem(c: Context, bytes: ByteArray, nome: String): Boolean {
        return try {
            val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "FusaoTechX")
            dir.mkdirs()
            val f = File(dir, nome); f.writeBytes(bytes)
            c.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f)))
            true
        } catch (_: Exception) { false }
    }

    fun whatsapp(c: Context, numero: String, texto: String) {
        var n = numero.replace("+", "").replace(" ", "")
        if (n.length == 9 && n.startsWith("8")) n = "258$n"
        abrirUrl(c, "https://api.whatsapp.com/send?phone=$n&text=" + Uri.encode(texto))
    }

    // ---------- Ficheiros ----------
    private fun raiz(): File = Environment.getExternalStorageDirectory()

    fun listar(p: String?): JSONObject {
        val dir = File(if (p.isNullOrEmpty()) raiz().path else p)
        val o = JSONObject(); o.put("dir", dir.path)
        val arr = JSONArray()
        try {
            val fs = dir.listFiles()
            if (fs != null) {
                fs.sortWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                for (f in fs) {
                    if (f.name.startsWith(".")) continue
                    val j = JSONObject()
                    j.put("n", f.name); j.put("p", f.path); j.put("d", f.isDirectory)
                    j.put("s", if (f.isDirectory) 0 else f.length()); j.put("m", f.lastModified() / 1000)
                    val dot = f.name.lastIndexOf('.')
                    j.put("e", if (!f.isDirectory && dot >= 0) f.name.substring(dot + 1).lowercase() else "")
                    arr.put(j)
                }
            }
        } catch (_: Exception) {}
        o.put("itens", arr)
        return o
    }
}
