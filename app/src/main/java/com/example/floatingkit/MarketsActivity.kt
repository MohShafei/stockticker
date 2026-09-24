package com.example.floatingkit

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Markets browser: sections per market, each row shows symbol + price + ★.
 * Tapping ★ adds/removes the symbol from the ticker watchlist (same prefs
 * the floating overlay reads, so the ticker updates immediately).
 */
class MarketsActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private data class RowViews(
        val price: TextView,
        val change: TextView,
        val star: Button,
        val def: StockDef
    )
    private val rows = HashMap<String, RowViews>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_markets)
        title = getString(R.string.markets_title)

        val container = findViewById<LinearLayout>(R.id.marketsContainer)
        val inflater = LayoutInflater.from(this)

        for (market in Markets.ALL) {
            // Section header: flag + label + fav count (refreshed in onResume).
            val header = TextView(this).apply {
                text = market.label
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 20, 0, 4)
            }
            header.tag = "header_${market.id}"
            container.addView(header)

            for (def in market.stocks) {
                val row = inflater.inflate(R.layout.item_market_row, container, false)
                val symbolTv = row.findViewById<TextView>(R.id.rowSymbol)
                val nameTv = row.findViewById<TextView>(R.id.rowName)
                val priceTv = row.findViewById<TextView>(R.id.rowPrice)
                val changeTv = row.findViewById<TextView>(R.id.rowChange)
                val starBtn = row.findViewById<Button>(R.id.rowStar)

                symbolTv.text = def.symbol
                nameTv.text = def.name
                priceTv.text = "…"
                changeTv.text = ""
                paintStar(starBtn, StockApi.isFav(this, def.symbol))
                starBtn.setOnClickListener {
                    paintStar(starBtn, StockApi.toggleFav(this, def.symbol))
                }
                rows[def.symbol.uppercase()] = RowViews(priceTv, changeTv, starBtn, def)
                container.addView(row)
            }
        }

        // One batch per market in parallel (each ≤12 = one backend call).
        scope.launch {
            Markets.ALL.map { market ->
                async(Dispatchers.IO) {
                    market.stocks.map { it.symbol } to
                        runCatching { StockApi.fetchQuotes(market.stocks.map { it.symbol }) }
                            .getOrDefault(emptyMap())
                }
            }.awaitAll().forEach { (_, quotes) ->
                for ((sym, item) in quotes) {
                    rows[sym.uppercase()]?.let { bindQuote(it, item) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Stars may have changed via ticker dismiss — repaint.
        for ((sym, row) in rows) {
            paintStar(row.star, StockApi.isFav(this, sym))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun bindQuote(row: RowViews, item: FloatingItem) {
        val price = item.title.substringAfter("  ", item.title).trim()
            .ifBlank { item.title }
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
