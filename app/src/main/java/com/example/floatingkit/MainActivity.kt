package com.example.floatingkit

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val enableBtn = findViewById<Button>(R.id.enableBtn)
        val startBtn = findViewById<Button>(R.id.startBtn)
        val overlaySwitch = findViewById<Switch>(R.id.overlaySwitch)
        val modeGroup = findViewById<RadioGroup>(R.id.modeGroup)
        val themeSpinner = findViewById<Spinner>(R.id.themeSpinner)
        val scaleLabel = findViewById<TextView>(R.id.scaleLabel)
        val scaleBar = findViewById<SeekBar>(R.id.scaleBar)
        val symbolsEdit = findViewById<EditText>(R.id.symbolsEdit)

        val prefs = getSharedPreferences(FloatingOverlayService.PREFS, MODE_PRIVATE)

        // Watchlist
        symbolsEdit.setText(
            prefs.getString(StockApi.KEY_SYMBOLS, StockApi.DEFAULT_SYMBOLS) ?: StockApi.DEFAULT_SYMBOLS
        )
        findViewById<Button>(R.id.saveBtn).setOnClickListener {
            StockApi.saveWatchlist(this, symbolsEdit.text.toString())
            FloatingOverlayService.broadcastSettings(this)
        }
        findViewById<Button>(R.id.refreshBtn).setOnClickListener {
            FloatingOverlayService.broadcastSettings(this)
        }

        // Theme spinner
        val themes = FloatingThemes.names
        themeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, themes)
        themeSpinner.setSelection(themes.indexOf(prefs.getString(FloatingOverlayService.KEY_THEME, FloatingThemes.CLASSIC)).coerceAtLeast(0))
        themeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs.edit().putString(FloatingOverlayService.KEY_THEME, themes[pos]).apply()
                FloatingOverlayService.broadcastSettings(this@MainActivity)
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        // Mode radios
        when (prefs.getString(FloatingOverlayService.KEY_MODE, FloatingOverlayService.Mode.SINGLE.name)) {
            FloatingOverlayService.Mode.MULTI.name -> modeGroup.check(R.id.modeMulti)
            FloatingOverlayService.Mode.STACK.name -> modeGroup.check(R.id.modeStack)
            FloatingOverlayService.Mode.TICKER.name -> modeGroup.check(R.id.modeTicker)
            else -> modeGroup.check(R.id.modeSingle)
        }
        modeGroup.setOnCheckedChangeListener { _, id ->
            val m = when (id) {
                R.id.modeMulti -> FloatingOverlayService.Mode.MULTI
                R.id.modeStack -> FloatingOverlayService.Mode.STACK
                R.id.modeTicker -> FloatingOverlayService.Mode.TICKER
                else -> FloatingOverlayService.Mode.SINGLE
            }
            prefs.edit().putString(FloatingOverlayService.KEY_MODE, m.name).apply()
            FloatingOverlayService.broadcastSettings(this)
        }

        // Enabled switch
        overlaySwitch.isChecked = prefs.getBoolean(FloatingOverlayService.KEY_ENABLED, true)
        overlaySwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(FloatingOverlayService.KEY_ENABLED, checked).apply()
            FloatingOverlayService.broadcastSettings(this)
        }

        // Scale 0.5x–2.0x
        val currentScale = prefs.getFloat(FloatingOverlayService.KEY_SCALE, 1.0f)
        scaleBar.progress = ((currentScale - 0.5f) * 20).toInt().coerceIn(0, 30)
        fun renderScale() {
            val s = 0.5f + scaleBar.progress / 20f
            scaleLabel.text = getString(R.string.scale_label, s)
        }
        renderScale()
        scaleBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { renderScale() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                prefs.edit().putFloat(FloatingOverlayService.KEY_SCALE, 0.5f + s!!.progress / 20f).apply()
                FloatingOverlayService.broadcastSettings(this@MainActivity)
            }
        })

        enableBtn.setOnClickListener { FloatingOverlayService.openSettings(this) }
        startBtn.setOnClickListener {
            if (FloatingOverlayService.isRunning) FloatingOverlayService.stop(this, DemoFloatingService::class.java)
            else FloatingOverlayService.start(this, DemoFloatingService::class.java)
            updateStatus()
        }
        findViewById<Button>(R.id.flashBtn).setOnClickListener { DemoStore.flashFirst(this) }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val canDraw = FloatingOverlayService.canDraw(this)
        val running = FloatingOverlayService.isRunning
        findViewById<TextView>(R.id.statusText).text = buildString {
            append(if (canDraw) getString(R.string.status_can_draw) else getString(R.string.status_no_draw))
            append("\n")
            append(if (running) getString(R.string.status_service_on) else getString(R.string.status_service_off))
        }
        findViewById<Button>(R.id.startBtn).text =
            if (running) getString(R.string.stop_overlay) else getString(R.string.start_overlay)
    }
}
