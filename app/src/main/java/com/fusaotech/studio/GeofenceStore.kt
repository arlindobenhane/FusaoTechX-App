package com.fusaotech.studio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.*

/**
 * Geofencing simples sem Google Play Services.
 * Zonas guardadas em filesDir/geofences.json.
 * Eventos (entrou/saiu) guardados em filesDir/geofence_events.json.
 * O Watchdog verifica a localização a cada ciclo (~15 min) e dispara alertas.
 */
object GeofenceStore {

    private fun fZonas(c: Context) = File(c.filesDir, "geofences.json")
    private fun fEventos(c: Context) = File(c.filesDir, "geofence_events.json")

    // ── Zonas ──────────────────────────────────────────────────────────────

    fun zonas(c: Context): JSONArray = try { JSONArray(fZonas(c).readText()) } catch (_: Exception) { JSONArray() }

    fun adicionarZona(c: Context, nome: String, lat: Double, lng: Double, raioM: Double): String {
        val id = "gz_" + System.currentTimeMillis()
        val arr = zonas(c)
        arr.put(JSONObject()
            .put("id", id).put("nome", nome)
            .put("lat", lat).put("lng", lng)
            .put("raio", raioM)
            .put("ativa", true)
            .put("criada", System.currentTimeMillis()))
        fZonas(c).writeText(arr.toString())
        return id
    }

    fun removerZona(c: Context, id: String) {
        val arr = zonas(c)
        val novo = JSONArray()
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (o.optString("id") != id) novo.put(o) }
        fZonas(c).writeText(novo.toString())
    }

    fun toggleZona(c: Context, id: String, ativa: Boolean) {
        val arr = zonas(c)
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (o.optString("id") == id) o.put("ativa", ativa) }
        fZonas(c).writeText(arr.toString())
    }

    // ── Eventos ────────────────────────────────────────────────────────────

    fun eventos(c: Context, limite: Int = 200): JSONArray {
        return try {
            val arr = JSONArray(fEventos(c).readText())
            if (arr.length() <= limite) return arr
            val novo = JSONArray()
            for (i in arr.length() - limite until arr.length()) novo.put(arr.getJSONObject(i))
            novo
        } catch (_: Exception) { JSONArray() }
    }

    private fun guardarEvento(c: Context, o: JSONObject) {
        try {
            val arr = try { JSONArray(fEventos(c).readText()) } catch (_: Exception) { JSONArray() }
            arr.put(o)
            // Mantém só os 500 mais recentes
            if (arr.length() > 500) {
                val novo = JSONArray()
                for (i in arr.length() - 500 until arr.length()) novo.put(arr.getJSONObject(i))
                fEventos(c).writeText(novo.toString())
            } else fEventos(c).writeText(arr.toString())
        } catch (_: Exception) {}
    }

    // ── Verificação ────────────────────────────────────────────────────────

    private const val PREFS_ESTADO = "ftx_gz_estado"

    /** Verifica a posição atual contra todas as zonas ativas. Chama-se no Watchdog. */
    fun verificar(c: Context) {
        try {
            val arr = zonas(c)
            if (arr.length() == 0) return
            val loc = Device.localizacao(c, 6000)
            if (loc.has("erro")) return
            val lat = loc.getDouble("lat"); val lng = loc.getDouble("lng")
            val ts = loc.optLong("quando", System.currentTimeMillis())
            val sp = c.getSharedPreferences(PREFS_ESTADO, Context.MODE_PRIVATE)

            for (i in 0 until arr.length()) {
                val z = arr.getJSONObject(i)
                if (!z.optBoolean("ativa", true)) continue
                val id = z.optString("id")
                val dist = distancia(lat, lng, z.getDouble("lat"), z.getDouble("lng"))
                val dentro = dist <= z.getDouble("raio")
                val estavaDentro = sp.getBoolean("dentro_$id", false)

                if (dentro != estavaDentro) {
                    sp.edit().putBoolean("dentro_$id", dentro).apply()
                    val tipo = if (dentro) "entrou" else "saiu"
                    guardarEvento(c, JSONObject()
                        .put("id", "ev_$ts")
                        .put("zona_id", id)
                        .put("zona_nome", z.optString("nome"))
                        .put("tipo", tipo)
                        .put("lat", lat).put("lng", lng)
                        .put("distancia", kotlin.math.roundToInt(dist))
                        .put("ts", ts))
                    // SEM notificação no telefone — evento fica só no log para o admin ler.
                }
            }
        } catch (_: Exception) {}
    }

    // ── Distância Haversine ────────────────────────────────────────────────

    fun distancia(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dl = Math.toRadians(lat2 - lat1); val dg = Math.toRadians(lng2 - lng1)
        val a = sin(dl / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dg / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

}
