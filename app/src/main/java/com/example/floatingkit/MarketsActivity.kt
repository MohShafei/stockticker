package com.example.floatingkit

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Markets browser: every market shows its FULL listing (backend /symbols:
 * Nasdaq screener, TwelveData, Binance — 24h cached).
 *
 * - Featured rows load with prices immediately (one batch per market).
 * - "Show all N" expands the full list; prices fill in 12-symbol chunks so
 *   free quotas are never burst.
 * - Search filters across loaded lists; ★ adds/removes ticker symbols and
 *   refreshes the floating overlay instantly.
 */
class MarketsActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class RowViews(
        val row: android.view.View,
        val price: TextView,
        val change: TextView,
        val star: Button,
        val def: StockDef
    )

    private inner class Section(val market: Market) {
        lateinit var header: TextView
        lateinit var box: LinearLayout
        lateinit var toggle: Button
        var full: List<StockDef>? = null
        var expanded = false
        var priceJob: Job? = null
        val rows = LinkedHashMap<String, RowViews>()
    }

    private val sections = mutableListOf<Section>()
    private lateinit var container: LinearLayout
    private lateinit var inflater: LayoutInflater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_markets)
        title = getString(R.string.markets_title)

        container = findViewById(R.id.marketsContainer)
        inflater = LayoutInflater.from(this)

        val search = EditText(this).apply {
            hint = getString(R.string.markets_search_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(8, 24, 8, 8)
        }
        container.addView(search)
        search.addTextChangedListener { applyFilter(it.toString()) }

        for (market in Markets.ALL) {
            val sec = Section(market)
            sections.add(sec)

            sec.header = TextView(this).apply {
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 20, 0, 4)
            }
            container.addView(sec.header)

            sec.box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            container.addView(sec.box)

            sec.toggle = Button(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(0, 4, 0, 12)
            }
            sec.toggle.setOnClickListener { toggleSection(sec) }
            container.addView(sec.toggle)

            // Featured rows instantly (prices fill in below).
            for (def in market.featured) addRow(sec, def)

            renderHeader(sec, null)
            renderToggle(sec)
        }

        // Featured prices first (fast, one batch per market in parallel).
        scope.launch {
            Markets.ALL.map { market ->
                async(Dispatchers.IO) {
                    market to runCatching {
                        StockApi.fetchQuotes(market.featured.map { it.symbol })
                    }.getOrDefault(emptyMap())
                }
            }.awaitAll().forEach { (market, quotes) ->
                sectionOf(market.id)?.let { sec ->
                    for (def in market.featured) {
                        quotes[def.symbol.uppercase()]?.let { item ->
                            sec.rows[def.symbol.uppercase()]?.let { bindQuote(it, item) }
                        }
                    }
                }
            }
        }

        // Full listings in background (24h-cached server side, cheap after first).
        scope.launch {
            Markets.ALL.map { market ->
                async(Dispatchers.IO) {
                    market to runCatching { StockApi.fetchSymbols(market) }
                        .getOrDefault(market.featured)
                }
            }.awaitAll().forEach { (market, full) ->
                sectionOf(market.id)?.let { sec ->
                    sec.full = full
                    renderHeader(sec, full.size)
                    renderToggle(sec)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sections.flatMap { it.rows.values }.forEach { row ->
            paintStar(row.star, StockApi.isFav(this, row.def.symbol))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun sectionOf(id: String) = sections.firstOrNull { it.market.id == id }

    private fun renderHeader(sec: Section, fullCount: Int?) {
        val favs = sec.market.featured.count { StockApi.isFav(this, it.symbol) }
        sec.header.text = if (fullCount != null)
            "${sec.market.label}  ·  $fullCount  ·  ★$favs"
        else
            "${sec.market.label}  ·  ★$favs"
    }

    private fun renderToggle(sec: Section) {
        val n = sec.full?.size
        sec.toggle.text = when {
            sec.expanded -> getString(R.string.markets_show_less)
            n != null -> getString(R.string.markets_show_all, n)
            else -> getString(R.string.markets_loading)
        }
        sec.toggle.isEnabled = sec.full != null || sec.expanded
    }

    private fun toggleSection(sec: Section) {
        sec.expanded = !sec.expanded
        renderToggle(sec)
        if (sec.expanded) expandSection(sec) else collapseSection(sec)
    }

    private fun collapseSection(sec: Section) {
        sec.priceJob?.cancel()
        sec.priceJob = null
        // Drop non-featured, non-starred rows to keep view count sane.
        val featured = sec.market.featured.map { it.symbol.uppercase() }.toSet()
        val it = sec.rows.entries.iterator()
        while (it.hasNext()) {
            val (sym, row) = it.next()
            if (sym !in featured && !StockApi.isFav(this, sym)) {
                sec.box.removeView(row.row)
                it.remove()
            }
        }
    }

    private fun expandSection(sec: Section) {
        val full = sec.full ?: return
        val featured = sec.market.featured.map { it.symbol.uppercase() }.toSet()
        val missing = full.filter { it.symbol.uppercase() !in sec.rows }
        for (def in missing) addRow(sec, def)
        // Chunked price fill (12 per backend call, small pause between).
        sec.priceJob?.cancel()
        sec.priceJob = scope.launch {
            missing.map { it.symbol }.chunked(12).forEach { chunk ->
                try {
                    val quotes = StockApi.fetchQuotes(chunk)
                    for ((sym, item) in quotes) {
                        sec.rows[sym.uppercase()]?.let { bindQuote(it, item) }
                    }
                } catch (_: Exception) {}
                delay(600)
            }
        }
    }

    private fun addRow(sec: Section, def: StockDef): RowViews {
        val row = inflater.inflate(R.layout.item_market_row, sec.box, false)
        val symbolTv = row.findViewById<TextView>(R.id.rowSymbol)
        val nameTv = row.findViewById<TextView>(R.id.rowName)
        val priceTv = row.findViewById<TextView>(R.id.rowPrice)
        val changeTv = row.findViewById<TextView>(R.id.rowChange)
        val starBtn = row.findViewById<Button>(R.id.rowStar)
        symbolTv.text = def.symbol
        nameTv.text = def.name
        priceTv.text = "…"
        paintStar(starBtn, StockApi.isFav(this, def.symbol))
        starBtn.setOnClickListener {
            val fav = StockApi.toggleFav(this, def.symbol)
            paintStar(starBtn, fav)
            renderHeader(sec, sec.full?.size)
        }
        val views = RowViews(row, priceTv, changeTv, starBtn, def)
        sec.rows[def.symbol.uppercase()] = views
        sec.box.addView(row)
        return views
    }

    private fun applyFilter(query: String) {
        val q = query.trim().uppercase()
        for (sec in sections) {
            var visible = 0
            val full = sec.full
            if (q.isBlank()) {
                // Restore: featured always, extras only if expanded.
                for ((sym, row) in sec.rows) {
                    val show = sec.expanded ||
                        sec.market.featured.any { it.symbol.uppercase() == sym } ||
                        StockApi.isFav(this, sym)
                    row.row.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
                    if (show) visible++
                }
                sec.header.visibility = android.view.View.VISIBLE
                sec.toggle.visibility = android.view.View.VISIBLE
            } else {
                // Ensure full list present, then show matches (fetch prices for them).
                if (full != null) {
                    for (def in full) {
                        if (def.symbol.uppercase() !in sec.rows &&
                            (def.symbol.uppercase().contains(q) || def.name.uppercase().contains(q))) {
                            addRow(sec, def)
                        }
                    }
                    val matches = sec.rows.values.filter {
                        it.def.symbol.uppercase().contains(q) || it.def.name.uppercase().contains(q)
                    }
                    for ((sym, row) in sec.rows) {
                        val show = row in matches
                        row.row.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
                    }
                    if (matches.isNotEmpty()) {
                        scope.launch {
                            try {
                                val quotes = StockApi.fetchQuotes(matches.map { it.def.symbol }.take(24))
                                for ((sym, item) in quotes) {
                                    sec.rows[sym.uppercase()]?.let { bindQuote(it, item) }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    visible = matches.size
                }
                val anyVisible = visible > 0
                sec.header.visibility = if (anyVisible) android.view.View.VISIBLE else android.view.View.GONE
                sec.toggle.visibility = if (q.isBlank()) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    private fun bindQuote(row: RowViews, item: FloatingItem) {
        val price = item.title.substringAfter("  ", item.title).trim().ifBlank { item.title }
        row.price.text = price
        row.change.text = item.status
        val up = item.status.trim().startsWith("+")
        val color = if (up) Color.parseColor("#2E7D32") else Color.parseColor("#C62828")
        row.price.setTextColor(color)
        row.change.setTextColor(color)
    }

    private fun paintStar(btn: Button, fav: Boolean) {
        btn.text = if (fav) "★" else "☆"
        btn.setTextColor(if (fav) Color.parseColor("#FFB300") else Color.GRAY)
    }
}
