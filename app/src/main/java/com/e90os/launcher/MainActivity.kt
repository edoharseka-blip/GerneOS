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

    /** Value labels that live data updates, by key ("range", "cons", ...), with their unit suffix. */
    private val bound = mutableMapOf<String, MutableList<Pair<TextView, String>>>()
    private fun bind(key: String?, tv: TextView, suffix: String = ""): TextView {
        if (key != null) bound.getOrPut(key) { mutableListOf() }.add(tv to suffix)
        return tv
    }
    private fun show(key: String, value: String?) {
        bound[key]?.forEach { (tv, suffix) -> tv.text = (value ?: "—") + suffix }
    }

    private var btTicks = 0
    private var wasPlaying = false
    private var showingBt = false
    private var canSpeed: Int? = null
    private var gpsSpeed: Float? = null
    private var canAlive = false
    private val canChips = mutableListOf<TextView>()

    /** ELM327 OBD adapter for coolant, intake and voltage (the KSW MCU does not report them). */
    private val obd = Obd(this) { handler.post { if (currentTab == TAB_SETTINGS) buildSettingsBody() } }
    private fun obdTarget(): String? = getPreferences(MODE_PRIVATE).getString("obd_target", null)

    private fun fmt1(v: Float) = String.format(Locale("ru"), "%.1f", v)

    /** Pull the latest CAN frame from the KSW service and push it into every bound label. */
    private fun refreshCar() {
        val st = KswCar.read()
        canAlive = st != null
        show("outside", st?.outsideTemp?.let { fmt1(it) })
        show("range", st?.range?.takeIf { it > 0 }?.toString())
        show("fuel", st?.fuel?.takeIf { it > 0 }?.toString())
        show("avgspeed", st?.avgSpeed?.takeIf { it > 0f }?.let { fmt1(it) })
        show("cons", st?.consumption?.takeIf { it > 0f }?.let { fmt1(it) })
        canSpeed = st?.speed
        show("volt", obd.voltage?.let { fmt1(it) })
        show("coolant", obd.coolant?.toString())
        show("intake", obd.intake?.toString())
        rpmGauge.value = st?.rpm?.toFloat()
        rpmGauge.readout = st?.rpm?.let { String.format(Locale("ru"), "%.1f", it / 1000f) } ?: "—"
        rpmGauge.caption = if (st != null) "CAN" else "CAN: нет данных"
        canChips.forEach { it.setTextColor(rgb(if (canAlive) "#8FBF6A" else "#D9734A")) }
        updateSpeed()
        refreshRoute()
    }

    private fun updateSpeed() {
        val v = canSpeed?.toFloat() ?: gpsSpeed
        speedGauge.value = v
        speedGauge.readout = v?.let { Math.round(it).toString() } ?: "—"
        speedGauge.caption = when {
            canSpeed != null -> "CAN"
            gpsSpeed != null -> "GPS"
            locationListener != null -> "поиск GPS…"
            else -> "нет данных"
        }
    }

    private fun refreshRoute() {
        val r = RouteState.info?.takeIf { System.currentTimeMillis() - it.time < 10 * 60_000L }
        if (r == null) {
            bound["route_title"]?.forEach { it.first.text = "Нет активного маршрута" }
            bound["route_dist"]?.forEach { it.first.text = "—" }
            bound["route_dur"]?.forEach { it.first.text = "" }
            bound["route_eta"]?.forEach { it.first.text = "—:—" }
            return
        }
        val (dist, dur, eta) = RouteState.parse(r)
        val caption = when {
            dist == null && eta == null -> r.title.ifBlank { r.text }
            r.text.isNotBlank() -> r.text
            else -> "До манёвра"
        }
        bound["route_title"]?.forEach { it.first.text = caption }
        bound["route_dist"]?.forEach { it.first.text = dist ?: "—" }
        bound["route_dur"]?.forEach { it.first.text = dur ?: "" }
        bound["route_eta"]?.forEach { it.first.text = eta ?: "—:—" }
    }

    private val tick = object : Runnable {
        override fun run() {
            clock.text = timeFmt.format(Date())
            refreshStatusIcons()
            refreshMedia()
            if (currentTab == TAB_HOME || currentTab == TAB_AUTO) refreshCar()
            if (btMode && currentTab == TAB_HOME) {
                if (++btTicks % 5 == 0) gocBt.query()
                refreshBt()
            }
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
        addView(art, LinearLayout.LayoutParams(160.dp(), 160.dp()).apply { topMargin = 40.dp() })

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
    private fun valueCard(ic: Ico, title: String, value: String, unit: String, key: String? = null): LinearLayout = card().apply {
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
                addView(bind(key, label(value, 30f, text, true)))
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
            addView(bind("outside", label("— °C", 22f, text, true), " °C"))
            setPadding(2.dp(), 0, 8.dp(), 0)
            setOnClickListener { select(TAB_AUTO) }
        }, LinearLayout.LayoutParams(MATCH, 34.dp()))

        addView(pair(valueCard(Ico.BATTERY, "Напряжение", "—", "В", "volt"), valueCard(Ico.COOLANT, "Температура ОЖ", "—", "°C", "coolant")),
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

        addView(pair(valueCard(Ico.FUEL, "Запас хода", "—", "км", "range"), valueCard(Ico.FUEL_DROP, "Расход", "—", "л/100 км", "cons")),
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
        val strip = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        repeat(4) { index ->
            val slot = FrameLayout(this@MainActivity)
            val cell = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { openQuickApp(index) }
                setOnLongClickListener { chooseQuickApp(index); true }
                pressable()
            }
            shortcutCells.add(cell)
            slot.addView(cell, FrameLayout.LayoutParams(64.dp(), 64.dp(), Gravity.CENTER))
            strip.addView(slot, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        addView(strip, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = 4.dp() })
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
            addView(FrameLayout(this@MainActivity).apply {
                mediaPanel = buildPlayer()
                btPanel = buildBtPanel()
                addView(mediaPanel, FrameLayout.LayoutParams(MATCH, MATCH))
                addView(btPanel, FrameLayout.LayoutParams(MATCH, MATCH))
                addView(sourceToggle(), FrameLayout.LayoutParams(WRAP, 32.dp(), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                    topMargin = 12.dp()
                })
            }, LinearLayout.LayoutParams(0, MATCH, 0.78f).apply { rightMargin = 16.dp() })
            addView(right, LinearLayout.LayoutParams(0, MATCH, 1.22f))
        }
    }

    // ---------- media / Bluetooth source switch ----------

    private lateinit var mediaPanel: View
    private lateinit var btPanel: View
    private lateinit var segMedia: TextView
    private lateinit var segBt: TextView
    private var btMode = false
    private lateinit var gocBt: GocBt
    private lateinit var btMusic: BtMusic

    private lateinit var btStatus: TextView
    private lateinit var btDevice: TextView
    private lateinit var btBadge: FrameLayout
    private lateinit var btTitle: TextView
    private lateinit var btArtist: TextView
    private lateinit var btSeek: SeekLine
    private lateinit var btPos: TextView
    private lateinit var btDur: TextView
    private lateinit var btPlayIcon: IconView
    private lateinit var btConnectLabel: TextView

    private fun segment(t: String, onClick: () -> Unit) = label(t, 13f, text, true).apply {
        gravity = Gravity.CENTER
        setPadding(14.dp(), 0, 14.dp(), 0)
        setOnClickListener { onClick() }
    }

    /** Pill switch "Медиа | Bluetooth" in the corner of the music window. */
    private fun sourceToggle(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = grad("#1E1610", "#130E0A", 16, edgeHi)
        setPadding(3.dp(), 3.dp(), 3.dp(), 3.dp())
        segMedia = segment("Медиа") { setBtMode(false) }
        segBt = segment("ᛒ  Bluetooth") { setBtMode(true) }
        addView(segMedia, LinearLayout.LayoutParams(WRAP, MATCH))
        addView(segBt, LinearLayout.LayoutParams(WRAP, MATCH))
    }

    private fun setBtMode(on: Boolean) {
        btMode = on
        getPreferences(MODE_PRIVATE).edit().putBoolean("bt_mode", on).apply()
        mediaPanel.visibility = if (on) View.GONE else View.VISIBLE
        btPanel.visibility = if (on) View.VISIBLE else View.GONE
        segMedia.background = if (!on) grad("#6A3E12", "#3A220C", 13, amber) else null
        segBt.background = if (on) grad("#6A3E12", "#3A220C", 13, amber) else null
        segMedia.setTextColor(rgb(if (!on) "#FFF4E7" else muted))
        segBt.setTextColor(rgb(if (on) "#FFF4E7" else muted))
        if (on) gocBt.query()
        refreshBt()
    }

    private fun btAction(textValue: String, ic: Ico, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = grad("#1E1813", "#15110E", 10, edge)
        addView(icon(ic, amber, 1.7f), LinearLayout.LayoutParams(18.dp(), 18.dp()).apply { rightMargin = 8.dp() })
        addView(label(textValue, 13f, text, true).oneLine().also { if (ic == Ico.BT) btConnectLabel = it })
        setOnClickListener { onClick() }
        pressable()
    }

    private fun buildBtPanel(): View = card().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(18.dp(), 14.dp(), 18.dp(), 14.dp())
        visibility = View.GONE

        // Phone status block.
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            btBadge = FrameLayout(this@MainActivity).apply {
                addView(icon(Ico.BT, amber, 1.6f), FrameLayout.LayoutParams(30.dp(), 30.dp(), Gravity.CENTER))
            }
            addView(btBadge, LinearLayout.LayoutParams(60.dp(), 60.dp()).apply { rightMargin = 14.dp() })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                btStatus = label("", 12f, muted, true).apply { letterSpacing = 0.1f }
                addView(btStatus)
                btDevice = label("", 20f, text, true).oneLine()
                addView(btDevice, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 4.dp() })
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 46.dp() })

        // Phone music.
        addView(FadeLine(this@MainActivity, rgb(edgeHi)), LinearLayout.LayoutParams(MATCH, 1.dp()).apply {
            topMargin = 14.dp(); bottomMargin = 12.dp()
        })
        addView(label("МУЗЫКА С ТЕЛЕФОНА", 10f, dim, true).apply { letterSpacing = 0.15f })
        btTitle = label("", 20f, text, true).apply { gravity = Gravity.CENTER }.oneLine()
        addView(btTitle, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 8.dp() })
        btArtist = label("", 15f, muted).apply { gravity = Gravity.CENTER }.oneLine()
        addView(btArtist, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 4.dp() })

        addView(spacer(), LinearLayout.LayoutParams(1, 0, 1f))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            btPos = label("0:00", 14f, muted)
            btDur = label("0:00", 14f, muted).apply { gravity = Gravity.END }
            btSeek = SeekLine(this@MainActivity)
            addView(btPos, LinearLayout.LayoutParams(44.dp(), WRAP))
            addView(btSeek, LinearLayout.LayoutParams(0, 18.dp(), 1f).apply { leftMargin = 6.dp(); rightMargin = 6.dp() })
            addView(btDur, LinearLayout.LayoutParams(44.dp(), WRAP))
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            val (prev, _) = transportButton(Ico.PREV, false) { btMedia(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
            val (play, pi) = transportButton(Ico.PLAY, true) { btMedia(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
            val (next, _) = transportButton(Ico.NEXT, false) { btMedia(KeyEvent.KEYCODE_MEDIA_NEXT) }
            btPlayIcon = pi
            addView(prev, LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(play, LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = 12.dp(); rightMargin = 12.dp() })
            addView(next, LinearLayout.LayoutParams(0, MATCH, 1f))
        }, LinearLayout.LayoutParams(MATCH, 48.dp()).apply { topMargin = 8.dp() })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btAction("Подключить", Ico.BT) { openBluetooth() }, LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(btAction("Настройки BT", Ico.GEAR) {
                runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }.onFailure { openBluetooth() }
            },
                LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = 10.dp() })
        }, LinearLayout.LayoutParams(MATCH, 40.dp()).apply { topMargin = 10.dp() })
    }

    /** Media session of the head unit's Bluetooth app, when it publishes one. */
    private fun btSession(): MediaController? =
        mediaSessions()?.firstOrNull { it.packageName == GocBt.PKG }

    private fun btMedia(code: Int) {
        val sub = when (code) {
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> KswCar.BT_PREV
            KeyEvent.KEYCODE_MEDIA_NEXT -> KswCar.BT_NEXT
            else -> if (btMusic.playing == true) KswCar.BT_PAUSE else KswCar.BT_PLAY
        }
        if (gocBt.connected != false && KswCar.bt(sub)) {
            handler.postDelayed({ refreshBt(); refreshMedia() }, 500)
            return
        }
        val c = btSession()
        if (c != null) {
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> c.transportControls.skipToPrevious()
                KeyEvent.KEYCODE_MEDIA_NEXT -> c.transportControls.skipToNext()
                else -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING || btMusic.playing == true)
                    c.transportControls.pause() else c.transportControls.play()
            }
        } else if (gocBt.connected == false) {
            openBluetooth()
            return
        } else {
            sendMediaKey(code)
        }
        handler.postDelayed({ refreshBt() }, 400)
    }

    private fun refreshBt() {
        if (!::btStatus.isInitialized) return
        val conn = gocBt.connected
        val (status, color) = when (conn) {
            true -> "ТЕЛЕФОН ПОДКЛЮЧЁН" to "#8FBF6A"
            false -> "НЕТ ПОДКЛЮЧЕНИЯ" to "#D9734A"
            null -> "BLUETOOTH" to muted
        }
        btStatus.text = status
        btStatus.setTextColor(rgb(color))
        btDevice.text = when (conn) {
            true -> "Телефон"
            false -> "Подключите телефон"
            null -> "Bluetooth магнитолы"
        }
        btBadge.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = intArrayOf(rgb(if (conn == true) "#4A3010" else "#2A1D12"), rgb("#140F0B"))
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 32.dp().toFloat()
            setStroke(2.dp(), rgb(if (conn == true) amber else edgeHi))
        }
        val c = btSession()
        val md = c?.metadata
        val st = c?.playbackState
        val playing = st?.state == PlaybackState.STATE_PLAYING
        btTitle.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: btMusic.title?.takeIf { conn != false }
            ?: if (conn == true) "Музыка телефона" else "Нет трека"
        btArtist.text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            ?: (if (btMusic.title != null && conn != false) "Bluetooth · телефон" else null)
            ?: if (conn == true) "Нажмите ▶ или включите музыку на телефоне" else "Нажмите «Подключить»"
        btPlayIcon.icon = if (playing || btMusic.playing == true) Ico.PAUSE else Ico.PLAY
        val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        var pos = st?.position ?: 0L
        if (playing && st != null) pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        val bDur = if (dur > 0) dur else btMusic.durSec * 1000L
        val bPos = if (dur > 0) pos else btMusic.posSec * 1000L
        btSeek.active = bDur > 0
        btSeek.fraction = if (bDur > 0) bPos.toFloat() / bDur else 0f
        btPos.text = fmt(bPos.coerceAtLeast(0))
        btDur.text = if (bDur > 0) fmt(bDur) else "0:00"
        btConnectLabel.text = if (conn == true) "Телефоны" else "Подключить"
    }

    private fun openBluetooth() {
        val tries = listOf(
            Intent("com.wits.bt.action.OPEN").setPackage("com.wits.ksw.bt"),
            packageManager.getLaunchIntentForPackage("com.wits.ksw.bt"),
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
        )
        for (i in tries) {
            if (i == null) continue
            if (runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
        toast("Bluetooth недоступен")
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
                cell.background = GradientDrawable().apply {
                    gradientType = GradientDrawable.RADIAL_GRADIENT
                    gradientRadius = 46.dp().toFloat()
                    colors = intArrayOf(rgb("#3A2716"), rgb("#16110D"))
                    cornerRadius = 18.dp().toFloat()
                    setStroke(1.dp(), rgb("#5A3E26"))
                }
                cell.elevation = 6.dp().toFloat()
                cell.addView(ImageView(this).apply { setImageDrawable(appIcon) }, LinearLayout.LayoutParams(44.dp(), 44.dp()))
            } else {
                cell.background = GradientDrawable().apply {
                    setColor(rgb("#120E0B"))
                    cornerRadius = 18.dp().toFloat()
                    setStroke(1.dp(), rgb("#6A4A2A"), 5.dp().toFloat(), 4.dp().toFloat())
                }
                cell.elevation = 0f
                cell.addView(icon(Ico.ADD, amber, 1.6f), LinearLayout.LayoutParams(26.dp(), 26.dp()))
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

    private fun obdTargetLabel(t: String?): String = when {
        t == null -> "Не выбран"
        t.startsWith("wifi:") -> "Wi-Fi " + t.removePrefix("wifi:")
        else -> {
            val mac = t.substringAfter(':')
            val name = runCatching { BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(mac)?.name }.getOrNull() ?: mac
            if (t.startsWith("ble:")) "$name (BLE)" else name
        }
    }

    /** Pick the OBD adapter: a paired Bluetooth device, a Wi-Fi ELM327, or off. */
    private fun chooseObd() {
        val bonded = runCatching { BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList() }
            .getOrNull().orEmpty().sortedBy { it.name ?: it.address }
        val names = mutableListOf("Выключен")
        val targets = mutableListOf<String?>(null)
        bonded.forEach { names += "Bluetooth: " + (it.name ?: it.address); targets += "bt:" + it.address }
        names += "Найти Bluetooth LE адаптер (iOS + Android)…"; targets += "scan"
        names += "Wi-Fi ELM327 (192.168.0.10:35000)"; targets += Obd.WIFI_DEFAULT
        names += "Сопрячь новый адаптер…"; targets += "pair"
        AlertDialog.Builder(this)
            .setTitle(if (bonded.isEmpty()) "OBD-адаптер (сопряжённых нет)" else "OBD-адаптер")
            .setItems(names.toTypedArray()) { _, i ->
                val t = targets[i]
                if (t == "scan") { scanBleObd(); return@setItems }
                if (t == "pair") {
                    runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                    return@setItems
                }
                getPreferences(MODE_PRIVATE).edit().putString("obd_target", t).apply()
                obd.start(t)
                buildSettingsBody()
            }
            .show()
    }

    /** Scan 6 s for BLE devices and let the user pick the OBD adapter among them. */
    private fun scanBleObd() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) { Toast.makeText(this, "Bluetooth выключен или недоступен", Toast.LENGTH_LONG).show(); return }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 7)
            Toast.makeText(this, "Нужно разрешение на геолокацию для поиска BLE, затем повторите", Toast.LENGTH_LONG).show()
            return
        }
        val found = linkedMapOf<String, String>()
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(type: Int, r: android.bluetooth.le.ScanResult) {
                val name = r.device.name ?: r.scanRecord?.deviceName ?: return
                found[r.device.address] = name
            }
        }
        val wait = AlertDialog.Builder(this).setTitle("Поиск BLE-адаптера…")
            .setMessage("Вставьте сканер в OBD-разъём и включите зажигание.").setCancelable(false).show()
        runCatching { scanner.startScan(cb) }.onFailure { wait.dismiss(); Toast.makeText(this, "Поиск BLE не запустился", Toast.LENGTH_LONG).show(); return }
        handler.postDelayed({
            runCatching { scanner.stopScan(cb) }
            wait.dismiss()
            if (found.isEmpty()) { Toast.makeText(this, "BLE-адаптер не найден", Toast.LENGTH_LONG).show(); return@postDelayed }
            val list = found.entries.sortedByDescending { e ->
                listOf("OBD", "ELM", "LINK", "SCAN", "VEEPEAK", "IOS").any { e.value.uppercase().contains(it) }
            }
            AlertDialog.Builder(this).setTitle("BLE-адаптер")
                .setItems(list.map { "${it.value}  ${it.key}" }.toTypedArray()) { _, i ->
                    val t = "ble:" + list[i].key
                    getPreferences(MODE_PRIVATE).edit().putString("obd_target", t).apply()
                    obd.start(t)
                    buildSettingsBody()
                }.show()
        }, 6_000)
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

        settingsSection("Автомобиль")
        val (obdText, obdColor) = when (obd.state) {
            Obd.State.OFF -> "Выключен" to muted
            Obd.State.CONNECTING -> "Подключение…" to muted
            Obd.State.NO_ADAPTER -> "Адаптер не отвечает" to "#D9734A"
            Obd.State.NO_ECU -> "Нет ответа от машины" to "#D9734A"
            Obd.State.OK -> "Работает" to "#8FBF6A"
        }
        settingsRow(Ico.CAR, "OBD-адаптер ELM327", obdTargetLabel(obdTarget())) { chooseObd() }
        settingsRow(Ico.SIGNAL, "Статус OBD", obdText, obdColor) { chooseObd() }

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
        if (showingBt) { btMedia(code); return }
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
        if (showingBt) { btMedia(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); return }
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
        showingBt = false
        // A phone/app track started playing while the Bluetooth view is open: show it.
        val nowPlaying = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        if (nowPlaying && btMode && !wasPlaying) setBtMode(false)
        wasPlaying = nowPlaying
        if (c == null) {
            if (!showBtTrack(sessions)) showNoTrack()
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

    /** Phone music over the head unit's Bluetooth: title from BtMusic, controls via the KSW session. */
    private fun showBtTrack(sessions: List<MediaController>): Boolean {
        val t = btMusic.title ?: return false
        if (gocBt.connected == false || btMusic.playing == null && System.currentTimeMillis() - btMusic.updated > 15 * 60_000L) return false
        controller = sessions.firstOrNull { it.packageName == GocBt.PKG }
        showingBt = true
        isPlaying = btMusic.playing != false
        trackTitle.text = t
        trackArtist.text = "Bluetooth · телефон"
        playIcon.icon = if (isPlaying) Ico.PAUSE else Ico.PLAY
        if (lastArt != null) {
            lastArt = null
            artImage.setImageBitmap(null)
            artImage.visibility = View.GONE
            artPlaceholder.visibility = View.VISIBLE
        }
        seek.active = btMusic.durSec > 0
        seek.fraction = if (btMusic.durSec > 0) btMusic.posSec.toFloat() / btMusic.durSec else 0f
        positionText.text = fmt(btMusic.posSec * 1000L)
        durationText.text = if (btMusic.durSec > 0) fmt(btMusic.durSec * 1000L) else ""
        return true
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
    private fun centerMetric(name: String, value: String, unit: String, ic: Ico? = null, prefix: String? = null, key: String? = null): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(label(name, 14f, muted).apply { gravity = Gravity.CENTER })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (ic != null) addView(icon(ic, amber, 1.7f), LinearLayout.LayoutParams(24.dp(), 24.dp()).apply { rightMargin = 8.dp() })
                if (prefix != null) addView(label("$prefix ", 22f, text))
                addView(bind(key, label(value, 30f, text, true)))
                addView(label(" $unit", if (unit.length > 3) 15f else 26f, text, unit.length <= 3))
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
    private fun barRow(ic: Ico, name: String, value: String, key: String? = null, suffix: String = ""): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(icon(ic, amber, 1.6f), LinearLayout.LayoutParams(30.dp(), 30.dp()).apply { rightMargin = 12.dp() })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(name, 13f, soft).oneLine())
            addView(SegmentBar(this@MainActivity, 6), LinearLayout.LayoutParams(MATCH, 6.dp()).apply { topMargin = 5.dp() })
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(bind(key, label(value, 15f, text, true).apply { gravity = Gravity.END }, suffix),
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
            readout = "—"; caption = "CAN: нет данных"
        }

        val centre = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(centerMetric("Наружная t°", "—", "°C", key = "outside"))
            addView(FadeLine(this@MainActivity, rgb("#5A4430")), LinearLayout.LayoutParams(MATCH, 1.dp()).apply { topMargin = 12.dp(); bottomMargin = 12.dp() })
            addView(centerMetric("Запас хода", "—", "км", ic = Ico.FUEL, key = "range"))
            addView(FadeLine(this@MainActivity, rgb("#5A4430")), LinearLayout.LayoutParams(MATCH, 1.dp()).apply { topMargin = 12.dp(); bottomMargin = 12.dp() })
            addView(centerMetric("Расход", "—", "л/100 км", prefix = "Ø", key = "cons"))
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
                    addView(bind("route_title", label("Нет активного маршрута", 12f, muted).oneLine()))
                    addView(bind("route_dist", label("—", 22f, text, true)), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 2.dp() })
                    addView(bind("route_dur", label("", 15f, soft)), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 2.dp() })
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(hLine(), LinearLayout.LayoutParams(1.dp(), 40.dp()).apply { rightMargin = 14.dp() })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(label("Прибытие", 13f, muted))
                    addView(bind("route_eta", label("—:—", 20f, text, true)), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 3.dp() })
                }, LinearLayout.LayoutParams(84.dp(), WRAP))
            }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = 4.dp() })
            setOnClickListener { openSelectedApp(TAB_MAP) }
            pressable()
        }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(twoRowCard(barRow(Ico.FUEL, "Топливо в баке", "— л", "fuel", " л"), barRow(Ico.BATTERY, "Напряжение АКБ", "— В", "volt", " В")),
                LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(route, LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = 12.dp(); rightMargin = 12.dp() })
            addView(twoRowCard(barRow(Ico.COOLANT, "ОЖ", "— °C", "coolant", " °C"), barRow(Ico.THERMO, "Впуск", "— °C", "intake", " °C")),
                LinearLayout.LayoutParams(0, MATCH, 1f))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp(), 2.dp(), 12.dp(), 10.dp())
            addView(gauges, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(bottom, LinearLayout.LayoutParams(MATCH, 100.dp()).apply { topMargin = 4.dp() })
            visibility = View.GONE
        }
    }

    private fun showSpeed(kmh: Float?) {
        gpsSpeed = kmh
        updateSpeed()
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

        gocBt = GocBt(this) { refreshBt() }
        btMusic = BtMusic(this) { refreshMedia(); refreshBt() }
        btMusic.start()
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
        gocBt.start()
        setBtMode(getPreferences(MODE_PRIVATE).getBoolean("bt_mode", false))
        obd.start(obdTarget())
        KswCar.startDumpLog()
    }

    override fun onResume() {
        super.onResume()
        refreshQuickAccess()
        if (currentTab == TAB_SETTINGS) buildSettingsBody()
        handler.removeCallbacks(tick)
        handler.post(tick)
        if (currentTab == TAB_AUTO) startGps()
    }

    override fun onDestroy() {
        gocBt.stop()
        btMusic.stop()
        obd.stop()
        KswCar.stopDumpLog()
        super.onDestroy()
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
