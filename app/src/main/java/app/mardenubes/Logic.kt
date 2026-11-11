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

data class Hour(val hr: Int, val score: Int, val reasons: List<String>, val top: Int? = null)
data class Place(val name: String, val lat: Double, val lon: Double, val detail: String = "")
data class Day(
    val date: String, val avg: Int, val best: Hour, val hours: List<Hour>,
    val models: Int = 1, val spread: Int = 0, val aemet: String? = null
)

object Logic {
    private fun base(lat: Double, lon: Double) =
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&forecast_days=7&timezone=Europe%2FMadrid"
    private const val VARS = "temperature_2m,dew_point_2m,wind_speed_10m,cloud_cover_low,cloud_cover_mid,cloud_cover_high,temperature_850hPa,precipitation," +
        "temperature_950hPa,temperature_925hPa,temperature_900hPa," +
        "relative_humidity_950hPa,relative_humidity_925hPa,relative_humidity_900hPa,relative_humidity_850hPa," +
        "geopotential_height_950hPa,geopotential_height_925hPa,geopotential_height_900hPa,geopotential_height_850hPa"
    private val LEVELS = intArrayOf(950, 925, 900, 850)

    // Modelos independientes: ECMWF (europeo), ICON (alemán), GFS (EE. UU.), Météo-France
    private val MODELS = listOf("ecmwf_ifs025", "icon_seamless", "gfs_seamless", "meteofrance_seamless")

    // Se muestran 7 días, pero solo se avisa de los primeros N (más allá la previsión es poco fiable)
    const val NOTIFY_DAYS = 3

    /**
     * Puntuación con perfil vertical (niveles 950/925/900/850 hPa, de abajo arriba):
     * capa saturada abajo, su techo estimado frente a la altitud del mirador,
     * inversión sobre el techo y aire seco encima. La lluvia limita la puntuación.
     */
    fun scoreProfile(
        hr: Int, low: Double, mid: Double, high: Double, t: Double, dew: Double, wind: Double, precip: Double,
        T: DoubleArray, RH: DoubleArray, Z: DoubleArray, viewM: Int
    ): Hour {
        var s = 0
        val r = mutableListOf<String>()
        var idxTop = -2 // -2: sin capa saturada, -1: solo junto al suelo, 0..3: último nivel saturado
        if (RH[0] >= 88) {
            idxTop = 0
            for (k in 1..3) { if (RH[k] >= 88) idxTop = k else break }
        } else if (t - dew <= 2.0) {
            idxTop = -1
        }
        val top: Int? = when {
            idxTop == -2 -> null
            idxTop == -1 -> Z[0].toInt()
            idxTop < 3 -> ((Z[idxTop] + Z[idxTop + 1]) / 2).toInt()
            else -> Z[3].toInt() + 100
        }
        if (top != null) {
            s += 15; r += "base saturada"
            val margin = viewM - top
            if (margin >= 100) { s += 25; r += "techo bajo el mirador" }
            else if (margin >= 0) { s += 15; r += "techo cerca del mirador" }
            else if (margin < -100) { s -= 30; r += "⚠ nubes sobre el mirador" }
            if (idxTop < 3) {
                val lowT = if (idxTop < 0) t else T[idxTop]
                val dT = T[idxTop + 1] - lowT
                val upRH = RH[idxTop + 1]
                if (dT >= 0) { s += 20; r += "inversión sobre el techo" } else if (dT >= -2) s += 10
                if (upRH <= 60) { s += 15; r += "aire seco arriba" } else if (upRH <= 75) s += 8 else if (upRH >= 90) s -= 25
            }
            if (low >= 80) { s += 15; r += "nubes bajas" } else if (low >= 60) s += 8
        }
        val up = maxOf(mid, high)
        if (up <= 20) { s += 5; r += "cielo despejado arriba" } else if (up > 60) s -= 15
        if (wind <= 8) { s += 5; r += "viento flojo" }
        if (top == null) s = minOf(s, 20)
        if (precip >= 0.2) { s = minOf(s, 15); r += "⚠ lluvia" } else if (precip >= 0.1) s = minOf(s, 35)
        return Hour(hr, s.coerceIn(0, 100), r, top)
    }

    /** Puntuación simple (solo superficie); se usa si un modelo no trae datos de niveles de presión. */
    fun score(hr: Int, low: Double, mid: Double, high: Double, t: Double, dew: Double, wind: Double, t850: Double): Hour {
        var s = 0
        val r = mutableListOf<String>()
        if (low >= 80) { s += 40; r += "nubes bajas" }
        else if (low >= 60) { s += 25; r += "nubes bajas" }
        else if (low >= 40) s += 10
        val up = maxOf(mid, high)
        if (up <= 20) { s += 20; r += "cielo despejado arriba" } else if (up <= 40) s += 10 else if (up > 60) s -= 20
        val spread = t - dew
        if (spread <= 1.5) { s += 15; r += "aire saturado" } else if (spread <= 3) s += 8
        if (wind <= 8) { s += 10; r += "viento flojo" } else if (wind <= 15) s += 5
        val d = t850 - t
        if (d > -3) { s += 15; r += "inversión térmica" } else if (d > -5) s += 8
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

    /** Puntuaciones por día de un modelo (sfx = "_modelo", o "" si no se pidió ninguno). Null si faltan variables. */
    private fun perModel(h: JSONObject, sfx: String, dates: List<String>, viewM: Int): Map<String, List<Hour>>? {
        val low = h.optJSONArray("cloud_cover_low$sfx") ?: return null
        val mid = h.optJSONArray("cloud_cover_mid$sfx") ?: return null
        val high = h.optJSONArray("cloud_cover_high$sfx") ?: return null
        val t = h.optJSONArray("temperature_2m$sfx") ?: return null
        val dew = h.optJSONArray("dew_point_2m$sfx") ?: return null
        val wind = h.optJSONArray("wind_speed_10m$sfx") ?: return null
        val t850 = h.optJSONArray("temperature_850hPa$sfx") ?: return null
        val pT = LEVELS.map { h.optJSONArray("temperature_${it}hPa$sfx") }
        val pRH = LEVELS.map { h.optJSONArray("relative_humidity_${it}hPa$sfx") }
        val pZ = LEVELS.map { h.optJSONArray("geopotential_height_${it}hPa$sfx") }
        val prec = h.optJSONArray("precipitation$sfx")
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
                t.optDouble(i, Double.NaN), dew.optDouble(i, Double.NaN), wind.optDouble(i, Double.NaN), t850.optDouble(i, Double.NaN)
            )
            if (v.any { it.isNaN() }) continue
            val T = DoubleArray(4)
            val RH = DoubleArray(4)
            val Z = DoubleArray(4)
            var ok = true
            for (k in 0 until 4) {
                T[k] = pT[k]?.optDouble(i, Double.NaN) ?: Double.NaN
                RH[k] = pRH[k]?.optDouble(i, Double.NaN) ?: Double.NaN
                Z[k] = pZ[k]?.optDouble(i, Double.NaN) ?: Double.NaN
                if (T[k].isNaN() || RH[k].isNaN() || Z[k].isNaN()) ok = false
            }
            val pr = prec?.optDouble(i, 0.0) ?: 0.0
            val hourScore = if (ok) scoreProfile(hr, v[0], v[1], v[2], v[3], v[4], v[5], if (pr.isNaN()) 0.0 else pr, T, RH, Z, viewM)
            else score(hr, v[0], v[1], v[2], v[3], v[4], v[5], v[6])
            out.getOrPut(date) { mutableListOf() } += hourScore
        }
        return out
    }

    private fun combine(date: String, ms: List<List<Hour>>): Day {
        val n = ms.size
        val modelAvgs = ms.map { hs -> hs.sumOf { it.score } / hs.size }
        val avg = modelAvgs.sum() / n
        val spread = modelAvgs.max() - modelAvgs.min()
        val hours = ms.flatten().map { it.hr }.distinct().sorted().map { hr ->
            val hh = ms.mapNotNull { m -> m.firstOrNull { it.hr == hr } }
            val reasons = hh.flatMap { it.reasons }.groupingBy { it }.eachCount()
                .filter { it.value * 2 >= hh.size }.keys.toList()
            val tops = hh.mapNotNull { it.top }
            Hour(hr, hh.sumOf { it.score } / hh.size, reasons, if (tops.isEmpty()) null else tops.sum() / tops.size)
        }
        return Day(date, avg, hours.maxByOrNull { it.score }!!, hours, n, spread)
    }

    fun fetch(lat: Double, lon: Double, aemet: Pair<String, String>? = null, viewM: Int = 1350): List<Day> {
        val today = LocalDate.now(ZoneId.of("Europe/Madrid"))
        val dates = (0..6).map { today.plusDays(it.toLong()).toString() }
        val perModels = mutableListOf<Map<String, List<Hour>>>()
        try {
            val h = get("${base(lat, lon)}&hourly=$VARS&models=${MODELS.joinToString(",")}").getJSONObject("hourly")
            for (m in MODELS) perModel(h, "_$m", dates, viewM)?.let { if (it.isNotEmpty()) perModels += it }
        } catch (e: Exception) { /* se prueba con el modelo por defecto */ }
        if (perModels.isEmpty()) {
            val h = get("${base(lat, lon)}&hourly=$VARS").getJSONObject("hourly")
            perModel(h, "", dates, viewM)?.let { if (it.isNotEmpty()) perModels += it }
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

    /** Predicción horaria de AEMET (solo ~48 h): humedad, viento y cielo entre las 6 y las 10 h. */
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

    // Puntos predefinidos: el valle/pueblo que se ve desde abajo del mirador (coordenadas aproximadas)
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

    /** Busca pueblos por nombre con el geocodificador gratuito de Open-Meteo. */
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
    fun viewM(ctx: Context) = prefs(ctx).getInt("viewm", 1350)

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

    /** Consulta el tiempo y avisa (una vez por día) si se supera el umbral. */
    fun checkAndNotify(ctx: Context): List<Day> {
        val place = current(ctx)
        val p = prefs(ctx)
        val ak = p.getString("aemet_key", "")?.trim() ?: ""
        val am = p.getString("aemet_muni", "")?.trim() ?: ""
        val days = fetch(place.lat, place.lon, if (ak.isNotEmpty() && am.isNotEmpty()) Pair(ak, am) else null, viewM(ctx))
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
