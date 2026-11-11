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
data class Place(val name: String, val lat: Double, val lon: Double, val detail: String = "", val elev: Int? = null)
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