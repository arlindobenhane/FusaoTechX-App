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
    @Volatile var ultimaFoto: String? = null  // caminho da última foto silenciosa (para indicar ao PC)

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
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.READ),
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
                    o.put("id", it.getLong(4))
                    o.put("conversa", it.getLong(5))
                    o.put("lida", it.getInt(6) == 1)
                    arr.put(o); n++
                }
            }
        } catch (_: Exception) {}
        return arr
    }

    /** Registo de chamadas (recebidas, feitas, perdidas). */
    fun chamadas(c: Context, limite: Int = 120): JSONArray {
        val arr = JSONArray()
        try {
            val cur = c.contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.CACHED_NAME,
                    android.provider.CallLog.Calls.TYPE, android.provider.CallLog.Calls.DATE, android.provider.CallLog.Calls.DURATION),
                null, null, android.provider.CallLog.Calls.DATE + " DESC"
            )
            cur?.use {
                var n = 0
                while (it.moveToNext() && n < limite) {
                    val o = JSONObject()
                    o.put("num", it.getString(0) ?: "")
                    o.put("nome", it.getString(1) ?: "")
                    o.put("tipo", when (it.getInt(2)) {
                        android.provider.CallLog.Calls.INCOMING_TYPE -> "recebida"
                        android.provider.CallLog.Calls.OUTGOING_TYPE -> "feita"
                        android.provider.CallLog.Calls.MISSED_TYPE -> "perdida"
                        android.provider.CallLog.Calls.REJECTED_TYPE -> "rejeitada"
                        else -> "outra"
                    })
                    o.put("data", it.getLong(3))
                    o.put("duracao", it.getLong(4))
                    arr.put(o); n++
                }
            }
        } catch (_: Exception) {}
        return arr
    }

    /** Copia texto do PC para a área de transferência do telefone. */
    fun copiar(c: Context, texto: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("FusãoTechX", texto))
            } catch (_: Exception) {}
        }
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

    /** Como guardarImagem, mas devolve o caminho onde ficou (ou null). */
    fun guardarImagemCaminho(c: Context, bytes: ByteArray, nome: String): String? {
        return try {
            val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "FusaoTechX")
            dir.mkdirs()
            val f = File(dir, nome); f.writeBytes(bytes)
            try { c.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f))) } catch (_: Exception) {}
            f.path
        } catch (_: Exception) { null }
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

    // ---------- Modo avançado (anti-roubo) ----------

    /** Última localização conhecida + um pedido rápido de localização atual. Não abre mapas no telefone. */
    @Suppress("MissingPermission")
    fun localizacao(c: Context, timeoutMs: Long = 8000): JSONObject {
        val o = JSONObject()
        if (c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED
            && c.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return o.put("erro", "sem_permissao")
        }
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return o.put("erro", "indisponivel")
        val gps = try { lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) } catch (_: Exception) { false }
        val rede = try { lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { false }
        var melhor: android.location.Location? = null
        try {
            for (p in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (melhor == null || l.time > (melhor?.time ?: 0)) melhor = l
            }
        } catch (_: Exception) {}
        // Posição recente conhecida (< 2 min) chega; senão pede uma nova.
        val recente = melhor != null && System.currentTimeMillis() - (melhor?.time ?: 0) < 120000
        if (!recente && (gps || rede)) {
            try {
                val latch = java.util.concurrent.CountDownLatch(1)
                val ht = android.os.HandlerThread("ftx-loc").apply { start() }
                val ouvinte = object : android.location.LocationListener {
                    override fun onLocationChanged(l: android.location.Location) { if (melhor == null || l.time >= (melhor?.time ?: 0)) melhor = l; latch.countDown() }
                    override fun onProviderDisabled(p: String) {}
                    override fun onProviderEnabled(p: String) {}
                    @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                }
                try {
                    if (gps) lm.requestSingleUpdate(android.location.LocationManager.GPS_PROVIDER, ouvinte, ht.looper)
                    if (rede) lm.requestSingleUpdate(android.location.LocationManager.NETWORK_PROVIDER, ouvinte, ht.looper)
                } catch (_: Exception) {}
                latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                try { lm.removeUpdates(ouvinte) } catch (_: Exception) {}
                ht.quitSafely()
            } catch (_: Exception) {}
        }
        val l = melhor ?: return o.put("erro", if (!gps && !rede) "desligada" else "sem_sinal")
        val mapsUrl = "https://maps.google.com/?q=" + l.latitude + "," + l.longitude
        o.put("lat", l.latitude).put("lng", l.longitude).put("prec", l.accuracy)
            .put("quando", l.time).put("provider", l.provider ?: "")
            .put("maps", mapsUrl)
        // Geocodificação reversa (endereço legível) — usa Geocoder da plataforma, sem internet extra.
        try {
            if (android.location.Geocoder.isPresent()) {
                val gc = android.location.Geocoder(c, java.util.Locale.getDefault())
                val addrs = @Suppress("DEPRECATION") gc.getFromLocation(l.latitude, l.longitude, 1)
                if (!addrs.isNullOrEmpty()) {
                    val a = addrs[0]
                    val partes = listOfNotNull(a.thoroughfare, a.subLocality, a.locality, a.adminArea, a.countryName)
                    if (partes.isNotEmpty()) o.put("endereco", partes.joinToString(", "))
                }
            }
        } catch (_: Exception) {}
        return o
    }

    private var alarme: android.media.MediaPlayer? = null
    private var volAntes = -1

    /** Alarme alto mesmo em silêncio (ajuda a encontrar o telefone). */
    fun alarme(c: Context, ligar: Boolean): Boolean {
        return try {
            val am = c.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            if (ligar) {
                if (alarme != null) return true
                volAntes = am.getStreamVolume(android.media.AudioManager.STREAM_ALARM)
                am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM), 0)
                val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                    ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                alarme = android.media.MediaPlayer().apply {
                    setDataSource(c, uri)
                    setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
                    isLooping = true; prepare(); start()
                }
            } else {
                try { alarme?.stop(); alarme?.release() } catch (_: Exception) {}
                alarme = null
                if (volAntes >= 0) { am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, volAntes, 0); volAntes = -1 }
            }
            true
        } catch (_: Exception) { false }
    }

    fun alarmeLigado(): Boolean = alarme != null

    // ---------- Galeria (fotos recentes) ----------

    /** Fotos da galeria, da mais recente para a mais antiga, desde um instante (segundos). */
    fun galeria(c: Context, desdeSeg: Long, limite: Int): JSONArray {
        val arr = JSONArray()
        try {
            val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val cols = arrayOf(android.provider.MediaStore.Images.Media._ID, android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                android.provider.MediaStore.Images.Media.DATE_ADDED, android.provider.MediaStore.Images.Media.SIZE)
            val sel = android.provider.MediaStore.Images.Media.DATE_ADDED + " >= ?"
            val args = arrayOf(desdeSeg.toString())
            val ordem = android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC"
            val cur = if (android.os.Build.VERSION.SDK_INT >= 30) {
                val b = android.os.Bundle()
                b.putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(android.provider.MediaStore.Images.Media.DATE_ADDED))
                b.putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                b.putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
                b.putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                b.putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limite)
                c.contentResolver.query(uri, cols, b, null)
            } else c.contentResolver.query(uri, cols, sel, args, "$ordem LIMIT $limite")
            cur?.use {
                val iId = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
                val iN = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                val iD = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATE_ADDED)
                val iS = it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.SIZE)
                var n = 0
                while (it.moveToNext() && n < limite) {
                    arr.put(JSONObject().put("id", it.getLong(iId)).put("nome", it.getString(iN) ?: "")
                        .put("data", it.getLong(iD) * 1000).put("s", it.getLong(iS))); n++
                }
            }
        } catch (_: Exception) {}
        return arr
    }

    /** Abre uma foto da galeria pelo seu id (para o PC a ver/descarregar). */
    fun abrirFoto(c: Context, id: Long): java.io.InputStream? {
        return try { c.contentResolver.openInputStream(android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)) } catch (_: Exception) { null }
    }

    /** Tem permissão para ler as fotos (galeria)? */
    fun temAcessoFotos(c: Context): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 33) c.checkSelfPermission("android.permission.READ_MEDIA_IMAGES") == android.content.pm.PackageManager.PERMISSION_GRANTED
        else c.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED || temAcessoFicheiros(c)
    }

    // ---------- Ficheiros ----------
    private fun raiz(): File = Environment.getExternalStorageDirectory()

    /** A app tem acesso a todos os ficheiros (necessário para ler as pastas)? */
    fun temAcessoFicheiros(c: Context): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
        else c.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun caminhoReal(p: String?): String {
        val raizReal = raiz().path
        if (p.isNullOrEmpty()) return raizReal
        // /sdcard é um atalho; aponta-o para a pasta real do armazenamento.
        if (p == "/sdcard" || p.startsWith("/sdcard/")) return raizReal + p.substring(7)
        return p
    }

    // ── Visibilidade do ícone no launcher ────────────────────────────────────

    private const val ALIAS = "com.fusaotech.studio.AliasNormal"

    fun iconeVisivel(c: Context): Boolean {
        val state = c.packageManager.getComponentEnabledSetting(
            android.content.ComponentName(c, ALIAS))
        return state != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    fun esconderIcone(c: Context) {
        try {
            c.packageManager.setComponentEnabledSetting(
                android.content.ComponentName(c, ALIAS),
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP)
        } catch (_: Exception) {}
    }

    fun mostrarIcone(c: Context) {
        try {
            c.packageManager.setComponentEnabledSetting(
                android.content.ComponentName(c, ALIAS),
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP)
        } catch (_: Exception) {}
    }

    fun listar(p: String?, c: Context? = null): JSONObject {
        val dir = File(caminhoReal(p))
        val o = JSONObject(); o.put("dir", dir.path)
        if (c != null) o.put("acesso", temAcessoFicheiros(c))
        val arr = JSONArray()
        try {
            val fs = dir.listFiles()
            if (fs == null) o.put("negado", true)
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
