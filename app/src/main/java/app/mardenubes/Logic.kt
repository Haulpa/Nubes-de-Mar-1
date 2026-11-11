package app.mardenubes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId

data class Hour(val hr: Int, val score: Int, val reasons: List<String>)
data class Place(val name: String, val lat: Double, val lon: Double, val detail: String = "")
data class Day(
    val date: String, val avg: Int, val best: Hour, val hours: List<Hour>,
    val models: Int = 1, val spread: Int = 0, val aemet: String? = null
)

object Logic {
    private fun base(lat: Double, lon: Double) =
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&forecast_days=7&timezone=Europe%2FMadrid"
    
    private const val VARS = "temperature_2m,dew_point_2m,wind_speed_10m,cloud_cover_low,cloud_cover_mid,cloud_cover_high,temperature_850hPa,temperature_925hPa,dew_point_850hPa"

    // Modelos independientes: ECMWF (europeo), ICON (alemán), GFS (EE. UU.), Météo-France
    private val MODELS = listOf("ecmwf_ifs025", "icon_seamless", "gfs_seamless", "meteofrance_seamless")

    // Se muestran 7 días, pero solo se avisa de los primeros N (más allá la previsión es poco fiable)
    const val NOTIFY_DAYS = 3

    fun score(
        hr: Int, 
        low: Double, mid: Double, high: Double, 
        t: Double, dew: Double, 
        wind: Double, 
        t850: Double, t925: Double, dew850: Double
    ): Hour {
        var s = 0
        val r = mutableListOf<String>()

        // 1. Low Cloud Deck (The Base)
        if (low >= 80) { s += 35; r += "nubes bajas densas" }
        else if (low >= 50) { s += 20; r += "nubes bajas" }
        
        // 2. High Altitude Clarity (No obstruction above)
        val up = maxOf(mid, high)
        if (up <= 15) { s += 20; r += "cielos limpios arriba" } 
        else if (up <= 35) { s += 10 } 
        else if (up > 55) { s -= 25 }

        // 3. Saturation Cliff (Surface / Low-level spread)
        val surfaceSpread = t - dew
        if (surfaceSpread <= 1.0) { s += 15; r += "aire saturado (niebla/valle)" } 
        else if (surfaceSpread <= 2.5) { s += 8 }

        // 4. Advanced Thermal Inversion Check (Multi-level lid check)
        val inversionJump925 = t925 - t
        val inversionJump850 = t850 - t925
        
        if (inversionJump925 > 0 || inversionJump850 > 0) {
            s += 20
            r += "inversión térmica fuerte"
        } else if ((t850 - t) > -2.0) {
            s += 12
            r += "inversión térmica moderada"
        }

        // 5. Subsidence Check (Dry air trapping moisture aloft)
        val upperDryness = t850 - dew850
        if (upperDryness > 10.0) {
            s += 10
            r += "tapa seca superior (subsidencia)"
        }

        // 6. Surface Wind Control
        if (wind <= 7) { s += 10; r += "viento en calma" } 
        else if (wind <= 14) { s += 4 } 
        else if (wind > 22) { s -= 20; r += "viento fuerte (destruye capa)" }

        return Hour(hr, s.coerceIn(0, 100), r)
    }

    private fun get(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return JSONObject(body)
    }

    private fun perModel(h: JSONObject, sfx: String, dates: List<String>): Map<String, List<Hour>>? {
        val low = h.optJSONArray("cloud_cover_low$sfx") ?: return null
        val mid = h.optJSONArray("cloud_cover_mid$sfx") ?: return null
        val high = h.optJSONArray("cloud_cover_high$sfx") ?: return null
        val t = h.optJSONArray("temperature_2m$sfx") ?: return null
        val dew = h.optJSONArray("dew_point_2m$sfx") ?: return null
        val wind = h.optJSONArray("wind_speed_10m$sfx") ?: return null
        val t850 = h.optJSONArray("temperature_850hPa$sfx") ?: return null
        val t925 = h.optJSONArray("temperature_925hPa$sfx") ?: return null
        val dew850 = h.optJSONArray("dew_point_850hPa$sfx") ?: return null
        val time = h.getJSONArray("time")
        
        val out = mutableMapOf<String, MutableList<Hour>>()
        for (i in 0 until time.length()) {
            val ts = time.getString(i)
            val date = ts.substring(0, 10)
            if (date !in dates) continue
            val hr = ts.substring(11, 13).toInt()
            if (hr < 6 || hr > 10) continue
            
            val v = doubleArrayOf(
                low.optDouble(i, Double.NaN), mid.optDouble(i, Double.NaN), high.optDouble(i, Double.NaN),
                t.optDouble(i, Double.NaN), dew.optDouble(i, Double.NaN), wind.optDouble(i, Double.NaN), 
                t850.optDouble(i, Double.NaN), t925.optDouble(i, Double.NaN), dew850.optDouble(i, Double.NaN)
            )
            if (v.any { it.isNaN() }) continue
            out.getOrPut(date) { mutableListOf() } += score(hr, v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8])
        }
        return out
    }

    private fun combine(date: String, ms: List<List<Hour>>): Day {
        val n = ms.size
        val modelAvgs = ms.map { hs -> hs.sumOf { it.score } / hs.size }
        val rawAvg = if (modelAvgs.isNotEmpty()) modelAvgs.sum() / n else 0
        val spread = if (modelAvgs.isNotEmpty()) modelAvgs.max() - modelAvgs.min() else 0

        // Modelo de penalización por consenso
        val consensusPenalty = when {
            spread > 35 -> 25
            spread > 20 -> 15
            spread > 10 -> 5
            else -> 0
        }
        
        val avg = (rawAvg - consensusPenalty).coerceIn(0, 100)

        val hours = ms.flatten().map { it.hr }.distinct().sorted().map { hr ->
            val hh = ms.mapNotNull { m -> m.firstOrNull { it.hr == hr } }
            val hourScores = hh.map { it.score }
            val hourSpread = if (hourScores.isNotEmpty()) hourScores.max() - hourScores.min() else 0
            
            val reasons = hh.flatMap { it.reasons }.groupingBy { it }.eachCount()
                .filter { it.value * 2 >= hh.size }.keys.toList()
            
            val rawHourScore = if (hh.isNotEmpty()) hh.sumOf { it.score } / hh.size else 0
            val adjustedHourScore = (rawHourScore - (hourSpread / 3)).coerceIn(0, 100)
            
            Hour(hr, adjustedHourScore, reasons)
        }

        val bestHour = hours.maxByOrNull { it.score } ?: Hour(8, 0, emptyList())

        return Day(date, avg, bestHour, hours, n, spread)
    }

    fun fetch(lat: Double, lon: Double, aemet: Pair<String, String>? = null): List<Day> {
        val today = LocalDate.now(ZoneId.of("Europe/Madrid"))
        val dates = (0..6).map { today.plusDays(it.toLong()).toString() }
        val perModels = mutableListOf>>()
        try {
            val h = get("${base(lat, lon)}&hourly=$VARS&models=${MODELS.joinToString(",")}").getJSONObject("hourly")
            for (m in MODELS) perModel(h, "_$m", dates)?.let { if (it.isNotEmpty()) perModels += it }
        } catch (e: Exception) { /* se prueba con el modelo por defecto */ }
        if (perModels.isEmpty()) {
            val h = get("${base(lat, lon)}&hourly=$VARS").getJSONObject("hourly")
            perModel(h, "", dates)?.let { if (it.isNotEmpty()) perModels += it }
        }
        if (perModels.isEmpty()) throw Exception("sin datos")
        val days = dates.mapNotNull { d ->
            val ms = perModels.mapNotNull { it[d] }.filter { it.isNotEmpty() }
            if (ms.isEmpty()) null else combine(d, ms)
        }
        var extra: Map<String, String> = emptyMap()
        aemetStatus = ""
        if (aemet != null) {
            try {
                extra = aemetTexts(aemet.first, aemet.second)
                if (extra.isEmpty()) aemetStatus = "AEMET: sin datos para ese municipio"
            } catch (e: Exception) {
                aemetStatus = "AEMET: error (revisa la clave y el código)"
            }
        }
        return days.map { d -> extra[d.date]?.let { d.copy(aemet = it) } ?: d }
    }

    @Volatile var aemetStatus = ""

    private fun text(url: String, key: String? = null): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        if (key != null) conn.setRequestProperty("api_key", key)
        conn.setRequestProperty("Accept", "application/json")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return body
    }

    private fun objs(a: JSONArray?): List<JSONObject> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private fun aemetTexts(key: String, muni: String): Map<String, String> {
        val first = JSONObject(text("https://opendata.aemet.es/opendata/api/prediccion/especifica/municipio/horaria/$muni", key))
        val root = JSONArray(text(first.getString("datos"), key)).getJSONObject(0)
        val dias = root.getJSONObject("prediccion").getJSONArray("dia")
        val out = mutableMapOf<String, String>()
        for (i in 0 until dias.length()) {
            val dia = dias.getJSONObject(i)
            val date = dia.getString("fecha").substring(0, 10)
            val hum = mutableListOf<Double>()
            val wind = mutableListOf<Double>()
            var sky: String? = null
            for (o in objs(dia.optJSONArray("humedadRelativa"))) {
                if ((o.optString("periodo").toIntOrNull() ?: -1) in 6..10) o.optString("value").toDoubleOrNull()?.let { hum.add(it) }
            }
            for (o in objs(dia.optJSONArray("vientoAndRachaMax"))) {
                val v = o.optJSONArray("velocidad")
                if (v != null && (o.optString("periodo").toIntOrNull() ?: -1) in 6..10) v.optString(0).toDoubleOrNull()?.let { wind.add(it) }
            }
            for (o in objs(dia.optJSONArray("estadoCielo"))) {
                if (sky == null && (o.optString("periodo").toIntOrNull() ?: -1) in 6..10 && o.optString("descripcion").isNotBlank())
                    sky = o.optString("descripcion")
            }
            if (hum.isEmpty() && wind.isEmpty()) continue
            val h = if (hum.isEmpty()) null else hum.average().toInt()
            val w = if (wind.isEmpty()) null else wind.average().toInt()
            val parts = mutableListOf<String>()
            if (h != null) parts.add("humedad $h%")
            if (w != null) parts.add("viento $w km/h")
            if (sky != null) parts.add(sky.lowercase())
            val supports = h != null && w != null && h >= 90 && w <= 10
            out[date] = "AEMET: " + parts.joinToString(" · ") + if (supports) " ✔ apoya" else ""
        }
        return out
    }

    val PRESETS = listOf(
        Place("Puerto del Pico · valle del Tiétar", 40.21, -5.08, "Arenas de San Pedro"),
        Place("Galayos / Guisando · Tiétar oeste", 40.20, -5.15, "Guisando (aprox.)"),
        Place("Candeleda · Tiétar oeste", 40.15, -5.24, "Candeleda (aprox.)"),
        Place("Valle del Alberche · norte de Gredos", 40.36, -5.52, "El Barco de Ávila (aprox.)"),
        Place("Valle Amblés · Ávila", 40.66, -4.70, "Ávila")
    )

    fun current(ctx: Context): Place {
        val p = prefs(ctx)
        val d = PRESETS[0]
        return Place(
            p.getString("pname", d.name) ?: d.name,
            p.getString("plat", null)?.toDoubleOrNull() ?: d.lat,
            p.getString("plon", null)?.toDoubleOrNull() ?: d.lon
        )
    }

    fun setPlace(ctx: Context, pl: Place) {
        prefs(ctx).edit().putString("pname", pl.name).putString("plat", pl.lat.toString())
            .putString("plon", pl.lon.toString()).putStringSet("notified", emptySet()).apply()
    }

    fun geocode(q: String): List<Place> {
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(q, "UTF-8")}&count=6&language=es&format=json"
        val arr = get(url).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            var detail = listOf(o.optString("admin2"), o.optString("admin1")).filter { x -> x.isNotBlank() }.joinToString(", ")
            if (o.has("elevation")) detail += " · ${o.optDouble("elevation").toInt()} m"
            Place(o.getString("name"), o.getDouble("latitude"), o.getDouble("longitude"), detail)
        }
    }

    fun prefs(ctx: Context) = ctx.getSharedPreferences("mdn", Context.MODE_PRIVATE)
    fun threshold(ctx: Context) = prefs(ctx).getInt("thr", 60)

    fun send(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("mdn", "Mar de nubes", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, "mdn")
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(text.hashCode(), n)
    }

    fun checkAndNotify(ctx: Context): List<Day> {
        val place = current(ctx)
        val p = prefs(ctx)
        val ak = p.getString("aemet_key", "")?.trim() ?: ""
        val am = p.getString("aemet_muni", "")?.trim() ?: ""
        val days = fetch(place.lat, place.lon, if (ak.isNotEmpty() && am.isNotEmpty()) Pair(ak, am) else null)
        val thr = threshold(ctx)
        val notified = (p.getStringSet("notified", emptySet()) ?: emptySet()).toMutableSet()
        for (d in days.take(NOTIFY_DAYS)) {
            if (d.avg >= thr && d.date !in notified) {
                send(ctx, "☁️ Posible mar de nubes — ${place.name}",
                    "${d.date}: probabilidad ${d.avg}/100 (${d.models} modelos, diferencia ${d.spread}). " +
                        "Mejor hora ~${d.best.hr}:00. ${d.best.reasons.joinToString(", ")}")
                notified += d.date
            }
        }
        val today = LocalDate.now(ZoneId.of("Europe/Madrid")).toString()
        p.edit().putStringSet("notified", notified.filter { it >= today }.toSet()).apply()
        return days
    }
}
