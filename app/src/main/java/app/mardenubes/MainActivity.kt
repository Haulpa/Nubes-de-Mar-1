package app.mardenubes

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
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
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var result: TextView
    private lateinit var thrLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val req = PeriodicWorkRequestBuilder<CheckWorker>(3, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("mdn", ExistingPeriodicWorkPolicy.KEEP, req)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(TextView(this).apply { text = "☁️ Mar de nubes · Puerto del Pico"; textSize = 20f })

        thrLabel = TextView(this)
        val thr = Logic.threshold(this)
        thrLabel.text = "Umbral de aviso: $thr/100"
        root.addView(thrLabel)
        root.addView(SeekBar(this).apply {
            max = 60
            progress = thr - 30
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { thrLabel.text = "Umbral de aviso: ${p + 30}/100" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    Logic.prefs(this@MainActivity).edit().putInt("thr", progress + 30).putStringSet("notified", emptySet()).apply()
                }
            })
        })
        root.addView(Button(this).apply { text = "Comprobar ahora"; setOnClickListener { check() } })
        root.addView(Button(this).apply {
            text = "Enviar aviso de prueba"
            setOnClickListener { Logic.send(this@MainActivity, "☁️ Aviso de prueba", "Las notificaciones funcionan.") }
        })
        result = TextView(this).apply { text = "Cargando…"; setPadding(0, pad, 0, 0) }
        root.addView(result)
        setContentView(ScrollView(this).apply { addView(root) })
        check()
    }

    private fun check() {
        result.text = "Consultando…"
        Thread {
            val text = try {
                Logic.checkAndNotify(this).joinToString("\n\n") { d ->
                    val lvl = if (d.avg >= 70) "Muy probable" else if (d.avg >= 50) "Posible" else "Poco probable"
                    "${d.date}\n${d.avg}/100 — $lvl\nMejor hora ${d.best.hr}:00 · ${d.best.reasons.joinToString(", ")}\n" +
                        d.hours.joinToString(" | ") { "${it.hr}h:${it.score}" }
                }.ifEmpty { "Sin datos" }
            } catch (e: Exception) {
                "Error de red. Reintenta."
            }
            runOnUiThread { result.text = text }
        }.start()
    }
}
