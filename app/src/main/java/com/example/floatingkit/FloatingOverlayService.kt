package com.example.floatingkit

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Production overlay engine: plain [Service] + SYSTEM_ALERT_WINDOW.
 *
 * Ported from LiveFootball_II's AccessibilityService version. All drag /
 * scale / theme / single-multi-stack-ticker logic is unchanged — only the
 * window type and permission model changed:
 *  - TYPE_APPLICATION_OVERLAY (needs ACTION_MANAGE_OVERLAY_PERMISSION)
 *  - Foreground service with low-priority notification (no kill in bg)
 *  - No accessibility events: per-app blacklist now needs UsageStats,
 *    so isBlacklistedForeground() is a stub returning false.
 */
abstract class FloatingOverlayService : Service() {

    enum class Mode { SINGLE, MULTI, STACK, TICKER }

    protected var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var refreshJob: Job? = null
    private var cycleJob: Job? = null
    private var settingsReceiver: BroadcastReceiver? = null
    private var screenReceiver: BroadcastReceiver? = null

    private val matchWindows = LinkedHashMap<Long, ChipWindow>()
    private var stackWindow: ChipWindow? = null
    private var stackItems = listOf<FloatingItem>()
    private var tickerWindow: ChipWindow? = null
    private val dismissed = mutableSetOf<Long>()
    private var cycleIndex = 0
    private val savedPositions = mutableMapOf<Long, Pair<Int, Int>>()
    private var singleWindowPos: Pair<Int, Int>? = null
    private var freedPosition: Pair<Int, Int>? = null

    /** Override: current items to display. Called every [refreshMs]. */
    protected abstract suspend fun getItems(): List<FloatingItem>

    /** Override: what happens on tap (default: reopen app). */
    protected open fun onItemTap(item: FloatingItem?) {
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }

    protected open fun refreshMs(): Long = 30_000L
    protected open fun cycleMs(): Long = 5000L

    // ── prefs ──
    protected fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    protected fun isOverlayEnabled(): Boolean = prefs().getBoolean(KEY_ENABLED, true)
    protected fun mode(): Mode = try { Mode.valueOf(prefs().getString(KEY_MODE, Mode.SINGLE.name)!!) } catch (_: Exception) { Mode.SINGLE }
    protected fun theme(): FloatingTheme = FloatingThemes.get(prefs().getString(KEY_THEME, FloatingThemes.CLASSIC))
    protected fun scale(): Float = prefs().getFloat(KEY_SCALE, 1.0f).coerceIn(0.5f, 2.0f)
    protected fun screenMode(): String = prefs().getString(KEY_SCREEN, SCREEN_BOTH) ?: SCREEN_BOTH

    protected inner class ChipWindow(val view: View, val params: WindowManager.LayoutParams) {
        var itemId: Long = -1L
        var scaledView: View? = null
        var baseWidth = 0
        var baseHeight = 0
        var expanded = false
        var lastItem: FloatingItem? = null
        var minimized = false
        var dragging = false
        var didDrag = false
        var touchStartRawX = 0f
        var touchStartRawY = 0f
        var startX = 0
        var startY = 0
    }

    // ── service lifecycle ──
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        tornDown = false
        isRunning = true
        instance = this
        startForegroundNotification()
        registerSettingsReceiver()
        registerScreenReceiver()
        refreshJob = scope.launch { refreshLoop() }
        cycleJob = scope.launch { cycleLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Permission revoked while running -> remove windows, wait for re-grant.
        if (!canDraw(this)) removeAllWindows()
        else scope.launch { runCatching { reconcile() } }
        return START_STICKY
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private var tornDown = false
    private fun teardown() {
        if (tornDown) return
        tornDown = true
        isRunning = false
        if (instance === this) instance = null
        refreshJob?.cancel(); cycleJob?.cancel()
        scope.cancel()
        settingsReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        settingsReceiver = null; screenReceiver = null
        removeAllWindows()
        windowManager = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, getString(R.string.overlay_channel_name), NotificationManager.IMPORTANCE_MIN)
                        .apply { description = getString(R.string.overlay_channel_desc) }
                )
            }
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.overlay_notif_title))
            .setContentText(getString(R.string.overlay_notif_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                startForeground(
                    NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }.onFailure {
                runCatching { startForeground(NOTIF_ID, notif) }
            }
        } else {
            runCatching { startForeground(NOTIF_ID, notif) }
        }
    }

    private fun registerSettingsReceiver() {
        settingsReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { scope.launch { runCatching { reconcile() } } }
        }
        val f = IntentFilter(ACTION_SETTINGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(settingsReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(settingsReceiver, f)
    }

    private fun registerScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { scope.launch { runCatching { reconcile() } } }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, f)
    }

    fun notifySettingsChanged() { scope.launch { runCatching { reconcile() } } }

    // ── loops ──
    private suspend fun refreshLoop() {
        while (scope.isActive) {
            runCatching { reconcile() }
            delay(refreshMs())
        }
    }

    private suspend fun cycleLoop() {
        while (scope.isActive) {
            delay(cycleMs())
            if (mode() != Mode.SINGLE) continue
            runCatching { advanceCycle() }
        }
    }

    private suspend fun advanceCycle() {
        val items = getItems().filter { it.id !in dismissed }
        if (items.isEmpty()) return
        if (cycleIndex >= items.size) cycleIndex = 0
        val desired = items[cycleIndex].id
        cycleIndex = (cycleIndex + 1) % items.size
        val chip = matchWindows.entries.firstOrNull()?.value ?: return
        val item = items.firstOrNull { it.id == desired } ?: return
        if (chip.itemId != desired) {
            matchWindows.entries.filter { it.value === chip }.map { it.key }.forEach { matchWindows.remove(it) }
            chip.itemId = desired
            matchWindows[desired] = chip
            bindCard(chip, item, animate = true)
        } else bindCard(chip, item, animate = false)
    }

    private suspend fun reconcile() {
        if (!canDraw(this)) { removeAllWindows(); return }
        if (!isOverlayEnabled() || !screenAllows()) { removeAllWindows(); return }
        val all = getItems()
        dismissed.removeAll { id -> all.none { it.id == id } }
        val visible = all.filter { it.id !in dismissed }
        when (mode()) {
            Mode.TICKER -> {
                removeCards(); removeStack()
                reconcileTicker(visible)
            }
            Mode.STACK -> {
                removeCards(); removeTicker()
                reconcileStack(visible)
            }
            Mode.MULTI -> {
                removeStack(); removeTicker()
                val want = visible.map { it.id }
                matchWindows.entries.removeIf { (id, chip) ->
                    if (id !in want) {
                        if (id > 0) { freedPosition = chip.params.x to chip.params.y; savedPositions.remove(id) }
                        removeWindow(chip); true
                    } else false
                }
                val byId = visible.associateBy { it.id }
                for (id in want) {
                    val chip = matchWindows[id]
                    val item = byId[id] ?: continue
                    if (chip == null) {
                        val c = addCardWindow(id)
                        matchWindows[id] = c
                        bindCard(c, item)
                    } else bindCard(chip, item)
                }
            }
            Mode.SINGLE -> {
                removeStack(); removeTicker()
                cycleSingle(visible)
            }
        }
    }

    private fun cycleSingle(visible: List<FloatingItem>) {
        if (matchWindows.size > 1) {
            val keep = matchWindows.entries.firstOrNull()?.value
            if (keep != null) {
                matchWindows.entries.filter { it.value !== keep }.forEach { removeWindow(it.value) }
                matchWindows.clear()
                matchWindows[keep.itemId] = keep
            } else removeCards()
        }
        if (cycleIndex >= visible.size) cycleIndex = 0
        val desired = visible.getOrNull(cycleIndex)?.id
        val chip = matchWindows.entries.firstOrNull()?.value
        val item = desired?.let { d -> visible.firstOrNull { it.id == d } }
        when {
            chip == null && item != null -> {
                val c = addCardWindow(desired!!)
                matchWindows[desired] = c
                bindCard(c, item)
            }
            chip != null && item == null -> { removeWindow(chip); matchWindows.clear() }
            chip != null && item != null -> {
                val current = visible.firstOrNull { it.id == chip.itemId }
                if (current != null) { bindCard(chip, current); return }
                matchWindows.entries.filter { it.value === chip }.map { it.key }.forEach { matchWindows.remove(it) }
                chip.itemId = desired!!
                matchWindows[desired] = chip
                bindCard(chip, item, animate = true)
            }
        }
    }

    // ── window creation: SYSTEM_ALERT_WINDOW variant ──
    private fun wm(): WindowManager =
        windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { windowManager = it }

    private fun baseParams(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 240 }

    private fun themedCtx(): Context = androidx.appcompat.view.ContextThemeWrapper(this, R.style.Theme_FloatingKit)

    private fun addCardWindow(id: Long): ChipWindow {
        val card = View.inflate(themedCtx(), R.layout.floating_card, null)
        val view = FrameLayout(themedCtx()).apply { clipChildren = false; clipToPadding = false }
        view.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        val params = baseParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT
        )
        applySavedPosition(params, id)
        val chip = ChipWindow(view, params).apply { itemId = id; scaledView = card }
        view.setOnTouchListener { _, e -> onWindowTouch(chip, e) }
        runCatching { wm().addView(view, params) }
        applyScale(chip)
        view.alpha = 0f
        view.post { view.animate().alpha(1f).setDuration(200).start() }
        return chip
    }

    private fun addStackWindow(): ChipWindow {
        val card = View.inflate(themedCtx(), R.layout.floating_stack, null)
        val view = FrameLayout(themedCtx()).apply { clipChildren = false; clipToPadding = false }
        view.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        val params = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        applySavedPosition(params, STACK_KEY)
        val chip = ChipWindow(view, params).apply { itemId = STACK_KEY; scaledView = card }
        view.setOnTouchListener { _, e -> onWindowTouch(chip, e) }
        runCatching { wm().addView(view, params) }
        applyScale(chip)
        view.alpha = 0f
        view.post { view.animate().alpha(1f).setDuration(200).start() }
        return chip
    }

    private fun addTickerWindow(): ChipWindow {
        val card = View.inflate(themedCtx(), R.layout.floating_ticker, null)
        val view = FrameLayout(themedCtx()).apply { clipChildren = false; clipToPadding = false }
        view.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        val params = baseParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        applySavedPosition(params, TICKER_KEY)
        val chip = ChipWindow(view, params).apply { itemId = TICKER_KEY; scaledView = card }
        view.findViewById<DraggableContainer>(R.id.tickerDraggable)?.let { d ->
            d.onDragStart = { chip.startX = chip.params.x; chip.startY = chip.params.y }
            d.onDrag = { dx, dy ->
                chip.params.x = chip.startX + dx; chip.params.y = chip.startY + dy
                runCatching { windowManager?.updateViewLayout(view, chip.params) }
            }
            d.onDragEnd = { saved -> if (saved) savedPositions[TICKER_KEY] = chip.params.x to chip.params.y }
        }
        runCatching { wm().addView(view, params) }
        applyScale(chip)
        view.alpha = 0f
        view.post { view.animate().alpha(1f).setDuration(200).start() }
        return chip
    }

    private fun applySavedPosition(lp: WindowManager.LayoutParams, id: Long) {
        if (mode() == Mode.SINGLE) {
            singleWindowPos?.let { lp.x = it.first; lp.y = it.second; return }
            return
        }
        savedPositions.remove(id)?.let { lp.x = it.first; lp.y = it.second; freedPosition = null; return }
        freedPosition?.let { lp.x = it.first; lp.y = it.second }
        freedPosition = null
    }

    // ── binding ──
    private fun bindCard(chip: ChipWindow, item: FloatingItem, animate: Boolean = false) {
        val view = chip.view
        if (animate) {
            view.animate().cancel()
            view.animate().alpha(0f).setDuration(140).withEndAction {
                applyItem(view, item, chip)
                view.animate().alpha(1f).setDuration(200).start()
            }.start()
        } else {
            view.animate().cancel(); view.alpha = 1f
            applyItem(view, item, chip)
        }
    }

    private fun applyItem(view: View, item: FloatingItem, chip: ChipWindow?) {
        val t = theme()
        chip?.lastItem = item
        view.findViewById<TextView>(R.id.overlayLeft)?.text = item.left
        view.findViewById<TextView>(R.id.overlayRight)?.text = item.right
        view.findViewById<TextView>(R.id.overlayTitle)?.apply { text = item.title; setTextColor(t.title) }
        view.findViewById<TextView>(R.id.overlayStatus)?.apply { text = item.status; setTextColor(t.status) }
        view.findViewById<TextView>(R.id.overlayClose)?.setTextColor(t.close)
        val badge = view.findViewById<TextView>(R.id.overlayBadge)
        if (badge != null) {
            if (item.badge != null) {
                badge.text = item.badge; badge.visibility = View.VISIBLE
                pulseCard(view, t.bg, 0xFF2E7D32.toInt())
            } else badge.visibility = View.GONE
        } else if (item.badge != null) pulseCard(view, t.bg, 0xFF2E7D32.toInt())
        val section = view.findViewById<View>(R.id.overlayExpanded)
        val detail = view.findViewById<TextView>(R.id.overlayDetail)
        if (section != null) {
            section.visibility = if (chip?.expanded == true) View.VISIBLE else View.GONE
            detail?.text = item.detail.ifBlank { item.title }
            detail?.setTextColor(t.status)
        }
        applyThemeToCard(view, t)
    }

    private fun reconcileStack(items: List<FloatingItem>) {
        stackItems = items
        if (items.isEmpty()) { removeStack(); return }
        val chip = stackWindow ?: addStackWindow().also { stackWindow = it }
        val t = theme()
        val list = chip.view.findViewById<LinearLayout>(R.id.stackList)
        val compact = chip.view.findViewById<LinearLayout>(R.id.stackCompact)
        val compactText = chip.view.findViewById<TextView>(R.id.stackCompactText)
        if (chip.minimized) {
            list.visibility = View.GONE; compact.visibility = View.VISIBLE
            compactText.text = "${items.size} items"; compactText.setTextColor(t.status)
        } else {
            list.visibility = View.VISIBLE; compact.visibility = View.GONE
            list.removeAllViews()
            val inf = LayoutInflater.from(chip.view.context)
            items.forEach { m ->
                val row = inf.inflate(R.layout.floating_stack_row, list, false)
                row.findViewById<TextView>(R.id.rowLeft).text = m.left
                row.findViewById<TextView>(R.id.rowRight).text = m.right
                row.findViewById<TextView>(R.id.rowTitle).apply { text = m.title; setTextColor(t.title) }
                row.findViewById<TextView>(R.id.rowStatus).apply { text = m.status; setTextColor(t.status) }
                list.addView(row)
            }
        }
        applyThemeToCard(chip.view, t)
        chip.view.findViewById<TextView>(R.id.stackClose)?.setTextColor(t.close)
        chip.view.findViewById<TextView>(R.id.stackMinimize)?.setTextColor(t.close)
        if (items.any { it.badge != null }) pulseCard(chip.view, t.bg, 0xFF2E7D32.toInt())
    }

    private fun reconcileTicker(items: List<FloatingItem>) {
        if (items.isEmpty()) { removeTicker(); return }
        val chip = tickerWindow ?: addTickerWindow().also { tickerWindow = it }
        val t = theme()
        val content = chip.view.findViewById<LinearLayout>(R.id.tickerContent)
        content.removeAllViews()
        val inf = LayoutInflater.from(chip.view.context)
        items.forEachIndexed { i, m ->
            if (i > 0) content.addView(TextView(chip.view.context).apply { text = "  |  "; setTextColor(t.status) })
            val row = inf.inflate(R.layout.item_ticker, content, false)
            row.findViewById<TextView>(R.id.tickerText).apply { text = "${m.left} ${m.title} ${m.right}".trim(); setTextColor(t.title) }
            row.findViewById<TextView>(R.id.tickerStatus).apply { text = m.status; setTextColor(t.status) }
            content.addView(row)
        }
        applyThemeToCard(chip.view, t)
        if (items.any { it.badge != null }) pulseCard(chip.view, t.bg, 0xFF2E7D32.toInt())
    }

    // ── touch: drag vs tap ──
    private fun onWindowTouch(chip: ChipWindow, event: MotionEvent): Boolean {
        val lp = chip.params
        val wm = windowManager ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                chip.dragging = false; chip.didDrag = false
                chip.touchStartRawX = event.rawX; chip.touchStartRawY = event.rawY
                chip.startX = lp.x; chip.startY = lp.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - chip.touchStartRawX).toInt()
                val dy = (event.rawY - chip.touchStartRawY).toInt()
                if (dx * dx + dy * dy > 64) {
                    chip.dragging = true; chip.didDrag = true
                    lp.x = chip.startX + dx; lp.y = chip.startY + dy
                    runCatching { wm.updateViewLayout(chip.view, lp) }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasDrag = chip.didDrag
                chip.dragging = false; chip.didDrag = false
                if (wasDrag) {
                    if (chip.itemId != -1L) savedPositions[chip.itemId] = lp.x to lp.y
                    if (mode() == Mode.SINGLE) singleWindowPos = lp.x to lp.y
                } else {
                    if (chip.itemId == STACK_KEY) {
                        if (isTapIn(chip.view, R.id.stackClose, event.rawX, event.rawY)) { closeChip(chip); return true }
                        if (isTapIn(chip.view, R.id.stackMinimize, event.rawX, event.rawY) || chip.minimized) {
                            chip.minimized = !chip.minimized; reconcileStack(stackItems); return true
                        }
                    }
                    if (isTapIn(chip.view, R.id.overlayClose, event.rawX, event.rawY)) { closeChip(chip); return true }
                    if (chip.itemId == STACK_KEY || chip.itemId == TICKER_KEY) { onItemTap(null); return true }
                    chip.expanded = !chip.expanded
                    chip.lastItem?.let { bindCard(chip, it) }
                    if (chip.expanded) onItemTapExpanded(chip)
                }
                return true
            }
        }
        return true
    }

    protected open fun onItemTapExpanded(chip: ChipWindow) {}

    private fun isTapIn(host: View, id: Int, rawX: Float, rawY: Float): Boolean {
        val v = host.findViewById<View>(id) ?: return false
        if (v.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return rawX >= loc[0] && rawX <= loc[0] + v.width && rawY >= loc[1] && rawY <= loc[1] + v.height
    }

    private fun closeChip(chip: ChipWindow) {
        when {
            chip.itemId > 0 -> { dismissed += chip.itemId; matchWindows.remove(chip.itemId); removeWindow(chip) }
            stackWindow === chip -> { stackWindow = null; stackItems = emptyList(); removeWindow(chip) }
            tickerWindow === chip -> { tickerWindow = null; removeWindow(chip) }
            else -> removeWindow(chip)
        }
    }

    private fun removeCards() { matchWindows.values.forEach { removeWindow(it) }; matchWindows.clear() }
    private fun removeStack() { stackWindow?.let { removeWindow(it) }; stackWindow = null; stackItems = emptyList() }
    private fun removeTicker() { tickerWindow?.let { removeWindow(it) }; tickerWindow = null }
    private fun removeAllWindows() { removeCards(); removeStack(); removeTicker() }

    private fun removeWindow(chip: ChipWindow) {
        try { (chip.scaledView?.getTag(TAG_PULSING) as? ObjectAnimator)?.cancel() } catch (_: Exception) {}
        runCatching { windowManager?.removeView(chip.view) }
    }

    // ── scale / theme / pulse ──
    private fun applyScale(chip: ChipWindow) {
        val s = scale()
        (chip.scaledView ?: chip.view).let { it.scaleX = s; it.scaleY = s }
        chip.view.post { resizeForScale(chip) }
    }

    private fun resizeForScale(chip: ChipWindow) {
        val view = chip.view
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) {
            view.post { resizeForScale(chip) }; return
        }
        val wm = windowManager ?: return
        val scaled = chip.scaledView ?: view
        val s = scale()
        if (chip.baseWidth == 0) { chip.baseWidth = scaled.width.coerceAtLeast(1); chip.baseHeight = scaled.height.coerceAtLeast(1) }
        var changed = false
        if (s > 1f) {
            val tw = (chip.baseWidth * s).toInt().coerceAtLeast(1)
            val th = (chip.baseHeight * s).toInt().coerceAtLeast(1)
            if (chip.params.width != tw) { chip.params.width = tw; changed = true }
            if (chip.params.height != th) { chip.params.height = th; changed = true }
        } else {
            if (chip.params.width != WindowManager.LayoutParams.WRAP_CONTENT && chip.itemId != TICKER_KEY) {
                chip.params.width = WindowManager.LayoutParams.WRAP_CONTENT; changed = true
            }
        }
        if (changed) runCatching { wm.updateViewLayout(view, chip.params) }
        scaled.pivotX = 0f; scaled.pivotY = 0f
        scaled.scaleX = s; scaled.scaleY = s
    }

    private fun applyThemeToCard(view: View, t: FloatingTheme) {
        val card = when {
            view is com.google.android.material.card.MaterialCardView -> view
            view is FrameLayout -> view.getChildAt(0) as? com.google.android.material.card.MaterialCardView
            else -> null
        } ?: return
        if (card.getTag(TAG_PULSING) == true) return
        card.setCardBackgroundColor(t.bg)
        card.strokeColor = t.border
        card.strokeWidth = 2
    }

    protected fun pulseCard(view: View, fromColor: Int, flashColor: Int) {
        try {
            val card = when {
                view is com.google.android.material.card.MaterialCardView -> view
                view is FrameLayout -> view.getChildAt(0) as? com.google.android.material.card.MaterialCardView
                else -> null
            } ?: return
            (card.getTag(TAG_PULSING) as? ObjectAnimator)?.cancel()
            val anim = ObjectAnimator.ofInt(card, "cardBackgroundColor", fromColor, flashColor, fromColor)
            anim.setEvaluator(ArgbEvaluator())
            anim.duration = 900
            anim.repeatCount = 5
            anim.repeatMode = ValueAnimator.REVERSE
            anim.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationStart(a: android.animation.Animator) { card.setTag(TAG_PULSING, anim) }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    card.setTag(TAG_PULSING, null)
                    runCatching { card.setCardBackgroundColor(theme().bg) }
                }
                override fun onAnimationCancel(a: android.animation.Animator) { card.setTag(TAG_PULSING, null) }
            })
            anim.start()
        } catch (_: Exception) {}
    }

    private fun screenAllows(): Boolean {
        if (screenMode() == SCREEN_BOTH) return true
        val locked = try {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            km.isDeviceLocked || km.isKeyguardLocked
        } catch (_: Exception) { false }
        return if (screenMode() == SCREEN_LOCK) locked else !locked
    }

    companion object {
        const val PREFS = "floatingkit_prefs"
        const val KEY_ENABLED = "floating_enabled"
        const val KEY_MODE = "floating_mode"
        const val KEY_THEME = "overlay_theme"
        const val KEY_SCALE = "window_scale"
        const val KEY_SCREEN = "overlay_screen"

        const val SCREEN_BOTH = "both"
        const val SCREEN_LOCK = "lock"
        const val SCREEN_HOME = "home"

        const val ACTION_SETTINGS = "com.example.floatingkit.SETTINGS"
        const val ACTION_STOP = "com.example.floatingkit.STOP"
        const val TAG_PULSING = 0x7F00F1A5
        const val CHANNEL_ID = "floating_ticker"
        const val NOTIF_ID = 1001

        private const val STACK_KEY = -2L
        private const val TICKER_KEY = -3L

        @Volatile var isRunning = false
            private set
        @Volatile var instance: FloatingOverlayService? = null
            private set

        /** Production permission check: SYSTEM_ALERT_WINDOW. */
        fun canDraw(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true

        fun openSettings(context: Context) {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            } else {
                Intent(Settings.ACTION_SETTINGS)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        fun start(context: Context, serviceClass: Class<*>) {
            val intent = Intent(context, serviceClass)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context, serviceClass: Class<*>) {
            context.stopService(Intent(context, serviceClass))
        }

        fun broadcastSettings(context: Context) {
            context.sendBroadcast(Intent(ACTION_SETTINGS).setPackage(context.packageName))
            (instance as? FloatingOverlayService)?.notifySettingsChanged()
        }
    }
}
