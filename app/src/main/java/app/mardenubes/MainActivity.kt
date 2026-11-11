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