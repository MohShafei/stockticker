/**
 * StockTicker backend — cached multi-provider quotes, $0 free-tier design.
 * Mirrors LiveFootball_II/backend/server.js patterns (in-memory diff cache,
 * /health shape, dotenv + Dockerfile deploy).
 *
 * Sources (all free):
 *  - US stocks  → Finnhub /quote (FINNHUB_KEY, 60 req/min) else Yahoo
 *  - UK/EU/Gulf/EGX/FX → Yahoo v8 chart (no key, query1→query2 fallback)
 *  - Crypto     → Binance /api/v3/ticker/24hr (no key, realtime)
 *
 * Endpoints:
 *  GET /health                        → {status, symbols, lastPollAt, lastPollOk, lastPollError, upstream}
 *  GET /quotes?symbols=AAPL,EMAAR.AE  → {quotes:[{symbol,title,status,chgPct,left,right,detail,badge,src,ts}], cached}
 */
require('dotenv').config();

const express = require('express');
const axios = require('axios');

const PORT = parseInt(process.env.PORT, 10) || 3000;
const FINNHUB_KEY = process.env.FINNHUB_KEY || '';
const HOT_SYMBOLS = (process.env.HOT_SYMBOLS ||
    'AAPL,MSFT,HSBA.L,2222.SR,EMAAR.AE,COMI.CA,EURUSD=X,BTCUSDT')
    .split(',').map(s => s.trim().toUpperCase()).filter(Boolean);
const HOT_REFRESH_MS = parseInt(process.env.HOT_REFRESH_MS, 10) || 60000;

const TTL = {
    crypto: parseInt(process.env.TTL_CRYPTO_MS, 10) || 30 * 1000,
    us: parseInt(process.env.TTL_US_MS, 10) || 60 * 1000,
    intl: parseInt(process.env.TTL_INTL_MS, 10) || 90 * 1000,
};

const cache = new Map(); // SYM -> { payload, ts }
let lastPollAt = null;
let lastPollOk = false;
let lastPollError = null;

const app = express();

app.get('/health', (req, res) => {
    res.status(200).json({
        status: 'OK',
        finnhub: Boolean(FINNHUB_KEY),
        symbols: cache.size,
        lastPollAt,
        lastPollOk,
        lastPollError,
        timestamp: new Date(),
    });
});

// ── symbol routing (mirrors Android StockApi) ──
const CRYPTO_BASES = new Set(['BTC', 'ETH', 'SOL', 'BNB', 'XRP', 'DOGE', 'ADA', 'AVAX', 'LINK', 'TON', 'TRX', 'DOT', 'MATIC', 'LTC', 'BCH', 'NEAR', 'UNI', 'ATOM']);

function toBinance(raw) {
    let s = raw.trim().toUpperCase().replace(/[\s/]/g, '').replace(/\.USD$/, '').replace(/-USD$/, '');
    if (s.endsWith('USDT') && CRYPTO_BASES.has(s.slice(0, -4))) return s;
    if (s.endsWith('USD') && CRYPTO_BASES.has(s.slice(0, -3))) return s.slice(0, -3) + 'USDT';
    if (CRYPTO_BASES.has(s)) return s + 'USDT';
    return null;
}

const FX_QUOTES = new Set(['EUR', 'USD', 'GBP', 'SAR', 'EGP', 'AED', 'QAR', 'KWD', 'BHD', 'OMR', 'JPY', 'CHF', 'CAD', 'AUD', 'CNY', 'INR', 'TRY', 'PKR']);

function normalizeYahoo(raw) {
    let s = raw.trim().toUpperCase().replace(/\s/g, '');
    if (s.includes('/')) {
        const pair = s.replace(/\//g, '');
        if (/^[A-Z]{6}$/.test(pair)) return `${pair}=X`;
        return pair;
    }
    if (/^[A-Z]{6}$/.test(s) && FX_QUOTES.has(s.slice(0, 3)) && FX_QUOTES.has(s.slice(3))) return `${s}=X`;
    return s;
}

function kindOf(symbol) {
    if (toBinance(symbol)) return 'crypto';
    const y = normalizeYahoo(symbol);
    if (/^[A-Z]+$/.test(y) && !y.includes('.') && !y.includes('=')) return 'us';
    return 'intl';
}

function ttlFor(symbol) {
    const k = kindOf(symbol);
    return TTL[k] || TTL.intl;
}

// ── providers ──
const UA = { 'User-Agent': 'Mozilla/5.0 (Linux; Android 14)', Accept: 'application/json' };

async function fetchBinance(display, binance) {
    const r = await axios.get('https://api.binance.com/api/v3/ticker/24hr', {
        params: { symbol: binance }, headers: UA, timeout: 8000,
    });
    const d = r.data || {};
    const price = parseFloat(d.lastPrice);
    if (!price) throw new Error('bad binance price');
    const chgPct = parseFloat(d.priceChangePercent) || 0;
    const base = binance.replace(/USDT$/, '');
    return shape(display, `${base}  ${fmt(price)}`, chgPct,
        '₿', chgPct >= 0 ? '🚀' : '📉',
        `Binance ${base}/USDT realtime` +
        (d.highPrice && d.lowPrice ? ` · L ${fmt(parseFloat(d.lowPrice))} H ${fmt(parseFloat(d.highPrice))}` : ''),
        'binance');
}

async function fetchFinnhub(symbol) {
    const r = await axios.get('https://finnhub.io/api/v1/quote', {
        params: { symbol, token: FINNHUB_KEY }, headers: UA, timeout: 8000,
    });
    const d = r.data || {};
    if (!d.c) throw new Error('bad finnhub quote');
    const chgPct = d.pc ? ((d.c - d.pc) / d.pc) * 100 : 0;
    return shape(symbol, `${symbol}  ${fmt(d.c)}`, chgPct,
        chgPct >= 0 ? '▲' : '▼', chgPct >= 0 ? '📈' : '📉',
        `Finnhub realtime` +
        (d.l && d.h ? ` · L ${fmt(d.l)} H ${fmt(d.h)}` : ''),
        'finnhub');
}

async function fetchYahoo(symbol) {
    const y = normalizeYahoo(symbol);
    const enc = encodeURIComponent(y);
    const hosts = [
        `https://query1.finance.yahoo.com/v8/finance/chart/${enc}?interval=1d&range=2d`,
        `https://query2.finance.yahoo.com/v8/finance/chart/${enc}?interval=1d&range=2d`,
    ];
    let lastErr = null;
    for (const url of hosts) {
        try {
            const r = await axios.get(url, { headers: UA, timeout: 8000 });
            const meta = r.data?.chart?.result?.[0]?.meta;
            if (!meta || !meta.regularMarketPrice) throw new Error('bad yahoo meta');
            const price = meta.regularMarketPrice;
            const prev = meta.chartPreviousClose ?? meta.previousClose ?? price;
            const chgPct = prev ? ((price - prev) / prev) * 100 : 0;
            const isFx = y.endsWith('=X');
            const name = meta.shortName || meta.longName || y;
            return shape(symbol, `${shortSym(y)}  ${fmt(price)}`, chgPct,
                isFx ? '💱' : chgPct >= 0 ? '▲' : '▼',
                chgPct >= 0 ? '📈' : '📉',
                `${name}` +
                (meta.regularMarketDayLow && meta.regularMarketDayHigh
                    ? ` · L ${fmt(meta.regularMarketDayLow)} H ${fmt(meta.regularMarketDayHigh)}` : '') +
                (meta.currency ? ` · ${meta.currency}` : ''),
                'yahoo');
        } catch (e) { lastErr = e; }
    }
    throw lastErr || new Error('yahoo failed');
}

async function fetchOne(symbol) {
    const s = symbol.trim().toUpperCase();
    const binance = toBinance(s);
    if (binance) {
        try { return await fetchBinance(s, binance); }
        catch (e) { return fetchYahoo(s); } // crypto fallback
    }
    if (kindOf(s) === 'us' && FINNHUB_KEY) {
        try { return await fetchFinnhub(s); }
        catch (e) { return fetchYahoo(s); }
    }
    return fetchYahoo(s);
}

// ── shaping (same fields Android FloatingItem needs) ──
function fmt(p) {
    return p >= 1000
        ? p.toLocaleString('en-US', { maximumFractionDigits: 0 })
        : p.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function shortSym(y) {
    if (y.endsWith('=X') && y.length === 8) return `${y.slice(0, 3)}/${y.slice(3, 6)}`;
    return y;
}

function shape(symbol, title, chgPct, left, right, detail, src) {
    const sign = chgPct >= 0 ? '+' : '';
    return {
        symbol,
        title,
        status: `${sign}${chgPct.toFixed(2)}%`,
        chgPct: Math.round(chgPct * 100) / 100,
        left, right, detail, src,
        badge: Math.abs(chgPct) >= 3 ? `⚡ ${sign}${chgPct.toFixed(1)}%` : null,
        ts: new Date().toISOString(),
    };
}

// ── cache + background refresh ──
async function refreshSymbol(symbol) {
    try {
        const q = await fetchOne(symbol);
        cache.set(symbol, { payload: q, ts: Date.now() });
        lastPollAt = new Date().toISOString();
        lastPollOk = true;
        lastPollError = null;
        return q;
    } catch (e) {
        lastPollOk = false;
        lastPollError = `${symbol}: ${e.message}`;
        return null;
    }
}

function fresh(symbol) {
    const hit = cache.get(symbol);
    if (!hit) return null;
    if (Date.now() - hit.ts > ttlFor(symbol) * 2) return null; // stale-but-usable window
    return hit.payload;
}

// Staggered hot refresh: 3 symbols per tick so Yahoo never sees a burst.
let hotIndex = 0;
async function hotLoop() {
    if (HOT_SYMBOLS.length === 0) return;
    const batch = [];
    for (let i = 0; i < 3; i++) {
        batch.push(HOT_SYMBOLS[hotIndex % HOT_SYMBOLS.length]);
        hotIndex += 1;
    }
    await Promise.all(batch.map(s => refreshSymbol(s)));
    // eslint-disable-next-line no-use-before-define
    setTimeout(hotLoop, Math.max(15000, Math.floor(HOT_REFRESH_MS / Math.ceil(HOT_SYMBOLS.length / 3))));
}

// ── routes ──
app.get('/quotes', async (req, res) => {
    const raw = String(req.query.symbols || '').toUpperCase();
    const symbols = [...new Set(
        raw.split(/[,;\n]/).map(s => s.trim()).filter(Boolean)
    )].slice(0, 12);
    if (symbols.length === 0) {
        res.status(400).json({ error: 'symbols required, e.g. /quotes?symbols=AAPL,EMAAR.AE' });
        return;
    }
    const out = [];
    let allCached = true;
    for (const s of symbols) {
        const hit = cache.get(s);
        if (hit && Date.now() - hit.ts <= ttlFor(s)) {
            out.push(hit.payload);
        } else {
            allCached = false;
            const q = await refreshSymbol(s);
            out.push(q || fresh(s) || {
                symbol: s, title: `${s}  …`, status: 'offline', chgPct: 0,
                left: '●', right: '', detail: 'No data yet', badge: null, src: 'none',
                ts: new Date().toISOString(),
            });
        }
    }
    res.set('Cache-Control', 'public, max-age=30');
    res.status(200).json({ quotes: out, cached: allCached });
});

app.listen(PORT, () => {
    console.log(`[Server] StockTicker backend on :${PORT} (finnhub=${Boolean(FINNHUB_KEY)})`);
    hotLoop();
});
