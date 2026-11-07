package com.e90os.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
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
 * E90 OS COCKPIT launcher for a 1280x480 head unit.
 * Tabs: Главная (player, on-board computer, quick access), Музыка and Карта (launch the
 * chosen apps), Авто (gauges), Настройки. Vehicle values need a verified CAN source and
 * show a dash until one exists; speed on the Авто tab comes from GPS.
 */
class MainActivity : Activity() {

    // Palette
    private val bg = "#080706"
    private val panel = "#14100D"
    private val panelHi = "#1B1511"
    private val edge = "#3A2A1C"
    private val edgeHi = "#76502A"
    private val amber = "#F3A43D"
    private val text = "#F7EEE4"
    private val soft = "#E2D6CA"
    private val muted = "#B6A99B"
    private val dim = "#7A6E62"

    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val navDefaults = listOf("ru.yandex.yandexnavi", "com.google.android.apps.maps")

    // Tabs
    private val TAB_HOME = 0
    private val TAB_MUSIC = 1
    private val TAB_MAP = 2
    private val TAB_AUTO = 3
    private val TAB_SETTINGS = 4
    private val TAB_APPS = 5

    // Header
    private lateinit var clock: TextView
    private lateinit var btIcon: IconView
    private lateinit var wifiIcon: IconView

    // Player
    private lateinit var artImage: ImageView
    private lateinit var artPlaceholder: View
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var playIcon: IconView
    private lateinit var seek: SeekLine
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var accessButton: TextView

    // Pages
    private lateinit var dashboard: View
    private lateinit var autoPage: View
    private lateinit var appsPage: View
    private lateinit var settingsPage: ScrollView
    private lateinit var settingsBody: LinearLayout
    private lateinit var grid: GridLayout
    private lateinit var appsCount: TextView
    private val navItems = mutableListOf<LinearLayout>()
    private val navIcons = mutableListOf<IconView>()
    private val navLabels = mutableListOf<TextView>()
    private val shortcutCells = mutableListOf<LinearLayout>()
    private var currentTab = TAB_HOME

    private var controller: MediaController? = null
    private var isPlaying = false
    private var lastArt: Any? = null

    private val tick = object : Runnable {
        override fun run() {
            clock.text = timeFmt.format(Date())
            refreshStatusIcons()
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

    private fun grad(top: String, bottom: String, radius: Int, strokeHex: String?): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(rgb(top), rgb(bottom))).apply {
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

    private fun icon(i: Ico, hex: String, stroke: Float = 1.7f) = IconView(this, i, rgb(hex), stroke)

    /** Visual press feedback for custom buttons. */
    private fun <T : View> T.pressable(): T = apply {
        isClickable = true
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.alpha = 0.6f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
            }
            false
        }
    }

    private fun spacer() = View(this)
    private fun hLine(hex: String = edge) = View(this).apply { setBackgroundColor(rgb(hex)) }

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

    // ---------- top bar & navigation ----------

    private fun topBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(22.dp(), 0, 22.dp(), 0)
        addView(label("E90 OS", 23f, text, true))
        addView(label("BMW E90", 14f, muted).apply { setPadding(16.dp(), 3.dp(), 0, 0) })
        addView(spacer(), LinearLayout.LayoutParams(0, 1, 1f))
        btIcon = icon(Ico.BT, text, 1.8f)
        addView(btIcon, LinearLayout.LayoutParams(20.dp(), 20.dp()).apply { rightMargin = 16.dp() })
        wifiIcon = icon(Ico.WIFI, text, 1.9f)
        addView(wifiIcon, LinearLayout.LayoutParams(22.dp(), 20.dp()).apply { rightMargin = 16.dp() })
        addView(icon(Ico.SIGNAL, text, 1.8f), LinearLayout.LayoutParams(20.dp(), 20.dp()).apply { rightMargin = 22.dp() })
        clock = label("", 22f, text)
        addView(clock)
    }

    private fun refreshStatusIcons() {
        val bt = runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled == true }.getOrDefault(false)
        val wifi = runCatching { applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true }
            .getOrDefault(false)
        btIcon.color = rgb(if (bt) text else "#4A4038")
        wifiIcon.color = rgb(if (wifi) text else "#4A4038")
    }

    private fun sideNav(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = grad("#120E0B", "#0B0908", 14, "#2A1F16")
        setPadding(6.dp(), 10.dp(), 6.dp(), 10.dp())
        val items = listOf(Ico.HOME to "Главная", Ico.MUSIC to "Музыка", Ico.PIN to "Карта", Ico.CAR to "Авто", Ico.GEAR to "Настройки")
        items.forEachIndexed { index, (ic, title) ->
            val item = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12.dp(), 0, 6.dp(), 0)
                val iv = icon(ic, text, 1.7f)
                val tv = label(title, 15f, text).oneLine()
                navIcons.add(iv)
                navLabels.add(tv)
                addView(iv, LinearLayout.LayoutParams(22.dp(), 22.dp()).apply { rightMargin = 12.dp() })
                addView(tv, LinearLayout.LayoutParams(0, WRAP, 1f))
                setOnClickListener { onTab(index) }
                if (index == TAB_MUSIC || index == TAB_MAP) setOnLongClickListener { chooseApp(index); true }
                pressable()
            }
            navItems.add(item)
            addView(item, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = 4.dp(); bottomMargin = 4.dp() })
        }
    }

    private fun onTab(index: Int) {
        when (index) {
            TAB_MUSIC, TAB_MAP -> openSelectedApp(index)
            TAB_APPS -> { select(TAB_APPS); loadApps() }
            TAB_SETTINGS -> { buildSettingsBody(); select(TAB_SETTINGS) }
            else -> select(index)
        }
    }

    private fun select(index: Int) {
        currentTab = index
        dashboard.visibility = if (index == TAB_HOME) View.VISIBLE else View.GONE
        autoPage.visibility = if (index == TAB_AUTO) View.VISIBLE else View.GONE
        appsPage.visibility = if (index == TAB_APPS) View.VISIBLE else View.GONE
        settingsPage.visibility = if (index == TAB_SETTINGS) View.VISIBLE else View.GONE
        if (index == TAB_AUTO) startGps() else stopGps()
        val activeNav = if (index == TAB_APPS) TAB_SETTINGS else index
        navItems.forEachIndexed { i, item ->
            val active = i == activeNav
            item.background = if (active) grad("#4E3424", "#33221A", 10, "#5A3E2A") else null
            navIcons[i].color = rgb(if (active) "#FFE3C2" else soft)
            navLabels[i].setTextColor(rgb(if (active) "#FFFFFF" else soft))
            navLabels[i].typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    // ---------- dashboard ----------

    private fun card(radius: Int = 12): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = grad("#1A1410", "#100C0A", radius, edge)
    }

    private fun transportButton(ic: Ico, primary: Boolean, onClick: () -> Unit): Pair<FrameLayout, IconView> {
        val iv = icon(ic, if (primary) amber else text)
        val frame = FrameLayout(this).apply {
            background = if (primary) grad("#6A3E12", "#3A220C", 10, amber) else grad("#1E1813", "#15110E", 10, edge)
            addView(iv, FrameLayout.LayoutParams(26.dp(), 26.dp(), Gravity.CENTER))
            setOnClickListener { onClick() }
            pressable()
        }
        return frame to iv
    }

    private fun buildPlayer(): View = card().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(18.dp(), 14.dp(), 18.dp(), 14.dp())

        val art = FrameLayout(this@MainActivity).apply {
            background = grad("#2A1D14", "#140F0C", 12, edge)
            artPlaceholder = icon(Ico.MUSIC, "#6A4A2A", 1.4f)
            addView(artPlaceholder, FrameLayout.LayoutParams(84.dp(), 84.dp(), Gravity.CENTER))
            artImage = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                visibility = View.GONE
                background = box("#140F0C", 12)
                clipToOutline = true
            }
            addView(artImage, FrameLayout.LayoutParams(MATCH, MATCH))
            setOnClickListener { openSelectedApp(TAB_MUSIC) }
        }
        addView(art, LinearLayout.LayoutParams(186.dp(), 186.dp()))

        trackTitle = label("Нет активного трека", 24f, text, true).apply { gravity = Gravity.CENTER }.oneLine()
        addView(trackTitle, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 12.dp() })
        trackArtist = label("", 17f, muted).apply { gravity = Gravity.CENTER }.oneLine()
        addView(trackArtist, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 5.dp() })
        accessButton = label("Разрешить доступ к музыке  ›", 13f, amber, true).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(12.dp(), 6.dp(), 12.dp(), 6.dp())
            background = box("#2A1D10", 8, edgeHi)
            setOnClickListener { openNotificationAccess() }
            pressable()
        }
        addView(accessButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 6.dp() })

        addView(spacer(), LinearLayout.LayoutParams(1, 0, 1f))

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            positionText = label("0:00", 14f, muted)
            durationText = label("0:00", 14f, muted)
            seek = SeekLine(this@MainActivity)
            addView(positionText, LinearLayout.LayoutParams(44.dp(), WRAP))
            addView(seek, LinearLayout.LayoutParams(0, 18.dp(), 1f).apply { leftMargin = 6.dp(); rightMargin = 6.dp() })
            addView(durationText.apply { gravity = Gravity.END }, LinearLayout.LayoutParams(44.dp(), WRAP))
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            val (prev, _) = transportButton(Ico.PREV, false) { mediaAction(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
            val (play, pi) = transportButton(Ico.PLAY, true) { togglePlay() }
            val (next, _) = transportButton(Ico.NEXT, false) { mediaAction(KeyEvent.KEYCODE_MEDIA_NEXT) }
            playIcon = pi
            addView(prev, LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(play, LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = 12.dp(); rightMargin = 12.dp() })
            addView(next, LinearLayout.LayoutParams(0, MATCH, 1f))
        }, LinearLayout.LayoutParams(MATCH, 52.dp()).apply { topMargin = 10.dp() })
    }

    /** Icon + title + big value card used by the on-board computer. */
    private fun valueCard(ic: Ico, title: String, value: String, unit: String): LinearLayout = card().apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(16.dp(), 6.dp(), 12.dp(), 6.dp())
        addView(icon(ic, amber, 1.6f), LinearLayout.LayoutParams(46.dp(), 46.dp()).apply { rightMargin = 16.dp() })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 15f, soft).oneLine())
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                addView(label(value, 30f, text, true))
                addView(label(" $unit", 19f, text, true).apply { setPadding(0, 0, 0, 2.dp()) })
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 4.dp() })
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    private fun pair(a: View, b: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, MATCH, 1f))
        addView(b, LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = 10.dp() })
    }

    private fun buildCarPanel(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(hLine(amber), LinearLayout.LayoutParams(2.dp(), 22.dp()).apply { rightMargin = 12.dp() })
            addView(label("БОРТОВОЙ КОМПЬЮТЕР", 17f, text, true).apply { letterSpacing = 0.06f })
            addView(spacer(), LinearLayout.LayoutParams(0, 1, 1f))
            addView(hLine(amber), LinearLayout.LayoutParams(2.dp(), 22.dp()).apply { rightMargin = 14.dp() })
            addView(icon(Ico.CLOUD, text, 1.6f), LinearLayout.LayoutParams(28.dp(), 28.dp()).apply { rightMargin = 10.dp() })
            addView(label("— °C", 22f, text, true))
            setPadding(2.dp(), 0, 8.dp(), 0)
            setOnClickListener { select(TAB_AUTO) }
        }, LinearLayout.LayoutParams(MATCH, 34.dp()))

        addView(pair(valueCard(Ico.BATTERY, "Напряжение", "—", "В"), valueCard(Ico.COOLANT, "Температура ОЖ", "—", "°C")),
            LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = 8.dp() })

        val oil = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp(), 6.dp(), 18.dp(), 6.dp())
            addView(icon(Ico.OIL, amber, 1.6f), LinearLayout.LayoutParams(46.dp(), 46.dp()).apply { rightMargin = 16.dp() })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Уровень масла", 15f, soft))
                addView(SegmentBar(this@MainActivity, 6), LinearLayout.LayoutParams(MATCH, 12.dp()).apply { topMargin = 8.dp() })
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(hLine(edgeHi), LinearLayout.LayoutParams(1.dp(), 26.dp()).apply { leftMargin = 16.dp(); rightMargin = 16.dp() })
            addView(label("—", 18f, amber, true))
        }
        addView(oil, LinearLayout.LayoutParams(MATCH, 0, 0.8f).apply { topMargin = 8.dp() })

        addView(pair(valueCard(Ico.FUEL, "Запас хода", "—", "км"), valueCard(Ico.FUEL_DROP, "Расход", "—", "л/100 км")),
            LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = 8.dp() })
    }

    private fun buildQuickAccess(): View = card().apply {
        setPadding(14.dp(), 6.dp(), 12.dp(), 8.dp())
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Быстрый доступ", 16f, text, true))
            addView(hLine(edge), LinearLayout.LayoutParams(0, 1.dp(), 1f).apply { leftMargin = 14.dp(); rightMargin = 14.dp() })
            addView(icon(Ico.PENCIL, amber, 1.8f).apply {
                setOnClickListener { editQuickAccess() }
                pressable()
            }, LinearLayout.LayoutParams(24.dp(), 24.dp()))
        }, LinearLayout.LayoutParams(MATCH, 26.dp()))
        val strip = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        repeat(4) { index ->
            val cell = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = grad("#211A14", "#17120F", 10, edge)
                setOnClickListener { openQuickApp(index) }
                setOnLongClickListener { chooseQuickApp(index); true }
                pressable()
            }
            shortcutCells.add(cell)
            strip.addView(cell, LinearLayout.LayoutParams(0, MATCH, 1f).apply { if (index > 0) leftMargin = 12.dp() })
        }
        addView(strip, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = 6.dp() })
    }

    private fun buildCockpitDashboard(): View {
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buildCarPanel(), LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(buildQuickAccess(), LinearLayout.LayoutParams(MATCH, 112.dp()).apply { topMargin = 10.dp() })
        }
        refreshQuickAccess()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            addView(buildPlayer(), LinearLayout.LayoutParams(0, MATCH, 0.78f).apply { rightMargin = 16.dp() })
            addView(right, LinearLayout.LayoutParams(0, MATCH, 1.22f))
        }
    }

    // ---------- quick access ----------

    private fun quickComponent(index: Int): ComponentName? =
        getPreferences(MODE_PRIVATE).getString("quick_$index", null)?.let(ComponentName::unflattenFromString)

    private fun refreshQuickAccess() {
        shortcutCells.forEachIndexed { index, cell ->
            cell.removeAllViews()
            val component = quickComponent(index)
            val appIcon = component?.let { runCatching { packageManager.getActivityIcon(it) }.getOrNull() }
            cell.contentDescription = appLabel(component) ?: "Добавить"
            if (appIcon != null) {
                cell.addView(ImageView(this).apply { setImageDrawable(appIcon) }, LinearLayout.LayoutParams(54.dp(), 54.dp()))
            } else {
                cell.addView(icon(Ico.PLUS, amber, 1.5f), LinearLayout.LayoutParams(40.dp(), 40.dp()))
            }
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

    private fun appKey(index: Int) = if (index == TAB_MUSIC) "music_app" else "navigation_app"
    private fun appTitle(index: Int) = if (index == TAB_MUSIC) "Музыка" else "Навигация"

    private fun savedApp(index: Int): ComponentName? =
        getPreferences(MODE_PRIVATE).getString(appKey(index), null)?.let(ComponentName::unflattenFromString)

    /** Saved app, or for navigation the first installed known navigator. */
    private fun effectiveApp(index: Int): ComponentName? {
        savedApp(index)?.let { return it }
        if (index == TAB_MAP) {
            for (pkg in navDefaults) packageManager.getLaunchIntentForPackage(pkg)?.component?.let { return it }
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
        if (currentTab == TAB_SETTINGS) buildSettingsBody()
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
        settingsBody.addView(label(title.uppercase(), 12f, dim, true).apply {
            letterSpacing = 0.15f
            setPadding(4.dp(), 14.dp(), 0, 6.dp())
        })
    }

    private fun settingsRow(ic: Ico, title: String, value: String, valueColor: String = muted, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = grad("#1A1410", "#100C0A", 10, edge)
            setPadding(14.dp(), 10.dp(), 16.dp(), 10.dp())
            addView(icon(ic, amber, 1.7f), LinearLayout.LayoutParams(24.dp(), 24.dp()).apply { rightMargin = 14.dp() })
            addView(label(title, 17f, text), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(label(value, 15f, valueColor).oneLine())
            addView(label("  ›", 20f, amber))
            setOnClickListener { onClick() }
            pressable()
        }
        settingsBody.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 6.dp() })
    }

    private fun buildSettingsBody() {
        settingsBody.removeAllViews()
        settingsBody.addView(label("Настройки", 24f, text, true))

        settingsSection("Приложения по умолчанию")
        settingsRow(Ico.MUSIC, "Музыка", appLabel(savedApp(TAB_MUSIC)) ?: "Не выбрано") { chooseApp(TAB_MUSIC) }
        settingsRow(Ico.PIN, "Навигация",
            appLabel(savedApp(TAB_MAP)) ?: appLabel(effectiveApp(TAB_MAP))?.let { "$it (авто)" } ?: "Не выбрано") {
            chooseApp(TAB_MAP)
        }

        settingsSection("Главный экран")
        settingsRow(Ico.GRID, "Все приложения", "${launchableApps().size}") { onTab(TAB_APPS) }
        val filled = (0 until 4).count { quickComponent(it) != null }
        settingsRow(Ico.PENCIL, "Быстрый доступ", "$filled из 4") { editQuickAccess() }
        val access = hasNotificationAccess()
        settingsRow(Ico.MUSIC, "Доступ к музыке", if (access) "Разрешён" else "Не разрешён",
            if (access) "#8FBF6A" else "#D9734A") { openNotificationAccess() }

        settingsSection("Система")
        settingsRow(Ico.HOME, "Лаунчер по умолчанию", "") {
            runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
        }
        settingsRow(Ico.GEAR, "Настройки Android", "") { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        settingsBody.addView(label("E90 OS COCKPIT  $version", 12f, dim).apply { setPadding(4.dp(), 12.dp(), 0, 0) })
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
                if (effectiveApp(TAB_MUSIC) != null) openSelectedApp(TAB_MUSIC) else toast("Выберите приложение для музыки")
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
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: source
        playIcon.icon = if (isPlaying) Ico.PAUSE else Ico.PLAY

        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (art !== lastArt) {
            lastArt = art
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
        seek.active = duration > 0
        seek.fraction = if (duration > 0) position.toFloat() / duration else 0f
        positionText.text = fmt(position)
        durationText.text = if (duration > 0) fmt(duration) else "--:--"
    }

    private fun showNoTrack() {
        controller = null
        isPlaying = false
        lastArt = null
        trackTitle.text = "Нет активного трека"
        trackArtist.text = appLabel(effectiveApp(TAB_MUSIC))?.let { "Нажмите ▶ · $it" } ?: "Нажмите ▶, чтобы продолжить"
        playIcon.icon = Ico.PLAY
        artImage.setImageBitmap(null)
        artImage.visibility = View.GONE
        artPlaceholder.visibility = View.VISIBLE
        seek.active = false
        seek.fraction = 0f
        positionText.text = "0:00"
        durationText.text = "0:00"
    }

    // ---------- auto page ----------

    private lateinit var speedGauge: GaugeView
    private lateinit var rpmGauge: GaugeView
    private var locationListener: LocationListener? = null

    /** Centred "name / [icon] value unit" block for the column between the gauges. */
    private fun centerMetric(name: String, value: String, unit: String, ic: Ico? = null, prefix: String? = null): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(label(name, 14f, muted).apply { gravity = Gravity.CENTER })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (ic != null) addView(icon(ic, amber, 1.7f), LinearLayout.LayoutParams(24.dp(), 24.dp()).apply { rightMargin = 8.dp() })
                if (prefix != null) addView(label("$prefix ", 22f, text))
                addView(label(value, 26f, text, true))
                addView(label(" $unit", if (unit.length > 3) 14f else 22f, text, unit.length <= 3))
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 6.dp() })
        }

    private lateinit var gpsChip: TextView

    private fun chip(textValue: String, hex: String): TextView = label(textValue, 11f, hex, true).apply {
        gravity = Gravity.CENTER
        letterSpacing = 0.08f
        setPadding(10.dp(), 4.dp(), 10.dp(), 4.dp())
        background = box("#120E0B", 10, "#3A2A1C")
    }

    /** Small round icon badge with a faint amber ring. */
    private fun badge(ic: Ico): FrameLayout = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = intArrayOf(rgb("#3A2410"), rgb("#1A120B"))
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 22.dp().toFloat()
            setStroke(1.dp(), rgb(edgeHi))
        }
        addView(icon(ic, amber, 1.7f), FrameLayout.LayoutParams(20.dp(), 20.dp(), Gravity.CENTER))
    }

    private fun tile(ic: Ico, name: String, value: String, unit: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(badge(ic), LinearLayout.LayoutParams(38.dp(), 38.dp()).apply { rightMargin = 12.dp() })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(name.uppercase(), 10f, muted, true).apply { letterSpacing = 0.12f })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                addView(label(value, 24f, text, true))
                addView(label(" $unit", 13f, muted).apply { setPadding(0, 0, 0, 3.dp()) })
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 2.dp() })
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    /** Glass panel between the gauges: outside temperature, range, consumption, data sources. */
    private fun buildCentrePanel(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = grad("#1C140E", "#0A0807", 18, "#3A2A1C")
        setPadding(16.dp(), 0, 16.dp(), 10.dp())

        addView(FadeLine(this@MainActivity, rgb(amber)), LinearLayout.LayoutParams(MATCH, 2.dp()).apply {
            leftMargin = 10.dp(); rightMargin = 10.dp()
        })
        addView(label("СНАРУЖИ", 10f, muted, true).apply { letterSpacing = 0.2f },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 10.dp() })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(Ico.CLOUD, amber, 1.6f), LinearLayout.LayoutParams(30.dp(), 30.dp()).apply { rightMargin = 10.dp() })
            addView(label("—", 38f, text).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
            addView(label(" °C", 18f, muted))
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 2.dp() })

        addView(FadeLine(this@MainActivity, rgb(edgeHi)), LinearLayout.LayoutParams(MATCH, 1.dp()).apply {
            topMargin = 8.dp(); bottomMargin = 10.dp()
        })
        addView(tile(Ico.FUEL, "Запас хода", "—", "км"), LinearLayout.LayoutParams(MATCH, WRAP))
        addView(tile(Ico.FUEL_DROP, "Расход Ø", "—", "л/100 км"), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 10.dp() })

        addView(spacer(), LinearLayout.LayoutParams(1, 0, 1f))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            gpsChip = chip("● GPS", dim)
            addView(gpsChip)
            addView(chip("● CAN", "#D9734A"), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = 8.dp() })
        })
    }

    /** Icon, name with a segmented bar under it, value on the right. */
    private fun barRow(ic: Ico, name: String, value: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(icon(ic, amber, 1.6f), LinearLayout.LayoutParams(30.dp(), 30.dp()).apply { rightMargin = 12.dp() })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(name, 13f, soft).oneLine())
            addView(SegmentBar(this@MainActivity, 6), LinearLayout.LayoutParams(MATCH, 6.dp()).apply { topMargin = 5.dp() })
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(label(value, 15f, text, true).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(64.dp(), WRAP).apply { leftMargin = 10.dp() })
    }

    private fun twoRowCard(a: View, b: View): LinearLayout = card().apply {
        setPadding(14.dp(), 8.dp(), 14.dp(), 8.dp())
        addView(a, LinearLayout.LayoutParams(MATCH, 0, 1f))
        addView(hLine(), LinearLayout.LayoutParams(MATCH, 1.dp()).apply { topMargin = 4.dp(); bottomMargin = 4.dp() })
        addView(b, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    private fun buildAutoPage(): View {
        speedGauge = GaugeView(this, 260f, 20f, 4, 1f, "km/h").apply { caption = "нет сигнала GPS" }
        rpmGauge = GaugeView(this, 7000f, 1000f, 5, 1000f, "1/min × 1000", redFrom = 6000f).apply {
            readout = "—"; caption = "CAN: нет связи"
        }

        val centre = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(centerMetric("Наружная t°", "—", "°C"))
            addView(FadeLine(this@MainActivity, rgb("#5A4430")), LinearLayout.LayoutParams(MATCH, 1.dp()).apply { topMargin = 12.dp(); bottomMargin = 12.dp() })
            addView(centerMetric("Запас хода", "—", "км", ic = Ico.FUEL))
            addView(FadeLine(this@MainActivity, rgb("#5A4430")), LinearLayout.LayoutParams(MATCH, 1.dp()).apply { topMargin = 12.dp(); bottomMargin = 12.dp() })
            addView(centerMetric("Расход", "—", "л/100 км", prefix = "Ø"))
        }

        val gauges = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(speedGauge, LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(centre, LinearLayout.LayoutParams(190.dp(), WRAP).apply { leftMargin = 6.dp(); rightMargin = 6.dp() })
            addView(rpmGauge, LinearLayout.LayoutParams(0, MATCH, 1f))
        }

        val route = card().apply {
            setPadding(16.dp(), 10.dp(), 16.dp(), 10.dp())
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(icon(Ico.NAV, amber, 1.7f), LinearLayout.LayoutParams(24.dp(), 24.dp()).apply { rightMargin = 10.dp() })
                addView(label("Карта · Маршрут", 15f, text))
                addView(spacer(), LinearLayout.LayoutParams(0, 1, 1f))
                addView(label((appLabel(effectiveApp(TAB_MAP)) ?: "Навигатор") + " ›", 12f, amber).oneLine())
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label("— км", 22f, text, true))
                    addView(label("— мин", 16f, soft), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 3.dp() })
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(hLine(), LinearLayout.LayoutParams(1.dp(), 40.dp()).apply { rightMargin = 14.dp() })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(label("Прибытие", 13f, muted))
                    addView(label("—:—", 20f, text, true), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 3.dp() })
                }, LinearLayout.LayoutParams(84.dp(), WRAP))
            }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = 4.dp() })
            setOnClickListener { openSelectedApp(TAB_MAP) }
            pressable()
        }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(twoRowCard(barRow(Ico.OIL, "Уровень масла", "— %"), barRow(Ico.BATTERY, "Напряжение АКБ", "— В")),
                LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(route, LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = 12.dp(); rightMargin = 12.dp() })
            addView(twoRowCard(barRow(Ico.COOLANT, "ОЖ", "— °C"), barRow(Ico.THERMO, "Впуск", "— °C")),
                LinearLayout.LayoutParams(0, MATCH, 1f))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 6.dp(), 12.dp(), 12.dp())
            addView(gauges, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(bottom, LinearLayout.LayoutParams(MATCH, 104.dp()).apply { topMargin = 8.dp() })
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
        if (requestCode == 7 && currentTab == TAB_AUTO) startGps()
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
            cell.addView(ImageView(this).apply { setImageDrawable(ri.loadIcon(pm)) }, LinearLayout.LayoutParams(56.dp(), 56.dp()))
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
        appsCount = label("", 14f, dim)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp(), 0, 8.dp(), 6.dp())
            addView(label("Приложения", 24f, text, true))
            addView(appsCount.apply { setPadding(10.dp(), 4.dp(), 0, 0) })
            addView(spacer(), LinearLayout.LayoutParams(0, 1, 1f))
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
            addView(sideNav(), LinearLayout.LayoutParams(150.dp(), MATCH).apply {
                leftMargin = 10.dp(); topMargin = 10.dp(); bottomMargin = 10.dp()
            })
            addView(content, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(rgb(bg))
            addView(topBar(), LinearLayout.LayoutParams(MATCH, 48.dp()))
            addView(View(this@MainActivity).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(rgb("#0B0908"), rgb("#2E241C"), rgb("#0B0908")))
            }, LinearLayout.LayoutParams(MATCH, 1.dp()))
            addView(center, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }

        setContentView(root)
        select(TAB_HOME)
    }

    override fun onResume() {
        super.onResume()
        refreshQuickAccess()
        if (currentTab == TAB_SETTINGS) buildSettingsBody()
        handler.removeCallbacks(tick)
        handler.post(tick)
        if (currentTab == TAB_AUTO) startGps()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        stopGps()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        select(TAB_HOME)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        // Home screen: ignore Back.
    }
}
