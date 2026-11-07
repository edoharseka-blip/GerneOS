package com.e90os.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * E90 OS home screen.
 * Shows the clock, date and device battery, the active media track with working
 * transport controls, and tabs for music, navigation and the app grid.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE, d MMMM", Locale.forLanguageTag("ru"))

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    // Header and dashboard
    private lateinit var clock: TextView
    private lateinit var dateText: TextView
    private lateinit var batteryPercent: TextView
    private lateinit var batteryState: TextView
    private lateinit var batteryBar: Bar

    // Now playing
    private lateinit var artImage: ImageView
    private lateinit var artPlaceholder: TextView
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var trackState: TextView
    private lateinit var playButton: TextView
    private lateinit var trackBar: Bar
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var accessButton: TextView

    // Pages and navigation
    private lateinit var dashboard: View
    private lateinit var appsPage: View
    private lateinit var settingsPage: LinearLayout
    private lateinit var grid: GridLayout
    private val settingLabels = mutableMapOf<Int, TextView>()
    private val tabLabels = mutableListOf<TextView>()
    private val tabLines = mutableListOf<View>()

    private var controller: MediaController? = null
    private var isPlaying = false

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = timeFmt.format(now)
            dateText.text = dateFmt.format(now).replaceFirstChar { it.uppercase() }
            refreshMedia()
            handler.postDelayed(this, 1000)
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
    private fun rgb(hex: String): Int = Color.parseColor(hex)

    private fun box(fill: String, radius: Int, strokeHex: String? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(rgb(fill))
            cornerRadius = radius.dp().toFloat()
            if (strokeHex != null) setStroke(1.dp(), rgb(strokeHex))
        }

    private fun label(t: String, size: Float, hex: String, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = t
            textSize = size
            setTextColor(rgb(hex))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun fmt(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    /** Thin progress bar. [set] takes a fraction from 0 to 1. */
    private inner class Bar(heightDp: Int, color: String) {
        private val fill = View(this@MainActivity).apply { background = box(color, 2) }
        private val rest = View(this@MainActivity)
        val view = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = box("#1E2630", 2)
            addView(fill, LinearLayout.LayoutParams(0, heightDp.dp(), 0f))
            addView(rest, LinearLayout.LayoutParams(0, heightDp.dp(), 1f))
        }

        fun set(fraction: Float) {
            val f = fraction.coerceIn(0f, 1f)
            (fill.layoutParams as LinearLayout.LayoutParams).weight = f
            (rest.layoutParams as LinearLayout.LayoutParams).weight = 1f - f
            view.requestLayout()
        }
    }

    private fun topBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(20.dp(), 8.dp(), 20.dp(), 8.dp())
        addView(label("E90 OS", 20f, "#F3A43D", true))
        addView(label("  —  COCKPIT", 18f, "#D3C3AF").apply { letterSpacing = 0.1f })
        addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
        dateText = label("", 14f, "#B6A99B").apply { setPadding(0, 0, 14.dp(), 0) }
        addView(dateText)
        clock = label("", 18f, "#E6E9EC")
        addView(clock)
    }

    private fun buildDashboard(): View {
        // Left: date and device battery.
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box("#0E1319", 16, "#1E2630")
            setPadding(20.dp(), 20.dp(), 20.dp(), 20.dp())
            gravity = Gravity.CENTER_VERTICAL

            dateText = label("", 26f, "#FFFFFF", true)
            addView(dateText)

            addView(label("Батарея устройства", 13f, "#7F8A96").apply {
                setPadding(0, 28.dp(), 0, 0)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                batteryPercent = label("--", 40f, "#4DA3FF", true)
                addView(batteryPercent)
                batteryState = label("", 14f, "#9AA3AD").apply { setPadding(12.dp(), 0, 0, 0) }
                addView(batteryState)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            batteryBar = Bar(4, "#1C69D4")
            addView(batteryBar.view, LinearLayout.LayoutParams(MATCH, 4.dp()).apply { topMargin = 8.dp() })
        }

        // Right: now playing card.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box("#11161D", 16, "#1E2630")
            setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
            gravity = Gravity.CENTER_VERTICAL

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(FrameLayout(this@MainActivity).apply {
                    background = GradientDrawable(
                        GradientDrawable.Orientation.BR_TL,
                        intArrayOf(rgb("#14365E"), rgb("#05070A"))
                    ).apply { cornerRadius = 12.dp().toFloat() }
                    artPlaceholder = label("♪", 40f, "#1C69D4", true).apply { gravity = Gravity.CENTER }
                    addView(artPlaceholder, FrameLayout.LayoutParams(MATCH, MATCH))
                    artImage = ImageView(this@MainActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        visibility = View.GONE
                    }
                    addView(artImage, FrameLayout.LayoutParams(MATCH, MATCH))
                }, LinearLayout.LayoutParams(120.dp(), 120.dp()))

                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(16.dp(), 0, 0, 0)
                    trackTitle = label("Нет активного трека", 22f, "#FFFFFF", true).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    addView(trackTitle)
                    trackArtist = label("", 15f, "#9AA3AD").apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    addView(trackArtist)
                    trackState = label("", 12f, "#1C69D4").apply { letterSpacing = 0.1f }
                    addView(trackState)
                    accessButton = label("Разрешить показ трека", 14f, "#FFFFFF").apply {
                        gravity = Gravity.CENTER
                        setPadding(16.dp(), 8.dp(), 16.dp(), 8.dp())
                        background = box("#1C69D4", 8)
                        visibility = View.GONE
                        setOnClickListener {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    }
                    addView(accessButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 10.dp() })
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
            }, LinearLayout.LayoutParams(MATCH, WRAP))

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(label("◀◀", 22f, "#E6E9EC").apply {
                    gravity = Gravity.CENTER
                    setOnClickListener { transport { skipToPrevious() } }
                }, LinearLayout.LayoutParams(64.dp(), 64.dp()))
                playButton = label("▶", 26f, "#FFFFFF").apply {
                    gravity = Gravity.CENTER
                    background = box("#05070A", 32, "#1C69D4")
                    setOnClickListener {
                        val c = controller ?: return@setOnClickListener noTrack()
                        if (isPlaying) c.transportControls.pause() else c.transportControls.play()
                    }
                }
                addView(playButton, LinearLayout.LayoutParams(64.dp(), 64.dp()))
                addView(label("▶▶", 22f, "#E6E9EC").apply {
                    gravity = Gravity.CENTER
                    setOnClickListener { transport { skipToNext() } }
                }, LinearLayout.LayoutParams(64.dp(), 64.dp()))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 8.dp() })

            trackBar = Bar(3, "#1C69D4")
            addView(trackBar.view, LinearLayout.LayoutParams(MATCH, 3.dp()).apply { topMargin = 12.dp() })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                positionText = label("0:00", 12f, "#7F8A96")
                addView(positionText)
                addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
                durationText = label("0:00", 12f, "#7F8A96")
                addView(durationText)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            addView(left, LinearLayout.LayoutParams(0, MATCH, 1f).apply { setMargins(0, 0, 12.dp(), 0) })
            addView(card, LinearLayout.LayoutParams(0, MATCH, 1.4f))
        }
    }

    private val amber = "#F3A43D"
    private val muted = "#B6A99B"
    private val panel = "#15110F"
    private val edge = "#76502A"
    private val shortcutCells = mutableListOf<LinearLayout>()

    private fun cardTitle(title: String): TextView = label(title, 12f, muted).apply {
        setPadding(0, 0, 0, 6.dp())
    }

    private fun dataCard(name: String, value: String, weight: Float = 1f): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = box(panel, 10, edge)
            setPadding(12.dp(), 8.dp(), 8.dp(), 8.dp())
            addView(cardTitle(name))
            addView(label(value, 21f, "#F7EEE4", true))
            tag = weight
        }

    private fun cockpitRow(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, view ->
            addView(view, LinearLayout.LayoutParams(0, MATCH, (view.tag as? Float) ?: 1f).apply {
                if (i > 0) leftMargin = 8.dp()
            })
        }
    }

    /** Home screen: native Android media sessions; vehicle values need a verified CAN source. */
    private fun buildCockpitDashboard(): View {
        val player = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            background = box(panel, 12, edge)

            val top = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val art = FrameLayout(this@MainActivity).apply {
                background = box("#241A13", 10, edge)
                artPlaceholder = label("♫", 44f, amber).apply { gravity = Gravity.CENTER }
                addView(artPlaceholder, FrameLayout.LayoutParams(MATCH, MATCH))
                artImage = ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    visibility = View.GONE
                }
                addView(artImage, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            top.addView(art, LinearLayout.LayoutParams(112.dp(), 112.dp()))
            val meta = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14.dp(), 0, 0, 0)
                trackTitle = label("Нет активного трека", 22f, "#F7EEE4", true).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                addView(trackTitle)
                trackArtist = label("", 15f, muted)
                addView(trackArtist)
                trackState = label("", 12f, amber)
                addView(trackState)
                accessButton = label("Разрешить доступ к музыке", 12f, amber).apply {
                    visibility = View.GONE
                    setPadding(0, 8.dp(), 0, 0)
                    setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                }
                addView(accessButton)
            }
            top.addView(meta, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(top, LinearLayout.LayoutParams(MATCH, 0, 1f))

            trackBar = Bar(4, amber)
            addView(trackBar.view, LinearLayout.LayoutParams(MATCH, 4.dp()))
            val times = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                positionText = label("0:00", 11f, muted)
                durationText = label("0:00", 11f, muted)
                addView(positionText)
                addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
                addView(durationText)
            }
            addView(times)
            val controls = LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER
                orientation = LinearLayout.HORIZONTAL
            }
            controls.addView(label("◀◀", 23f, amber).apply {
                gravity = Gravity.CENTER
                setOnClickListener { transport { skipToPrevious() } }
            }, LinearLayout.LayoutParams(72.dp(), 52.dp()))
            playButton = label("▶", 25f, "#FFF4E7", true).apply {
                gravity = Gravity.CENTER
                background = box("#71400E", 10, amber)
                setOnClickListener {
                    val c = controller ?: return@setOnClickListener noTrack()
                    if (isPlaying) c.transportControls.pause() else c.transportControls.play()
                }
            }
            controls.addView(playButton, LinearLayout.LayoutParams(72.dp(), 52.dp()))
            controls.addView(label("▶▶", 23f, amber).apply {
                gravity = Gravity.CENTER
                setOnClickListener { transport { skipToNext() } }
            }, LinearLayout.LayoutParams(72.dp(), 52.dp()))
            addView(controls)
        }

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("БОРТОВОЙ КОМПЬЮТЕР    ·    CAN: нет данных", 17f, "#F7EEE4", true),
                LinearLayout.LayoutParams(MATCH, 34.dp()))
            addView(cockpitRow(dataCard("Напряжение АКБ", "— В"), dataCard("Температура ОЖ", "— °C")),
                LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(dataCard("Уровень моторного масла", "Нет данных"),
                LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = 7.dp() })
            addView(cockpitRow(dataCard("Запас хода", "— км"), dataCard("Расход", "— л/100 км")),
                LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = 7.dp() })

            val quick = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(10.dp(), 5.dp(), 10.dp(), 5.dp())
                background = box(panel, 10, edge)
                val header = label("Быстрый доступ                                     ✎", 14f, "#F7EEE4", true)
                header.setOnClickListener { editQuickAccess() }
                addView(header)
                val strip = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                repeat(4) { index ->
                    val cell = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        setOnClickListener { if (quickComponent(index) == null) chooseQuickApp(index) else openQuickApp(index) }
                        setOnLongClickListener { chooseQuickApp(index); true }
                    }
                    shortcutCells.add(cell)
                    strip.addView(cell, LinearLayout.LayoutParams(0, MATCH, 1f))
                }
                addView(strip, LinearLayout.LayoutParams(MATCH, 0, 1f))
            }
            addView(quick, LinearLayout.LayoutParams(MATCH, 0, 1.05f).apply { topMargin = 7.dp() })
        }
        refreshQuickAccess()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            addView(player, LinearLayout.LayoutParams(0, MATCH, 1f).apply { rightMargin = 10.dp() })
            addView(right, LinearLayout.LayoutParams(0, MATCH, 1.38f))
        }
    }

    private fun quickComponent(index: Int): ComponentName? =
        getPreferences(MODE_PRIVATE).getString("quick_$index", null)?.let(ComponentName::unflattenFromString)

    private fun refreshQuickAccess() {
        shortcutCells.forEachIndexed { index, cell ->
            cell.removeAllViews()
            val component = quickComponent(index)
            val icon = if (component != null) runCatching {
                packageManager.getActivityIcon(component)
            }.getOrNull() else null
            if (icon != null) cell.addView(ImageView(this).apply { setImageDrawable(icon) },
                LinearLayout.LayoutParams(30.dp(), 30.dp()).apply { rightMargin = 5.dp() })
            val name = component?.let { runCatching {
                packageManager.getActivityInfo(it, 0).loadLabel(packageManager).toString()
            }.getOrNull() } ?: "+ Добавить"
            cell.addView(label(name, 12f, if (component == null) amber else "#E5DAD0").apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
    }

    private fun chooseQuickApp(index: Int) {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        val options = apps.map { it.loadLabel(pm).toString() }.toMutableList()
        options.add(0, "Убрать ярлык")
        AlertDialog.Builder(this).setTitle("Ярлык ${index + 1}")
            .setItems(options.toTypedArray()) { _, choice ->
                val value = if (choice == 0) null else apps[choice - 1].activityInfo.let {
                    ComponentName(it.packageName, it.name).flattenToString()
                }
                getPreferences(MODE_PRIVATE).edit().putString("quick_$index", value).apply()
                refreshQuickAccess()
            }.setNegativeButton("Отмена", null).show()
    }

    private fun editQuickAccess() {
        AlertDialog.Builder(this).setTitle("Изменить быстрый доступ")
            .setItems(arrayOf("Ярлык 1", "Ярлык 2", "Ярлык 3", "Ярлык 4")) { _, index ->
                chooseQuickApp(index)
            }.show()
    }

    private fun openQuickApp(index: Int) {
        val component = quickComponent(index) ?: return chooseQuickApp(index)
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (runCatching { startActivity(intent) }.isFailure) {
            getPreferences(MODE_PRIVATE).edit().remove("quick_$index").apply()
            refreshQuickAccess()
            Toast.makeText(this, "Приложение недоступно", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bottomNav(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(rgb("#100D0B"))
        val titles = listOf("⌂  Главная", "♫  Музыка", "➤  Карта", "▦  Приложения", "⚙  Настройки")
        titles.forEachIndexed { index, title ->
            val col = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            val line = View(this@MainActivity).apply { setBackgroundColor(Color.TRANSPARENT) }
            tabLines.add(line)
            col.addView(line, LinearLayout.LayoutParams(MATCH, 2.dp()))
            val tv = label(title, 14f, "#B6A99B").apply {
                gravity = Gravity.CENTER
                setPadding(4.dp(), 10.dp(), 4.dp(), 10.dp())
            }
            tabLabels.add(tv)
            col.addView(tv, LinearLayout.LayoutParams(MATCH, WRAP))
            col.setOnClickListener { onTab(index) }
            if (index == 1 || index == 2) {
                col.setOnLongClickListener { chooseApp(index); true }
            }
            addView(col, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
    }

    private fun onTab(index: Int) {
        when (index) {
            0 -> select(0)
            1, 2 -> openSelectedApp(index)
            3 -> {
                select(3)
                loadApps()
            }
            4 -> {
                updateSettingLabels()
                select(4)
            }
        }
    }

    private fun select(index: Int) {
        dashboard.visibility = if (index == 0) View.VISIBLE else View.GONE
        appsPage.visibility = if (index == 3) View.VISIBLE else View.GONE
        settingsPage.visibility = if (index == 4) View.VISIBLE else View.GONE
        tabLabels.forEachIndexed { i, tv ->
            tv.setTextColor(rgb(if (i == index) "#F3A43D" else "#B6A99B"))
        }
        tabLines.forEachIndexed { i, v ->
            v.setBackgroundColor(if (i == index) rgb("#F3A43D") else Color.TRANSPARENT)
        }
    }

    private fun appKey(index: Int) = if (index == 1) "music_app" else "navigation_app"

    private fun appTitle(index: Int) = if (index == 1) "Музыка" else "Навигация"

    private fun savedApp(index: Int): ComponentName? =
        getPreferences(MODE_PRIVATE).getString(appKey(index), null)
            ?.let(ComponentName::unflattenFromString)

    private fun openSelectedApp(index: Int) {
        val component = savedApp(index) ?: return chooseApp(index)
        val launch = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (runCatching { startActivity(launch) }.isFailure) {
            getPreferences(MODE_PRIVATE).edit().remove(appKey(index)).apply()
            Toast.makeText(this, "Приложение недоступно. Выберите другое", Toast.LENGTH_SHORT).show()
            chooseApp(index)
        }
    }

    private fun chooseApp(index: Int) {
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        if (apps.isEmpty()) {
            Toast.makeText(this, "Нет доступных приложений", Toast.LENGTH_SHORT).show()
            return
        }
        val names = apps.map { "${it.loadLabel(pm)}  (${it.activityInfo.packageName})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Выберите приложение: ${appTitle(index)}")
            .setItems(names) { _, which ->
                val activity = apps[which].activityInfo
                val component = ComponentName(activity.packageName, activity.name)
                getPreferences(MODE_PRIVATE).edit()
                    .putString(appKey(index), component.flattenToString()).apply()
                updateSettingLabels()
                openSelectedApp(index)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun updateSettingLabels() {
        for (index in 1..2) {
            val component = savedApp(index)
            val name = component?.let {
                runCatching { packageManager.getActivityInfo(it, 0).loadLabel(packageManager).toString() }
                    .getOrNull()
            } ?: "Не выбрано"
            settingLabels[index]?.text = "${appTitle(index)}: $name    ›"
        }
    }

    private fun buildSettingsPage(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24.dp(), 8.dp(), 24.dp(), 8.dp())
        addView(label("Настройки", 22f, "#FFFFFF", true))
        addView(label("Приложения по умолчанию", 13f, "#9AA3AD").apply {
            setPadding(0, 4.dp(), 0, 6.dp())
        })
        for (index in 1..2) {
            val row = label("", 16f, "#FFFFFF").apply {
                background = box("#0E1319", 12, "#1E2630")
                setPadding(16.dp(), 10.dp(), 16.dp(), 10.dp())
                isClickable = true
                setOnClickListener { chooseApp(index) }
            }
            settingLabels[index] = row
            addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                bottomMargin = 6.dp()
            })
        }
        updateSettingLabels()
        visibility = View.GONE
    }

    private fun noTrack() {
        Toast.makeText(this, "Нет активного медиаисточника", Toast.LENGTH_SHORT).show()
    }

    private fun transport(action: MediaController.TransportControls.() -> Unit) {
        val c = controller ?: return noTrack()
        c.transportControls.action()
    }

    private fun refreshBattery() {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) {
            batteryPercent.text = "--"
            batteryState.text = "нет данных"
            batteryBar.set(0f)
            return
        }
        val percent = level * 100 / scale
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        batteryPercent.text = "$percent%"
        batteryState.text = if (charging) "заряжается" else "от батареи"
        batteryBar.set(percent / 100f)
    }

    /**
     * Reads the active media session. Android only allows this after the user
     * enables notification access for E90 OS (see the button in the card).
     */
    private fun refreshMedia() {
        val sessions = try {
            getSystemService(MediaSessionManager::class.java)
                .getActiveSessions(ComponentName(this, MediaListenerService::class.java))
        } catch (e: SecurityException) {
            accessButton.visibility = View.VISIBLE
            showNoTrack()
            return
        }
        accessButton.visibility = View.GONE

        val c = sessions.firstOrNull()
        controller = c
        if (c == null) {
            showNoTrack()
            return
        }

        val md: MediaMetadata? = c.metadata
        val st: PlaybackState? = c.playbackState
        isPlaying = st?.state == PlaybackState.STATE_PLAYING

        trackTitle.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: c.packageName
        trackArtist.text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        trackState.text = if (isPlaying) "ИГРАЕТ" else "ПАУЗА"
        playButton.text = if (isPlaying) "❚❚" else "▶"

        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        artImage.setImageBitmap(art)
        artImage.visibility = if (art != null) View.VISIBLE else View.GONE
        artPlaceholder.visibility = if (art != null) View.GONE else View.VISIBLE

        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        var position = st?.position ?: 0L
        if (isPlaying && st != null) {
            position += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        trackBar.set(if (duration > 0) position.toFloat() / duration else 0f)
        positionText.text = fmt(position)
        durationText.text = fmt(duration)
    }

    private fun showNoTrack() {
        controller = null
        isPlaying = false
        trackTitle.text = "Нет активного трека"
        trackArtist.text = "Включите музыку в любом приложении"
        trackState.text = ""
        playButton.text = "▶"
        artImage.visibility = View.GONE
        artPlaceholder.visibility = View.VISIBLE
        trackBar.set(0f)
        positionText.text = "0:00"
        durationText.text = "0:00"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        grid = GridLayout(this).apply { columnCount = 4 }
        appsPage = ScrollView(this).apply {
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            addView(grid)
            visibility = View.GONE
        }
        dashboard = buildCockpitDashboard()
        settingsPage = buildSettingsPage()

        val content = FrameLayout(this).apply {
            addView(dashboard, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(appsPage, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(settingsPage, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bottomNav(), LinearLayout.LayoutParams(134.dp(), MATCH))
            addView(content, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(rgb("#080706"))
            addView(topBar(), LinearLayout.LayoutParams(MATCH, WRAP))
            addView(View(this@MainActivity).apply { setBackgroundColor(rgb("#76502A")) },
                LinearLayout.LayoutParams(MATCH, 1.dp()))
            addView(center, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }

        setContentView(root)
        select(0)
    }

    private fun loadApps() {
        grid.removeAllViews()
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps: List<ResolveInfo> = pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        for (ri in apps) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(8.dp(), 12.dp(), 8.dp(), 12.dp())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val launch = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    runCatching { startActivity(launch) }
                }
            }
            cell.addView(
                ImageView(this).apply { setImageDrawable(ri.loadIcon(pm)) },
                LinearLayout.LayoutParams(56.dp(), 56.dp())
            )
            cell.addView(label(ri.loadLabel(pm).toString(), 13f, "#E6E9EC").apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, 6.dp(), 0, 0)
            })
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            }
            grid.addView(cell, lp)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        select(0)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        // Home screen: ignore Back.
    }
}
