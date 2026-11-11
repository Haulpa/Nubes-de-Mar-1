package app.mardenubes

import android.Manifest
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
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
        root.addView(tv("Puerto del Pico · Valle del Tiétar", 14f, MUTED).apply { layoutParams = lp(16) })

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

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })
        check()
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
        val agree = if (d.models < 2) "1 modelo" else "${d.models} modelos · " +
            (if (d.spread <= 15) "coinciden" else if (d.spread <= 30) "diferencias moderadas" else "poca coincidencia")
        c.addView(tv(agree, 12f, MUTED).apply { layoutParams = lp(12) })
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
                    else "Última comprobación: " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Error de red. Reintenta." }
            }
        }.start()
    }
}
