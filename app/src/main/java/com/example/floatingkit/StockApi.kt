package com.example.floatingkit

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Multi-provider free feed — max market coverage, $0, no keys required.
 *
 * Routing (all verified working, no signup):
 *  - Crypto (BTC, ETH, …)       → Binance public  /api/v3/ticker/24hr (realtime, ~15s)
 *  - Everything else incl. Gulf → Yahoo v8 chart (delayed ~15min, but covers US/UK/EU/Gulf/EGX/forex)
 *  - US upgrade path            → Finnhub / TwelveData key in BuildConfig when you add one
 *                                 (backend cache recommended then; see README)
 *
 * Symbol formats accepted (normalized internally):
 *  US:    AAPL, MSFT, SPY
 *  UK:    HSBA.L, VOD.L, BP.L
 *  EU:    AIR.PA, SAP.DE, ASML.AS
 *  KSA:   2222.SR (Aramco), 1120.SR (AlRajhi), 1211.SR
 *  UAE:   EMAAR.AE, DIB.AE, FAB.AE, ALDAR.AE
 *  Qatar: QNB.QA   Kuwait: NBK.KW   Bahrain: ALBH.BH   Oman: BKMB.OM
 *  Egypt: COMI.CA, HRHO.CA, TMGH.CA
 *  Jordan: NOT covered by Yahoo — shows offline placeholder (see README)
 *  FX:    EURUSD, EUR/USD, USD/SAR, USDEGP=X  → Yahoo EURUSD=X …
 *  Crypto: BTC, BTC-USD, BTCUSDT, ETH/USD     → Binance BTCUSDT …
 */
object StockApi {

    const val KEY_SYMBOLS = "watch_symbols"
    const val DEFAULT_SYMBOLS = "AAPL,MSFT,HSBA.L,2222.SR,COMI.CA,EURUSD=X,BTC-USD"
    private const val TAG = "StockApi"
    private const val UA = "Mozilla/5.0 (Linux; Android 14)"

    @Volatile private var lastGood: List<FloatingItem> = emptyList()
    private val lastPrice = mutableMapOf<String, Double>()

    // concurrency: Binance+Yahoo in parallel, cap 12 symbols
    fun watchlist(context: Context): List<String> {
        val raw = context.getSharedPreferences(FloatingOverlayService.PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SYMBOLS, DEFAULT_SYMBOLS) ?: DEFAULT_SYMBOLS
        return raw.split(",", ";", "\n")
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)
            .ifEmpty { listOf("AAPL") }
    }

    fun saveWatchlist(context: Context, raw: String) {
        context.getSharedPreferences(FloatingOverlayService.PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SYMBOLS, raw.uppercase()).apply()
    }

    /** Star state for the Markets page — single source of truth is the watchlist. */
    fun isFav(context: Context, symbol: String): Boolean =
        watchlist(context).contains(symbol.trim().uppercase())

    /** Toggle star → returns new state. Triggers overlay refresh. */
    fun toggleFav(context: Context, symbol: String): Boolean {
        val sym = symbol.trim().uppercase()
        val current = watchlist(context).toMutableList()
        val nowFav = if (current.contains(sym)) {
            current.remove(sym); false
        } else {
            if (current.size >= 12) current.removeAt(0)
            current.add(sym); true
        }
        saveWatchlist(context, current.joinToString(","))
        FloatingOverlayService.broadcastSettings(context)
        return nowFav
    }

    /**
     * Batch quotes for the Markets page (one backend call per market, each ≤12).
     * Backend first, direct providers per-symbol fallback. Missing → absent from map.
     */
    suspend fun fetchQuotes(symbols: List<String>): Map<String, FloatingItem> =
        withContext(Dispatchers.IO) {
            fetchBackendMap(symbols)?.takeIf { it.isNotEmpty() }
                ?: symbols.mapNotNull { s ->
                    try { fetchRouted(s)?.let { s.trim().uppercase() to it } }
                    catch (_: Exception) { null }
                }.toMap()
        }

    suspend fun getFloatingItems(context: Context): List<FloatingItem> {
        val symbols = watchlist(context)
        // 1) Backend cache first (one shared poll serves all phones).
        fetchBackend(symbols)?.let {
            if (it.isNotEmpty()) {
                lastGood = it
                return it
            }
        }
        // 2) Direct providers fallback (works with no backend configured).
        val fresh = fetchAll(symbols)
        if (fresh.isNotEmpty()) {
            lastGood = fresh
            return fresh
        }
        return lastGood.ifEmpty { symbols.map { demoItem(it) } }
    }

    /** Backend-first path: GET $SERVER_BASE/quotes?symbols=… (empty base = skipped). */
    private suspend fun fetchBackend(symbols: List<String>): List<FloatingItem>? =
        withContext(Dispatchers.IO) {
            val bySym = fetchBackendMap(symbols) ?: return@withContext null
            val out = ArrayList<FloatingItem>(bySym.size)
            // Preserve watchlist order.
            for (s in symbols) bySym[s.uppercase()]?.let { out += it }
            out.ifEmpty { null }
        }

    private suspend fun fetchBackendMap(symbols: List<String>): Map<String, FloatingItem>? =
        withContext(Dispatchers.IO) {
            val base = try { BuildConfig.SERVER_BASE.trim().trimEnd('/') } catch (_: Exception) { "" }
            if (base.isEmpty()) return@withContext null
            try {
                val url = URL("$base/quotes?symbols=" + URLEncoder.encode(symbols.joinToString(","), "UTF-8"))
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 12000
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode != 200) return@withContext null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val arr = JSONObject(body).optJSONArray("quotes") ?: return@withContext null
                val bySym = HashMap<String, FloatingItem>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val sym = o.optString("symbol", "").uppercase()
                    if (sym.isBlank()) continue
                    bySym[sym] = FloatingItem(
                        id = abs(sym.hashCode().toLong()) + 1000,
                        title = o.optString("title", sym),
                        status = o.optString("status", ""),
                        left = o.optString("left", ""),
                        right = o.optString("right", ""),
                        detail = o.optString("detail", ""),
                        badge = o.optString("badge", "").takeIf { it.isNotBlank() }
                    )
                }
                bySym.ifEmpty { null }
            } catch (e: Exception) {
                Log.w(TAG, "backend err=${e.message}")
                null
            }
        }

    private suspend fun fetchAll(symbols: List<String>): List<FloatingItem> =
        withContext(Dispatchers.IO) {
            symbols.map { sym -> async { fetchRouted(sym) } }.awaitAll().filterNotNull()
        }

    private fun fetchRouted(raw: String): FloatingItem? {
        val binance = toBinanceSymbol(raw)
        if (binance != null) return fetchBinance(raw.uppercase(), binance)
        return fetchYahoo(normalizeYahoo(raw))
    }

    // ── crypto → Binance (free, realtime, no key) ──
    private val CRYPTO_BASES = setOf(
        "BTC", "ETH", "SOL", "BNB", "XRP", "DOGE", "ADA", "AVAX", "LINK",
        "TON", "TRX", "DOT", "MATIC", "LTC", "BCH", "NEAR", "UNI", "ATOM"
    )

    private fun toBinanceSymbol(raw: String): String? {
        var s = raw.trim().uppercase().replace(" ", "").replace("/", "")
        s = s.removeSuffix(".USD").removeSuffix("-USD")
        if (s.endsWith("USDT") && s.dropLast(4) in CRYPTO_BASES) return s
        if (s.endsWith("USD") && s.dropLast(3) in CRYPTO_BASES) return s.dropLast(3) + "USDT"
        if (s in CRYPTO_BASES) return s + "USDT"
        return null
    }

    private fun fetchBinance(display: String, binance: String): FloatingItem? {
        return try {
            val url = URL("https://api.binance.com/api/v3/ticker/24hr?symbol=$binance")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("User-Agent", UA); setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) return fetchYahoo(normalizeYahoo(display))
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val o = JSONObject(body)
            val price = o.optString("lastPrice", "").toDoubleOrNull() ?: return null
            val chgPct = o.optString("priceChangePercent", "0").toDoubleOrNull() ?: 0.0
            val high = o.optString("highPrice", "").toDoubleOrNull()
            val low = o.optString("lowPrice", "").toDoubleOrNull()
            val base = binance.removeSuffix("USDT")
            makeItem(
                key = display, title = "$base  ${fmtPrice(price)}",
                chgPct = chgPct, left = "₿", right = if (chgPct >= 0) "🚀" else "📉",
                detail = "Binance $base/USDT realtime" +
                    (if (high != null && low != null) " · L ${fmtPrice(low)} H ${fmtPrice(high)}" else "")
            )
        } catch (e: Exception) {
            Log.w(TAG, "$display binance err=${e.message}")
            fetchYahoo(normalizeYahoo(display))
        }
    }

    // ── stocks + forex → Yahoo (free, no key, widest coverage) ──
    private fun normalizeYahoo(raw: String): String {
        var s = raw.trim().uppercase().replace(" ", "")
        if (s.contains("/")) {
            // "EUR/USD" → "EURUSD=X", "BTC/USD" handled by Binance above (fallback only)
            val pair = s.replace("/", "")
            if (pair.length == 6 && pair.all { it.isLetter() }) return "${pair}=X"
            return pair
        }
        // bare "EURUSD" forex → "EURUSD=X"
        if (s.length == 6 && s.all { it.isLetter() } && s != "BTCUSD" && s != "ETHUSD") {
            // could be a 6-letter stock, but overwhelmingly FX — Yahoo resolves both
            if (isFxPair(s)) return "$s=X"
        }
        return s
    }

    private val FX_QUOTES = setOf(
        "EUR", "USD", "GBP", "SAR", "EGP", "AED", "QAR", "KWD", "BHD",
        "OMR", "JPY", "CHF", "CAD", "AUD", "CNY", "INR", "TRY", "PKR"
    )

    private fun isFxPair(s: String): Boolean =
        s.take(3) in FX_QUOTES && s.drop(3) in FX_QUOTES

    private fun fetchYahoo(symbol: String): FloatingItem? {
        val enc = URLEncoder.encode(symbol, "UTF-8").replace("+", "%20")
        val urls = listOf(
            "https://query1.finance.yahoo.com/v8/finance/chart/$enc?interval=1d&range=2d",
            "https://query2.finance.yahoo.com/v8/finance/chart/$enc?interval=1d&range=2d"
        )
        for (u in urls) {
            try {
                val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000
                    setRequestProperty("User-Agent", UA); setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode != 200) continue
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                return parseYahoo(symbol, JSONObject(body)) ?: continue
            } catch (e: Exception) {
                Log.w(TAG, "$symbol yahoo err=${e.message}")
            }
        }
        return null
    }

    private fun parseYahoo(symbol: String, root: JSONObject): FloatingItem? {
        val result = root.optJSONObject("chart")
            ?.optJSONArray("result")?.optJSONObject(0) ?: return null
        val meta = result.optJSONObject("meta") ?: return null
        val price = meta.optDouble("regularMarketPrice", Double.NaN)
        if (price.isNaN()) return null
        var prev = meta.optDouble("chartPreviousClose", Double.NaN)
        if (prev.isNaN()) prev = meta.optDouble("previousClose", Double.NaN)
        val dayHigh = meta.optDouble("regularMarketDayHigh", Double.NaN)
        val dayLow = meta.optDouble("regularMarketDayLow", Double.NaN)
        val vol = meta.optLong("regularMarketVolume", -1L)
        val name = meta.optString("shortName", meta.optString("longName", symbol))
        val ccy = meta.optString("currency", "")
        val chgPct = if (!prev.isNaN() && prev != 0.0) (price - prev) / prev * 100 else 0.0
        val isFx = symbol.endsWith("=X")
        return makeItem(
            key = symbol, title = "${shortSym(symbol)}  ${fmtPrice(price)}",
            chgPct = chgPct, left = if (isFx) "💱" else if (chgPct >= 0) "▲" else "▼",
            right = if (chgPct >= 0) "📈" else "📉",
            detail = buildString {
                append(name)
                if (!dayLow.isNaN() && !dayHigh.isNaN()) append(" · L ${fmtPrice(dayLow)} H ${fmtPrice(dayHigh)}")
                if (vol > 0) append(" · Vol ${vol / 1_000_000}M")
                if (ccy.isNotBlank()) append(" · $ccy")
            }
        )
    }

    private fun shortSym(symbol: String): String {
        // "2222.SR" stays; "EURUSD=X" → "EUR/USD"
        if (symbol.endsWith("=X") && symbol.length == 8) return "${symbol.take(3)}/${symbol.drop(3).take(3)}"
        return symbol
    }

    private fun makeItem(key: String, title: String, chgPct: Double, left: String, right: String, detail: String): FloatingItem {
        val up = chgPct >= 0
        val sign = if (up) "+" else ""
        val status = "$sign${"%.2f".format(chgPct)}%"
        val prevTick = lastPrice[key]
        lastPrice[key] = title.substringAfterLast(" ").replace(",", "").toDoubleOrNull() ?: 0.0
        val tickPct = if (prevTick != null && prevTick != 0.0) {
            val cur = lastPrice[key] ?: 0.0
            abs(cur - prevTick) / prevTick * 100
        } else 0.0
        val badge = when {
            abs(chgPct) >= 3.0 -> "⚡ $sign${"%.1f".format(chgPct)}%"
            tickPct >= 1.0 -> "● ${if (up) "▲" else "▼"}"
            else -> null
        }
        return FloatingItem(
            id = abs(key.hashCode().toLong()) + 1000,
            title = title, status = status, left = left, right = right,
            detail = detail, badge = badge
        )
    }

    private fun fmtPrice(p: Double): String =
        if (p >= 1000) "%,.0f".format(p) else "%,.2f".format(p)

    private fun demoItem(symbol: String) = FloatingItem(
        id = abs(symbol.hashCode().toLong()) + 1000,
        title = "$symbol  …", status = "offline", left = "●", right = "",
        detail = "No connection — showing cached symbols"
    )
}
