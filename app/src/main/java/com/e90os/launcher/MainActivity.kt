package com.e90os.launcher

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
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
 * E90 OS COCKPIT home screen for a 1280x480 head unit.
 * Side navigation, now-playing card driven by Android media sessions,
 * on-board computer panel (vehicle values need a verified CAN source),
 * four configurable quick-access shortcuts, app grid and settings.
 */
class MainActivity : Activity() {

    // Palette
    private val bg = "#080706"
    private val panel = "#15110F"
    private val panelHi = "#1E1813"
    private val edge = "#3A2A1C"
    private val edgeHi = "#76502A"
    private val amber = "#F3A43D"
    private val amberDeep = "#71400E"
    private val text = "#F7EEE4"
    private val muted = "#B6A99B"
    private val dim = "#7A6E62"

    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE, d MMMM", Locale.forLanguageTag("ru"))

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val navDefaults = listOf("ru.yandex.yandexnavi", "com.google.android.apps.maps")
    private val obdPackage = "org.prowl.torque"

    // Header
    private lateinit var clock: TextView
    private lateinit var dateText: TextView

    // Player
    private lateinit var artImage: ImageView
    private lateinit var artPlaceholder: TextView
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var trackSource: TextView
    private lateinit var playButton: TextView
    private lateinit var trackBar: Bar
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var accessButton: TextView

    // Pages
    private lateinit var dashboard: View
    private lateinit var appsPage: View
    private lateinit var autoPage: View
    private lateinit var settingsPage: ScrollView
    private lateinit var settingsBody: LinearLayout
    private lateinit var grid: GridLayout
    private lateinit var appsCount: TextView
    private val navItems = mutableListOf<LinearLayout>()
    private val navIcons = mutableListOf<TextView>()
    private val navLabels = mutableListOf<TextView>()
    private val shortcutCells = mutableListOf<LinearLayout>()
    private var currentTab = 0

    private var controller: MediaController? = null
    private var isPlaying = false
    private var lastArtKey: Any? = null

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = timeFmt.format(now)
            dateText.text = dateFmt.format(now).replaceFirstChar { it.uppercase() }
            refreshMedia()
            handler.postDelayed(this, 1000)
        }
    }

    // ---------- helpers ----------

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
            includeFontPadding = false
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun TextView.oneLine(): TextView = apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** Visual press feedback for custom buttons. */
    private fun View.pressable(): View = apply {
        isClickable = true
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> v.alpha = 0.6f
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.alpha = 1f
            }
            false
        }
    }

    private fun fmt(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun appLabel(component: ComponentName?): String? = component?.let {
        runCatching { packageManager.getActivityInfo(it, 0).loadLabel(packageManager).toString() }.getOrNull()
            ?: runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(it.packageName, 0)).toString()
            }.getOrNull()
    }

    private fun launchableApps(): List<ResolveInfo> {
        val pm = packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
    }

    private fun launch(component: ComponentName): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { startActivity(intent) }.isSuccess
    }

    private fun launchPackage(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    private fun isInstalled(pkg: String) = packageManager.getLaunchIntentForPackage(pkg) != null

    /** Thin progress bar. [set] takes a fraction from 0 to 1. */
    private inner class Bar(heightDp: Int, color: String) {
        private val fill = View(this@MainActivity).apply { background = box(color, 2) }
        private val rest = View(this@MainActivity)
        val view = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = box("#2A2119", 2)
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

    // ---------- top bar & navigation ----------

    private fun topBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(18.dp(), 0, 18.dp(), 0)
        addView(label("E90 OS", 19f, amber, true))
        addView(label("BMW E90", 13f, muted).apply {
            letterSpacing = 0.3f
            setPadding(12.dp(), 3.dp(), 0, 0)
        })
        addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
        dateText = label("", 14f, muted).apply { setPadding(0, 0, 16.dp(), 0) }
        addView(dateText)
        clock = label("", 22f, text, true)
        addView(clock)
    }

    private fun sideNav(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(rgb("#0F0C0A"))
        setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
        val items = listOf("⌂" to "Главная", "♫" to "Музыка", "➤" to "Карта", "◉" to "Авто", "▦" to "Приложения", "⚙" to "Настройки")
        items.forEachIndexed { index, (icon, title) ->
            val item = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val ic = label(icon, 22f, muted).apply { gravity = Gravity.CENTER }
                val tv = label(title, 12f, muted).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, 4.dp(), 0, 0)
                }
                navIcons.add(ic)
                navLabels.add(tv)
                addView(ic)
                addView(tv)
                setOnClickListener { onTab(index) }
                if (index == 1 || index == 2) setOnLongClickListener { chooseApp(index); true }
                pressable()
            }
            navItems.add(item)
            addView(item, LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
                if (index > 0) topMargin = 4.dp()
            })
        }
    }

    private fun onTab(index: Int) {
        when (index) {
            0 -> select(0)
            1, 2 -> openSelectedApp(index)
            3 -> select(3)
            4 -> {
                select(4)
                loadApps()
            }
            5 -> {
                buildSettingsBody()
                select(5)
            }
        }
    }

    private fun select(index: Int) {
        currentTab = index
        dashboard.visibility = if (index == 0) View.VISIBLE else View.GONE
        autoPage.visibility = if (index == 3) View.VISIBLE else View.GONE
        appsPage.visibility = if (index == 4) View.VISIBLE else View.GONE
        settingsPage.visibility = if (index == 5) View.VISIBLE else View.GONE
        if (index == 3) startGps() else stopGps()
        navItems.forEachIndexed { i, item ->
            val active = i == index
            item.background = if (active) box("#2A1D10", 10, edgeHi) else null
            navIcons[i].setTextColor(rgb(if (active) amber else muted))
            navLabels[i].setTextColor(rgb(if (active) amber else muted))
        }
    }

    // ---------- dashboard ----------

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = box(panel, 12, edge)
    }

    private fun controlButton(glyph: String, size: Int, primary: Boolean, onClick: () -> Unit): TextView =
        label(glyph, if (primary) 24f else 20f, if (primary) "#FFF4E7" else amber, primary).apply {
            gravity = Gravity.CENTER
            background = if (primary) box(amberDeep, size / 2, amber) else box(panelHi, size / 2, edge)
            setOnClickListener { onClick() }
            pressable()
        }

    private fun buildPlayer(): View = card().apply {
        setPadding(16.dp(), 14.dp(), 16.dp(), 12.dp())

        addView(label("СЕЙЧАС ИГРАЕТ", 11f, dim, true).apply { letterSpacing = 0.15f })

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
                clipToOutline = true
                background = box("#241A13", 10)
            }
            addView(artImage, FrameLayout.LayoutParams(MATCH, MATCH))
            setOnClickListener { openSelectedApp(1) }
        }
        top.addView(art, LinearLayout.LayoutParams(124.dp(), 124.dp()))
        val meta = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 0, 0, 0)
            trackTitle = label("Нет активного трека", 21f, text, true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(trackTitle)
            trackArtist = label("", 15f, muted).oneLine()
            addView(trackArtist, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 6.dp() })
            trackSource = label("", 12f, amber).oneLine()
            addView(trackSource, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 8.dp() })
            accessButton = label("Разрешить доступ к музыке  ›", 13f, amber, true).apply {
                visibility = View.GONE
                setPadding(12.dp(), 7.dp(), 12.dp(), 7.dp())
                background = box("#2A1D10", 8, edgeHi)
                setOnClickListener { openNotificationAccess() }
                pressable()
            }
            addView(accessButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 10.dp() })
        }
        top.addView(meta, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(top, LinearLayout.LayoutParams(MATCH, 0, 1f))

        trackBar = Bar(4, amber)
        addView(trackBar.view, LinearLayout.LayoutParams(MATCH, 4.dp()))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4.dp(), 0, 0)
            positionText = label("0:00", 11f, muted)
            durationText = label("0:00", 11f, muted)
            addView(positionText)
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(durationText)
        })
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            addView(controlButton("⏮", 50, false) { mediaAction(KeyEvent.KEYCODE_MEDIA_PREVIOUS) },
                LinearLayout.LayoutParams(50.dp(), 50.dp()))
            playButton = controlButton("▶", 58, true) { togglePlay() }
            addView(playButton, LinearLayout.LayoutParams(58.dp(), 58.dp()).apply {
                leftMargin = 22.dp(); rightMargin = 22.dp()
            })
            addView(controlButton("⏭", 50, false) { mediaAction(KeyEvent.KEYCODE_MEDIA_NEXT) },
                LinearLayout.LayoutParams(50.dp(), 50.dp()))
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 2.dp() })
    }

    private fun dataCell(name: String, value: String, unit: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        background = box(panelHi, 10, edge)
        setPadding(12.dp(), 6.dp(), 8.dp(), 6.dp())
        addView(label(name, 11f, muted).oneLine())
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(label(value, 22f, dim, true))
            addView(label(" $unit", 12f, dim).apply { setPadding(0, 0, 0, 3.dp()) })
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 4.dp() })
    }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, MATCH, 1f).apply { if (i > 0) leftMargin = 8.dp() })
        }
    }

    private fun buildCarPanel(): View = card().apply {
        setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
        setOnClickListener { onTab(3) }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("БОРТОВОЙ КОМПЬЮТЕР", 13f, text, true).apply { letterSpacing = 0.08f })
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(label("●  CAN: нет связи", 11f, "#D9734A").apply {
                setPadding(10.dp(), 4.dp(), 10.dp(), 4.dp())
                background = box("#2B1610", 10, "#5A2A1A")
            })
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        val obd = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = box("#2A1D10", 10, edgeHi)
            addView(label("OBD", 18f, amber, true).apply { gravity = Gravity.CENTER })
            addView(label(if (isInstalled(obdPackage)) "Открыть Torque ›" else "Torque не установлен", 11f, muted)
                .apply { gravity = Gravity.CENTER; setPadding(0, 3.dp(), 0, 0) })
            setOnClickListener { if (!launchPackage(obdPackage)) toast("Torque не установлен") }
            pressable()
        }
        addView(row(dataCell("Напряжение АКБ", "—", "В"), dataCell("Температура ОЖ", "—", "°C"),
            dataCell("Масло", "—", "")), LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = 8.dp() })
        addView(row(dataCell("Запас хода", "—", "км"), dataCell("Расход", "—", "л/100"), obd),
            LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = 8.dp() })
    }

    private fun buildQuickAccess(): View = card().apply {
        setPadding(12.dp(), 8.dp(), 8.dp(), 8.dp())
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("БЫСТРЫЙ ДОСТУП", 11f, dim, true).apply { letterSpacing = 0.15f })
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(label("✎  Изменить", 12f, amber).apply {
                setPadding(10.dp(), 3.dp(), 10.dp(), 3.dp())
                setOnClickListener { editQuickAccess() }
                pressable()
            })
        })
        val strip = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        repeat(4) { index ->
            val cell = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10.dp(), 0, 6.dp(), 0)
                background = box(panelHi, 10, edge)
                setOnClickListener { openQuickApp(index) }
                setOnLongClickListener { chooseQuickApp(index); true }
                pressable()
            }
            shortcutCells.add(cell)
            strip.addView(cell, LinearLayout.LayoutParams(0, MATCH, 1f).apply { if (index > 0) leftMargin = 8.dp() })
        }
        addView(strip, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = 6.dp() })
    }

    private fun buildCockpitDashboard(): View {
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buildCarPanel(), LinearLayout.LayoutParams(MATCH, 0, 1.75f))
            addView(buildQuickAccess(), LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = 10.dp() })
        }
        refreshQuickAccess()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            addView(buildPlayer(), LinearLayout.LayoutParams(0, MATCH, 1f).apply { rightMargin = 10.dp() })
            addView(right, LinearLayout.LayoutParams(0, MATCH, 1.3f))
        }
    }

    // ---------- quick access ----------

    private fun quickComponent(index: Int): ComponentName? =
        getPreferences(MODE_PRIVATE).getString("quick_$index", null)?.let(ComponentName::unflattenFromString)

    private fun refreshQuickAccess() {
        shortcutCells.forEachIndexed { index, cell ->
            cell.removeAllViews()
            val component = quickComponent(index)
            val icon = component?.let { runCatching { packageManager.getActivityIcon(it) }.getOrNull() }
            if (icon != null) {
                cell.addView(ImageView(this).apply { setImageDrawable(icon) },
                    LinearLayout.LayoutParams(34.dp(), 34.dp()).apply { rightMargin = 8.dp() })
            } else {
                cell.addView(label("+", 22f, amber, true), LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = 8.dp() })
            }
            cell.addView(label(appLabel(component) ?: "Добавить", 13f, if (component == null) amber else text).oneLine(),
                LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    private fun pickApp(title: String, allowClear: Boolean, onPick: (ComponentName?) -> Unit) {
        val pm = packageManager
        val apps = launchableApps()
        if (apps.isEmpty()) return toast("Нет доступных приложений")
        val names = apps.map { it.loadLabel(pm).toString() }.toMutableList()
        if (allowClear) names.add(0, "— Убрать —")
        AlertDialog.Builder(this).setTitle(title)
            .setItems(names.toTypedArray()) { _, choice ->
                val idx = if (allowClear) choice - 1 else choice
                onPick(if (idx < 0) null else apps[idx].activityInfo.let { ComponentName(it.packageName, it.name) })
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun setQuick(index: Int, component: ComponentName?) {
        getPreferences(MODE_PRIVATE).edit().putString("quick_$index", component?.flattenToString()).apply()
        refreshQuickAccess()
    }

    private fun chooseQuickApp(index: Int) =
        pickApp("Ярлык ${index + 1}", quickComponent(index) != null) { setQuick(index, it) }

    private fun editQuickAccess() {
        val items = (0 until 4).map { "Ярлык ${it + 1}: ${appLabel(quickComponent(it)) ?: "пусто"}" }
        AlertDialog.Builder(this).setTitle("Быстрый доступ")
            .setItems(items.toTypedArray()) { _, index -> chooseQuickApp(index) }
            .setNegativeButton("Готово", null)
            .show()
    }

    private fun openQuickApp(index: Int) {
        val component = quickComponent(index) ?: return chooseQuickApp(index)
        if (!launch(component)) {
            setQuick(index, null)
            toast("Приложение недоступно")
        }
    }

    // ---------- music / navigation apps ----------

    private fun appKey(index: Int) = if (index == 1) "music_app" else "navigation_app"
    private fun appTitle(index: Int) = if (index == 1) "Музыка" else "Навигация"

    private fun savedApp(index: Int): ComponentName? =
        getPreferences(MODE_PRIVATE).getString(appKey(index), null)?.let(ComponentName::unflattenFromString)

    /** Saved app, or for navigation the first installed known navigator. */
    private fun effectiveApp(index: Int): ComponentName? {
        savedApp(index)?.let { return it }
        if (index == 2) {
            for (pkg in navDefaults) {
                packageManager.getLaunchIntentForPackage(pkg)?.component?.let { return it }
            }
        }
        return null
    }

    private fun openSelectedApp(index: Int) {
        val component = effectiveApp(index) ?: return chooseApp(index)
        if (!launch(component)) {
            getPreferences(MODE_PRIVATE).edit().remove(appKey(index)).apply()
            toast("Приложение недоступно. Выберите другое")
            chooseApp(index)
        }
    }

    private fun chooseApp(index: Int) = pickApp("Приложение: ${appTitle(index)}", false) { component ->
        if (component == null) return@pickApp
        getPreferences(MODE_PRIVATE).edit().putString(appKey(index), component.flattenToString()).apply()
        if (currentTab == 5) buildSettingsBody()
        launch(component)
    }

    // ---------- settings ----------

    private fun hasNotificationAccess(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(ComponentName(this, MediaListenerService::class.java).flattenToString()) == true

    private fun openNotificationAccess() {
        runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            .onFailure { toast("Экран настроек недоступен") }
    }

    private fun settingsSection(title: String) {
        settingsBody.addView(label(title.uppercase(), 11f, dim, true).apply {
            letterSpacing = 0.15f
            setPadding(4.dp(), 14.dp(), 0, 6.dp())
        })
    }

    private fun settingsRow(title: String, value: String, valueColor: String = muted, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = box(panel, 10, edge)
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            addView(label(title, 16f, text), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(label(value, 14f, valueColor).oneLine())
            addView(label("  ›", 18f, amber))
            setOnClickListener { onClick() }
            pressable()
        }
        settingsBody.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 6.dp() })
    }

    private fun buildSettingsBody() {
        settingsBody.removeAllViews()
        settingsBody.addView(label("Настройки", 22f, text, true))

        settingsSection("Приложения по умолчанию")
        settingsRow("Музыка", appLabel(savedApp(1)) ?: "Не выбрано") { chooseApp(1) }
        val nav = savedApp(2)
        settingsRow("Навигация", appLabel(nav) ?: appLabel(effectiveApp(2))?.let { "$it (авто)" } ?: "Не выбрано") {
            chooseApp(2)
        }

        settingsSection("Главный экран")
        val filled = (0 until 4).count { quickComponent(it) != null }
        settingsRow("Быстрый доступ", "$filled из 4") { editQuickAccess() }
        val access = hasNotificationAccess()
        settingsRow("Доступ к музыке", if (access) "Разрешён" else "Не разрешён",
            if (access) "#8FBF6A" else "#D9734A") { openNotificationAccess() }

        settingsSection("Система")
        settingsRow("Лаунчер по умолчанию", "") {
            runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
        }
        settingsRow("Настройки Android", "") { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        settingsBody.addView(label("E90 OS COCKPIT  $version", 11f, dim).apply {
            setPadding(4.dp(), 12.dp(), 0, 0)
        })
    }

    // ---------- media ----------

    private fun mediaSessions(): List<MediaController>? = try {
        getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(this, MediaListenerService::class.java))
    } catch (e: SecurityException) {
        null
    }

    /** Prefer a playing session, then any session that has track metadata. */
    private fun pickSession(sessions: List<MediaController>): MediaController? =
        sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null }

    private fun sendMediaKey(code: Int) {
        val am = getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun mediaAction(code: Int) {
        val c = controller
        if (c != null) {
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> c.transportControls.skipToPrevious()
                KeyEvent.KEYCODE_MEDIA_NEXT -> c.transportControls.skipToNext()
            }
        } else {
            sendMediaKey(code)
        }
        handler.postDelayed({ refreshMedia() }, 300)
    }

    private fun togglePlay() {
        val c = controller
        if (c != null) {
            if (isPlaying) c.transportControls.pause() else c.transportControls.play()
            handler.postDelayed({ refreshMedia() }, 300)
            return
        }
        // No active session: wake the last media app, then fall back to the chosen music app.
        sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        handler.postDelayed({
            refreshMedia()
            if (controller == null) {
                if (effectiveApp(1) != null) openSelectedApp(1) else toast("Выберите приложение для музыки")
            }
        }, 1500)
    }

    private fun refreshMedia() {
        val sessions = mediaSessions()
        if (sessions == null) {
            accessButton.visibility = View.VISIBLE
            showNoTrack()
            return
        }
        accessButton.visibility = View.GONE

        val c = pickSession(sessions)
        controller = c
        if (c == null) {
            showNoTrack()
            return
        }

        val md: MediaMetadata? = c.metadata
        val st: PlaybackState? = c.playbackState
        isPlaying = st?.state == PlaybackState.STATE_PLAYING

        val source = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrDefault(c.packageName)
        trackTitle.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: source
        trackArtist.text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        trackSource.text = (if (isPlaying) "▶  " else "❚❚  ") + source
        playButton.text = if (isPlaying) "❚❚" else "▶"

        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (art !== lastArtKey) {
            lastArtKey = art
            artImage.setImageBitmap(art)
            artImage.visibility = if (art != null) View.VISIBLE else View.GONE
            artPlaceholder.visibility = if (art != null) View.GONE else View.VISIBLE
        }

        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        var position = st?.position ?: 0L
        if (isPlaying && st != null) {
            position += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        if (duration > 0) position = position.coerceAtMost(duration)
        trackBar.set(if (duration > 0) position.toFloat() / duration else 0f)
        positionText.text = fmt(position)
        durationText.text = if (duration > 0) fmt(duration) else "--:--"
    }

    private fun showNoTrack() {
        controller = null
        isPlaying = false
        lastArtKey = null
        trackTitle.text = "Нет активного трека"
        trackArtist.text = "Нажмите ▶, чтобы продолжить"
        trackSource.text = appLabel(effectiveApp(1))?.let { "Плеер: $it" } ?: ""
        playButton.text = "▶"
        artImage.setImageBitmap(null)
        artImage.visibility = View.GONE
        artPlaceholder.visibility = View.VISIBLE
        trackBar.set(0f)
        positionText.text = "0:00"
        durationText.text = "0:00"
    }

    // ---------- auto page ----------

    private lateinit var speedGauge: GaugeView
    private lateinit var rpmGauge: GaugeView
    private lateinit var speedSource: TextView
    private var locationListener: LocationListener? = null

    private fun iconBadge(glyph: String): TextView = label(glyph, if (glyph.length > 1) 12f else 18f, amber, glyph.length > 1).apply {
        gravity = Gravity.CENTER
        background = box("#2A1D10", 8, edge)
    }

    /** "Name / value unit" block with an icon on the left. */
    private fun metric(glyph: String, name: String, value: String, unit: String, big: Boolean = false): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconBadge(glyph), LinearLayout.LayoutParams(34.dp(), 34.dp()).apply { rightMargin = 10.dp() })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(name, 11f, muted).oneLine())
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.BOTTOM
                    addView(label(value, if (big) 26f else 20f, text, true))
                    addView(label(" $unit", 12f, muted).apply { setPadding(0, 0, 0, 3.dp()) })
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 2.dp() })
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }

    private fun levelMetric(glyph: String, name: String, value: String, unit: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(metric(glyph, name, value, unit))
            addView(SegmentBar(this@MainActivity), LinearLayout.LayoutParams(MATCH, 6.dp()).apply {
                topMargin = 6.dp(); leftMargin = 44.dp()
            })
        }

    private fun bottomCard(vararg parts: View): LinearLayout = card().apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
        parts.forEachIndexed { i, v ->
            if (i > 0) addView(View(this@MainActivity).apply { setBackgroundColor(rgb(edge)) },
                LinearLayout.LayoutParams(1.dp(), MATCH).apply { leftMargin = 10.dp(); rightMargin = 10.dp() })
            addView(v, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    private fun buildAutoPage(): View {
        speedGauge = GaugeView(this, 260f, 20f, 4, 1f, "км/ч").apply { caption = "нет сигнала GPS" }
        rpmGauge = GaugeView(this, 7000f, 1000f, 5, 1000f, "×1000 об/мин", redFrom = 6000f).apply {
            readout = "—"; caption = "CAN: нет связи"
        }

        val centre = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp(), 0, 8.dp(), 0)
            addView(metric("t°", "Наружная t°", "—", "°C", big = true))
            addView(View(this@MainActivity).apply { setBackgroundColor(rgb(edge)) },
                LinearLayout.LayoutParams(MATCH, 1.dp()).apply { topMargin = 12.dp(); bottomMargin = 12.dp() })
            addView(metric("км", "Запас хода", "—", "км", big = true))
            addView(View(this@MainActivity).apply { setBackgroundColor(rgb(edge)) },
                LinearLayout.LayoutParams(MATCH, 1.dp()).apply { topMargin = 12.dp(); bottomMargin = 12.dp() })
            addView(metric("Ø", "Расход", "—", "л/100 км", big = true))
            speedSource = label("Скорость: GPS  ·  остальное: CAN не подключён", 10f, dim).apply {
                gravity = Gravity.CENTER
                setPadding(0, 12.dp(), 0, 0)
            }
            addView(speedSource, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        val gauges = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(speedGauge, LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(centre, LinearLayout.LayoutParams(250.dp(), WRAP))
            addView(rpmGauge, LinearLayout.LayoutParams(0, MATCH, 1f))
        }

        val route = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
            addView(iconBadge("➤"), LinearLayout.LayoutParams(34.dp(), 34.dp()).apply { rightMargin = 10.dp() })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Карта · Маршрут", 11f, muted))
                addView(label("Нет активного маршрута", 15f, text, true).oneLine(),
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 3.dp() })
                addView(label((appLabel(effectiveApp(2)) ?: "Навигатор") + "  ›", 12f, amber),
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 3.dp() })
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            setOnClickListener { openSelectedApp(2) }
            pressable()
        }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bottomCard(levelMetric("OIL", "Уровень масла", "—", "%"), metric("⚡", "Напряжение АКБ", "—", "В")),
                LinearLayout.LayoutParams(0, MATCH, 1.25f))
            addView(route, LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = 10.dp(); rightMargin = 10.dp() })
            addView(bottomCard(levelMetric("≋", "ОЖ", "—", "°C"), metric("t°", "Впуск", "—", "°C")),
                LinearLayout.LayoutParams(0, MATCH, 1.25f))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 6.dp(), 10.dp(), 10.dp())
            addView(gauges, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(bottom, LinearLayout.LayoutParams(MATCH, 92.dp()).apply { topMargin = 6.dp() })
            visibility = View.GONE
        }
    }

    private fun showSpeed(kmh: Float?) {
        speedGauge.value = kmh
        speedGauge.readout = kmh?.let { Math.round(it).toString() } ?: "—"
        speedGauge.caption = if (kmh == null) "нет сигнала GPS" else "GPS"
    }

    private fun startGps() {
        if (locationListener != null) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            showSpeed(null)
            speedGauge.caption = "нужно разрешение GPS"
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 7)
            return
        }
        val lm = getSystemService(LocationManager::class.java) ?: return
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                val fresh = SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos < 5_000_000_000L
                showSpeed(if (loc.hasSpeed() && fresh) loc.speed * 3.6f else if (fresh) 0f else null)
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) = showSpeed(null)
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, listener, Looper.getMainLooper())
            locationListener = listener
            showSpeed(null)
            speedGauge.caption = "поиск GPS…"
        } catch (e: Exception) {
            showSpeed(null)
            speedGauge.caption = "GPS недоступен"
        }
    }

    private fun stopGps() {
        val l = locationListener ?: return
        runCatching { getSystemService(LocationManager::class.java)?.removeUpdates(l) }
        locationListener = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && currentTab == 3) startGps()
    }

    // ---------- apps page ----------

    private fun loadApps() {
        grid.removeAllViews()
        val pm = packageManager
        val apps = launchableApps()
        appsCount.text = "${apps.size}"
        for (ri in apps) {
            val name = ri.loadLabel(pm).toString()
            val component = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(6.dp(), 10.dp(), 6.dp(), 10.dp())
                setOnClickListener { launch(component) }
                setOnLongClickListener { addToQuick(component, name); true }
                pressable()
            }
            cell.addView(ImageView(this).apply { setImageDrawable(ri.loadIcon(pm)) },
                LinearLayout.LayoutParams(56.dp(), 56.dp()))
            cell.addView(label(name, 13f, text).apply {
                gravity = Gravity.CENTER
                setPadding(0, 6.dp(), 0, 0)
            }.oneLine(), LinearLayout.LayoutParams(MATCH, WRAP))
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            })
        }
    }

    private fun addToQuick(component: ComponentName, name: String) {
        val items = (0 until 4).map { "Ярлык ${it + 1}: ${appLabel(quickComponent(it)) ?: "пусто"}" }
        AlertDialog.Builder(this).setTitle("$name → быстрый доступ")
            .setItems(items.toTypedArray()) { _, index ->
                setQuick(index, component)
                toast("Добавлено на главный экран")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun buildAppsPage(): View {
        grid = GridLayout(this).apply { columnCount = 6 }
        appsCount = label("", 13f, dim)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp(), 0, 8.dp(), 6.dp())
            addView(label("Приложения", 22f, text, true))
            addView(appsCount.apply { setPadding(10.dp(), 4.dp(), 0, 0) })
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(label("Удерживайте значок, чтобы добавить в быстрый доступ", 12f, dim))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 12.dp(), 12.dp(), 0)
            addView(header)
            addView(ScrollView(this@MainActivity).apply {
                isVerticalScrollBarEnabled = false
                addView(grid)
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
            visibility = View.GONE
        }
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appsPage = buildAppsPage()
        autoPage = buildAutoPage()
        dashboard = buildCockpitDashboard()
        settingsBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 12.dp(), 16.dp(), 16.dp())
        }
        settingsPage = ScrollView(this).apply {
            addView(settingsBody)
            visibility = View.GONE
        }

        val content = FrameLayout(this).apply {
            addView(dashboard, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(autoPage, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(appsPage, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(settingsPage, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(sideNav(), LinearLayout.LayoutParams(112.dp(), MATCH))
            addView(View(this@MainActivity).apply { setBackgroundColor(rgb(edge)) },
                LinearLayout.LayoutParams(1.dp(), MATCH))
            addView(content, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(rgb(bg))
            addView(topBar(), LinearLayout.LayoutParams(MATCH, 44.dp()))
            addView(View(this@MainActivity).apply { setBackgroundColor(rgb(edgeHi)) },
                LinearLayout.LayoutParams(MATCH, 1.dp()))
            addView(center, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }

        setContentView(root)
        select(0)
    }

    override fun onResume() {
        super.onResume()
        refreshQuickAccess()
        if (currentTab == 5) buildSettingsBody()
        handler.removeCallbacks(tick)
        handler.post(tick)
        if (currentTab == 3) startGps()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        stopGps()
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
