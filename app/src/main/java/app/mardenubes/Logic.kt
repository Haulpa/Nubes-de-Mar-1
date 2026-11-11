package app.mardenubes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId

data class Hour(val hr: Int, val score: Int, val reasons: List<String>)
data class Day(val date: String, val avg: Int, val best: Hour, val hours: List<Hour>)

object Logic {
    // Valle del Tiétar (Arenas de San Pedro), de cara al Puerto del Pico
    private const val API = "https://api.open-meteo.com/v1/forecast?latitude=40.21&longitude=-5.08" +
        "&hourly=temperature_2m,dew_point_2m,wind_speed_10m,cloud_cover_low,cloud_cover_mid,cloud_cover_high,temperature_850hPa" +
        "&forecast_days=7&timezone=Europe%2FMadrid"

    // Se muestran 7 días, pero solo se avisa de los primeros N (más allá la previsión es poco fiable)
    const val NOTIFY_DAYS = 3

    fun score(hr: Int, low: Double, mid: Double, high: Double, t: Double, dew: Double, wind: Double, t850: Double): Hour {
        var s = 0
        val r = mutableListOf<String>()
        if (low >= 80) { s += 40; r += "nubes bajas ${low.toInt()}%" }
        else if (low >= 60) { s += 25; r += "nubes bajas ${low.toInt()}%" }
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

    fun fetch(): List<Day> {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val h = JSONObject(body).getJSONObject("hourly")
        val time = h.getJSONArray("time")
        val t = h.getJSONArray("temperature_2m")
        val dew = h.getJSONArray("dew_point_2m")
        val wind = h.getJSONArray("wind_speed_10m")
        val low = h.getJSONArray("cloud_cover_low")
        val mid = h.getJSONArray("cloud_cover_mid")
        val high = h.getJSONArray("cloud_cover_high")
        val t850 = h.getJSONArray("temperature_850hPa")
        val today = LocalDate.now(ZoneId.of("Europe/Madrid"))
        val dates = (0..6).map { today.plusDays(it.toLong()).toString() }
        return dates.mapNotNull { d ->
            val hours = mutableListOf<Hour>()
            for (i in 0 until time.length()) {
                val ts = time.getString(i)
                if (!ts.startsWith(d)) continue
                val hr = ts.substring(11, 13).toInt()
                if (hr < 6 || hr > 10) continue
                val v = doubleArrayOf(low.optDouble(i, Double.NaN), mid.optDouble(i, Double.NaN), high.optDouble(i, Double.NaN),
                    t.optDouble(i, Double.NaN), dew.optDouble(i, Double.NaN), wind.optDouble(i, Double.NaN), t850.optDouble(i, Double.NaN))
                if (v.any { it.isNaN() }) continue
                hours += score(hr, v[0], v[1], v[2], v[3], v[4], v[5], v[6])
            }
            if (hours.isEmpty()) null else Day(d, hours.sumOf { it.score } / hours.size, hours.maxByOrNull { it.score }!!, hours)
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

    /** Consulta el tiempo y avisa (una vez por día) si se supera el umbral. */
    fun checkAndNotify(ctx: Context): List<Day> {
        val days = fetch()
        val p = prefs(ctx)
        val thr = threshold(ctx)
        val notified = (p.getStringSet("notified", emptySet()) ?: emptySet()).toMutableSet()
        for (d in days.take(NOTIFY_DAYS)) {
            if (d.avg >= thr && d.date !in notified) {
                send(ctx, "☁️ Posible mar de nubes — Puerto del Pico",
                    "${d.date}: probabilidad ${d.avg}/100. Mejor hora ~${d.best.hr}:00. ${d.best.reasons.joinToString(", ")}")
                notified += d.date
            }
        }
        val today = LocalDate.now(ZoneId.of("Europe/Madrid")).toString()
        p.edit().putStringSet("notified", notified.filter { it >= today }.toSet()).apply()
        return days
    }
}
