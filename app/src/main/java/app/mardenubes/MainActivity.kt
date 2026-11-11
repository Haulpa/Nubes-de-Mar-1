package app.mardenubes

import android.Manifest
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val BG = 0xFF0B1620.toInt()
    private val CARD = 0xFF14232F.toInt()
    private val CHIP = 0xFF1E3342.toInt()
    private val TEXT = 0xFFE8EEF3.toInt()
    private val MUTED = 0xFF8FA3B3.toInt()
    private val ACCENT = 0xFF4B9FD6.toInt()
    private val GOOD = 0xFF3DBE8B.toInt()
    private val WARN = 0xFFE0A93B.toInt()
    private val LOW = 0xFF6B8294.toInt()
    private val M = ViewGroup.LayoutParams.MATCH_PARENT
    private val W = ViewGroup.LayoutParams.WRAP_CONTENT

    private val perDayOptions = intArrayOf(1, 2, 4, 6, 8, 12, 24)
    private val chips = mutableListOf<TextView>()
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private lateinit var freqLabel: TextView
    private lateinit var thrLabel: TextView
    private lateinit var placeLabel: TextView
    private lateinit var subtitle: TextView
    private lateinit var presetBox: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var searchResults: LinearLayout
    private lateinit var viewInput: EditText
    private lateinit var viewResults: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int = 16) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private fun lp(bottom: Int = 0) = LinearLayout.LayoutParams(M, W).apply { setMargins(0, 0, 0, dp(bottom)) }

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 20)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = lp(12)
    }

    private fun scoreColor(s: Int) = if (s >= 70) GOOD else if (s >= 50) WARN else LOW

    private fun schedule(update: Boolean) {
        val perDay = Logic.prefs(this).getInt("perday", 8)
        val hours = (24 / perDay).toLong().coerceAtLeast(1)
        val req = PeriodicWorkRequestBuilder<CheckWorker>(hours, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "mdn",
            if (update) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            req
        )
    }

    private fun styleChips(n: Int) {
        chips.forEachIndexed { i, c ->
            val sel = perDayOptions[i] == n
            c.background = rounded(if (sel) ACCENT else CHIP, 12)
            c.setTextColor(if (sel) 0xFFFFFFFF.toInt() else MUTED)
        }
        freqLabel.text = "$n al día · cada ${24 / n} h"
    }

    private fun button(text: String, filled: Boolean) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        stateListAnimator = null
        setTextColor(if (filled) 0xFFFFFFFF.toInt() else ACCENT)
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            if (filled) setColor(ACCENT) else { setColor(0); setStroke(dp(1), ACCENT) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        schedule(false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(24))
        }
        root.addView(tv("Mar de nubes", 28f, TEXT, true))
        subtitle = tv("", 14f, MUTED).apply { layoutParams = lp(16) }
        root.addView(subtitle)

        // Punto de observación
        val pc = card()
        pc.addView(tv("Punto de observación", 13f, MUTED))
        placeLabel = tv("", 18f, TEXT, true).apply { layoutParams = lp(4) }
        pc.addView(placeLabel)
        pc.addView(tv("Elige el valle o pueblo que se ve desde el mirador (por debajo), no la cumbre.", 12f, MUTED).apply { layoutParams = lp(10) })
        presetBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pc.addView(presetBox)
        searchInput = EditText(this).apply {
            hint = "Buscar pueblo o coordenadas (40.25, -5.20)"
            setHintTextColor(MUTED)
            setTextColor(TEXT)
            textSize = 14f
            setSingleLine(true)
            background = rounded(CHIP, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = lp(8).apply { topMargin = dp(10) }
        }
        pc.addView(searchInput)
        pc.addView(button("Buscar", false).apply {
            layoutParams = lp(8)
            setOnClickListener { search() }
        })
        searchResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pc.addView(searchResults)
        pc.addView(tv("Altitud del mirador (m)", 12f, MUTED).apply { layoutParams = lp(4) })
        viewInput = input("Altitud del mirador (m)").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Logic.viewM(this@MainActivity).toString())
        }
        pc.addView(viewInput)
        pc.addView(button("Guardar altitud", false).apply {
            layoutParams = lp(0)
            setOnClickListener {
                val v = viewInput.text.toString().trim().toIntOrNull()?.coerceIn(0, 4000) ?: 1350
                Logic.prefs(this@MainActivity).edit().putInt("viewm", v).putStringSet("notified", emptySet()).apply()
                check()
            }
        })
        val miradorInput = input("Mirador: nombre o coordenadas (altitud automática)")
        pc.addView(miradorInput)
        pc.addView(button("Buscar altitud del mirador", false).apply {
            layoutParams = lp(8)
            setOnClickListener { findViewAltitude(miradorInput.text.toString().trim()) }
        })
        viewResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pc.addView(viewResults)
        root.addView(pc)
        renderPlaces()

        // AEMET (opcional)
        val ac = card()
        ac.addView(tv("AEMET (opcional)", 13f, MUTED))
        ac.addView(tv("Clave gratuita en opendata.aemet.es. El código es el número tras «id» en la URL del municipio en aemet.es (p. ej. madrid-id28079 → 28079). Solo cubre hoy y mañana.", 12f, MUTED).apply { layoutParams = lp(10) })
        val keyInput = input("Clave API de AEMET").apply { setText(Logic.prefs(this@MainActivity).getString("aemet_key", "")) }
        val muniInput = input("Código de municipio (5 dígitos)").apply { setText(Logic.prefs(this@MainActivity).getString("aemet_muni", "")) }
        ac.addView(keyInput)
        ac.addView(muniInput)
        ac.addView(button("Guardar AEMET", false).apply {
            layoutParams = lp(0)
            setOnClickListener {
                Logic.prefs(this@MainActivity).edit()
                    .putString("aemet_key", keyInput.text.toString().trim())
                    .putString("aemet_muni", muniInput.text.toString().trim()).apply()
                check()
            }
        })
        root.addView(ac)

        // Frecuencia
        val fc = card()
        fc.addView(tv("Comprobaciones al día", 13f, MUTED))
        freqLabel = tv("", 18f, TEXT, true).apply { layoutParams = lp(10) }
        fc.addView(freqLabel)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        perDayOptions.forEach { n ->
            val c = TextView(this).apply {
                text = "$n×"
                gravity = Gravity.CENTER
                textSize = 14f
                setPadding(0, dp(10), 0, dp(10))
                layoutParams = LinearLayout.LayoutParams(0, W, 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
                setOnClickListener {
                    Logic.prefs(this@MainActivity).edit().putInt("perday", n).apply()
                    schedule(true)
                    styleChips(n)
                }
            }
            chips += c
            row.addView(c)
        }
        fc.addView(row)
        root.addView(fc)
        styleChips(Logic.prefs(this).getInt("perday", 8))

        // Umbral
        val tc = card()
        val thr = Logic.threshold(this)
        thrLabel = tv("Umbral de aviso: $thr/100", 16f, TEXT, true)
        tc.addView(thrLabel)
        tc.addView(SeekBar(this).apply {
            max = 60
            progress = thr - 30
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    thrLabel.text = "Umbral de aviso: ${p + 30}/100"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    Logic.prefs(this@MainActivity).edit().putInt("thr", progress + 30).putStringSet("notified", emptySet()).apply()
                }
            })
        })
        root.addView(tc)

        // Botones
        val br = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = lp(8) }
        br.addView(button("Comprobar ahora", true).apply {
            layoutParams = LinearLayout.LayoutParams(0, W, 1f).apply { setMargins(0, 0, dp(6), 0) }
            setOnClickListener { check() }
        })
        br.addView(button("Aviso de prueba", false).apply {
            layoutParams = LinearLayout.LayoutParams(0, W, 1f).apply { setMargins(dp(6), 0, 0, 0) }
            setOnClickListener { Logic.send(this@MainActivity, "☁️ Aviso de prueba", "Las notificaciones funcionan.") }
        })
        root.addView(br)

        status = tv("Cargando…", 13f, MUTED).apply { layoutParams = lp(12); setPadding(dp(4), dp(4), 0, 0) }
        root.addView(status)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(results)
        root.addView(tv("Datos: Open-Meteo (ECMWF, DWD, NOAA, Météo-France) · AEMET · Altitud: Copernicus", 11f, MUTED).apply { setPadding(dp(4), dp(8), 0, 0) })

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })
        check()
    }

    private fun input(h: String) = EditText(this).apply {
        hint = h
        setHintTextColor(MUTED)
        setTextColor(TEXT)
        textSize = 14f
        setSingleLine(true)
        background = rounded(CHIP, 12)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = lp(8)
    }

    private fun setViewM(m: Int, source: String) {
        Logic.prefs(this).edit().putInt("viewm", m).putStringSet("notified", emptySet()).apply()
        viewInput.setText(m.toString())
        viewResults.removeAllViews()
        viewResults.addView(tv("Altitud del mirador: $m m ($source)", 12f, MUTED))
        check()
    }

    private fun findViewAltitude(q: String) {
        if (q.isEmpty()) return
        val m = Regex("""^(-?\d+(?:\.\d+)?)\s*[,; ]\s*(-?\d+(?:\.\d+)?)$""").find(q)
        viewResults.removeAllViews()
        viewResults.addView(tv("Buscando…", 13f, MUTED))
        Thread {
            try {
                val lat = m?.groupValues?.get(1)?.toDouble()
                val lon = m?.groupValues?.get(2)?.toDouble()
                if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                    val e = Logic.elevation(lat, lon)
                    runOnUiThread { setViewM(e, "terreno en esas coordenadas") }
                } else {
                    val res = Logic.geocode(q).filter { it.elev != null }
                    runOnUiThread {
                        viewResults.removeAllViews()
                        if (res.isEmpty()) viewResults.addView(tv("Sin resultados con altitud. Prueba con coordenadas (p. ej. 40.28, -4.99).", 13f, MUTED))
                        else res.forEach { pl ->
                            viewResults.addView(TextView(this).apply {
                                text = "${pl.name}\n${pl.detail}"
                                textSize = 14f
                                setTextColor(TEXT)
                                background = rounded(CHIP, 12)
                                setPadding(dp(12), dp(10), dp(12), dp(10))
                                layoutParams = lp(6)
                                setOnClickListener { setViewM(pl.elev ?: 1350, pl.name) }
                            })
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    viewResults.removeAllViews()
                    viewResults.addView(tv("Error de red. Reintenta.", 13f, MUTED))
                }
            }
        }.start()
    }

    private fun placeRow(p: Place, sel: Boolean) = TextView(this).apply {
        text = if (p.detail.isEmpty()) p.name else "${p.name}\n${p.detail}"
        textSize = 14f
        setTextColor(if (sel) 0xFFFFFFFF.toInt() else TEXT)
        background = rounded(if (sel) ACCENT else CHIP, 12)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = lp(6)
        setOnClickListener { choose(p) }
    }

    private fun renderPlaces() {
        val cur = Logic.current(this)
        placeLabel.text = cur.name
        subtitle.text = String.format(Locale.US, "%.3f, %.3f", cur.lat, cur.lon)
        presetBox.removeAllViews()
        Logic.PRESETS.forEach { p ->
            presetBox.addView(placeRow(p, p.name == cur.name && p.lat == cur.lat && p.lon == cur.lon))
        }
    }

    private fun choose(p: Place) {
        Logic.setPlace(this, p)
        searchResults.removeAllViews()
        renderPlaces()
        check()
    }

    private fun search() {
        val q = searchInput.text.toString().trim()
        if (q.isEmpty()) return
        val m = Regex("""^(-?\d+(?:\.\d+)?)\s*[,; ]\s*(-?\d+(?:\.\d+)?)$""").find(q)
        if (m != null) {
            val lat = m.groupValues[1].toDouble()
            val lon = m.groupValues[2].toDouble()
            if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                choose(Place("Punto personalizado", lat, lon, "$lat, $lon"))
                return
            }
        }
        searchResults.removeAllViews()
        searchResults.addView(tv("Buscando…", 13f, MUTED))
        Thread {
            val res = try { Logic.geocode(q) } catch (e: Exception) { null }
            runOnUiThread {
                searchResults.removeAllViews()
                if (res == null) searchResults.addView(tv("Error de red. Reintenta.", 13f, MUTED))
                else if (res.isEmpty()) searchResults.addView(tv("Sin resultados.", 13f, MUTED))
                else res.forEach { searchResults.addView(placeRow(it, false)) }
            }
        }.start()
    }

    private fun dayCard(d: Day, label: String): LinearLayout {
        val c = card()
        val col = scoreColor(d.avg)
        c.addView(tv("$label · ${d.date.substring(8, 10)}/${d.date.substring(5, 7)}", 13f, MUTED))
        val sr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        sr.addView(tv("${d.avg}", 48f, col, true))
        sr.addView(tv("/100", 16f, MUTED).apply { setPadding(dp(4), 0, 0, dp(10)) })
        c.addView(sr)
        val level = if (d.avg >= 70) "Muy probable" else if (d.avg >= 50) "Posible" else "Poco probable"
        c.addView(tv(level, 17f, col, true))
        val detail = "Mejor hora ${d.best.hr}:00" + if (d.best.reasons.isNotEmpty()) " · ${d.best.reasons.joinToString(", ")}" else ""
        c.addView(tv(detail, 13f, MUTED).apply { layoutParams = lp(2) })
        d.best.top?.let {
            c.addView(tv("Techo estimado de la capa húmeda ~$it m · mirador ${Logic.viewM(this)} m", 12f, MUTED).apply { layoutParams = lp(2) })
        }
        val agree = if (d.models < 2) "1 modelo" else "${d.models} modelos · " +
            (if (d.spread <= 15) "coinciden" else if (d.spread <= 30) "diferencias moderadas" else "poca coincidencia")
        c.addView(tv(agree, 12f, MUTED).apply { layoutParams = lp(if (d.aemet != null) 2 else 12) })
        d.aemet?.let { c.addView(tv(it, 12f, MUTED).apply { layoutParams = lp(12) }) }
        val hr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        d.hours.forEach { h ->
            hr.addView(TextView(this).apply {
                text = "${h.hr}h\n${h.score}"
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(scoreColor(h.score))
                background = rounded(CHIP, 10)
                setPadding(0, dp(8), 0, dp(8))
                layoutParams = LinearLayout.LayoutParams(0, W, 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
            })
        }
        c.addView(hr)
        return c
    }

    private fun dayLabel(i: Int, d: Day): String = when (i) {
        0 -> "Hoy"
        1 -> "Mañana"
        else -> LocalDate.parse(d.date).dayOfWeek.getDisplayName(TextStyle.FULL, Locale("es", "ES"))
            .replaceFirstChar { it.uppercase() }
    }

    private fun check() {
        status.text = "Consultando…"
        Thread {
            try {
                val days = Logic.checkAndNotify(this)
                runOnUiThread {
                    results.removeAllViews()
                    days.forEachIndexed { i, d -> results.addView(dayCard(d, dayLabel(i, d))) }
                    status.text = if (days.isEmpty()) "Sin datos"
                    else "Última comprobación: " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) +
                        (if (Logic.aemetStatus.isEmpty()) "" else "\n" + Logic.aemetStatus)
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Error de red. Reintenta." }
            }
        }.start()
    }
}
