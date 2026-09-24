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
        twelvedata: Boolean(TWELVEDATA_KEY),
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

// ── full market listings (cached 24h — listings barely change) ──
// Sources: Nasdaq screener (US, no key) · TwelveData /stocks (needs TWELVEDATA_KEY,
// ~10 calls/day fits the 800/day free tier) · Binance (crypto, no key) ·
// curated fallbacks for Gulf/EGX/FX when no key is set.
const TWELVEDATA_KEY = process.env.TWELVEDATA_KEY || '';
const LIST_TTL_MS = 24 * 60 * 60 * 1000;
const listCache = new Map(); // market -> { payload, ts }

// MIC → Yahoo suffix used by the Android app.
const MIC_SUFFIX = {
    XNYS: '', XNAS: '', XASE: '', // US: bare symbol
    XLON: '.L',
    XPAR: '.PA', XAMS: '.AS', XBRU: '.BR', XLIS: '.LS',
    XETR: '.DE', XFRA: '.DE', XSTU: '.DE', XBER: '.DE', XHAM: '.DE', XHAN: '.DE', XMUN: '.DE', XDUS: '.DE',
    XMIL: '.MI', XMAD: '.MC', XSWX: '.SW', XOSL: '.OL', XSTO: '.ST', XHEL: '.HE', XCSE: '.CO',
    XSAU: '.SR',
    XDFM: '.AE', XADS: '.AE',
    DSMD: '.QA', XKUW: '.KW', XBSE: '.BH', MSM: '.OM',
    XCAI: '.CA', EGX: '.CA',
    XTSE: '.TO', XTSX: '.TV',
    XNSE: '.NS', XBOM: '.BO',
};

const MARKET_MIC = {
    uk: ['XLON'],
    eu: ['XPAR', 'XETR', 'XAMS', 'XMIL', 'XMAD', 'XSWX'],
    ksa: ['XSAU'],
    uae: ['XDFM', 'XADS'],
    qa: ['DSMD'],
    kw: ['XKUW'],
    eg: ['XCAI'],
};

async function tdStocks(mic) {
    const r = await axios.get('https://api.twelvedata.com/stocks', {
        params: { exchange: mic, format: 'JSON', apikey: TWELVEDATA_KEY },
        headers: UA, timeout: 15000,
    });
    const arr = Array.isArray(r.data) ? r.data : r.data?.data;
    if (!Array.isArray(arr)) throw new Error(`twelvedata ${mic}: bad shape`);
    return arr
        .filter(d => d && d.symbol && /common|ordinary|ETF/i.test(`${d.type || ''} Common`))
        .map(d => {
            const suffix = MIC_SUFFIX[(d.mic_code || mic).toUpperCase()] ?? '';
            let sym = String(d.symbol).toUpperCase();
            if (suffix && !sym.endsWith(suffix)) sym += suffix;
            return { symbol: sym, name: d.name || sym };
        });
}

async function nasdaqList(exchange) {
    const out = [];
    const limit = 1000;
    for (let offset = 0; ; offset += limit) {
        const r = await axios.get('https://api.nasdaq.com/api/screener/stocks', {
            params: { tableonly: 'true', limit, offset, exchange, download: 'true' },
            headers: { ...UA, Accept: 'application/json' }, timeout: 15000,
        });
        const rows = r.data?.data?.table?.rows;
        if (!Array.isArray(rows) || rows.length === 0) break;
        for (const row of rows) {
            if (row.symbol && row.name) out.push({ symbol: String(row.symbol).toUpperCase(), name: String(row.name) });
        }
        const total = r.data?.data?.totalRecords || 0;
        if (out.length >= total || rows.length < limit) break;
    }
    return out;
}

async function binanceList() {
    const r = await axios.get('https://api.binance.com/api/v3/ticker/24hr', { headers: UA, timeout: 15000 });
    if (!Array.isArray(r.data)) throw new Error('binance bad shape');
    return r.data
        .filter(t => t.symbol?.endsWith('USDT'))
        .map(t => ({ symbol: t.symbol, name: `${t.symbol.replace(/USDT$/, '')} / USDT`, vol: parseFloat(t.quoteVolume) || 0 }))
        .sort((a, b) => b.vol - a.vol)
        .slice(0, 200)
        .map(({ symbol, name }) => ({ symbol, name }));
}

// Curated fallbacks (used when TWELVEDATA_KEY is empty or a source fails).
const FALLBACK_LISTS = {
    ksa: [
        ['2222.SR', 'Saudi Aramco'], ['1120.SR', 'Al Rajhi Bank'], ['1211.SR', "Ma'aden"],
        ['2010.SR', 'SABIC'], ['1180.SR', 'Alinma Bank'], ['1320.SR', 'SABIC Agri'],
        ['2380.SR', 'Al Rajhi REIT'], ['1010.SR', 'Riyad Bank'], ['1050.SR', 'BSF'],
        ['1060.SR', 'Arab National Bank'], ['1080.SR', 'Arabian Centres'], ['1111.SR', 'Bank Albilad'],
        ['1140.SR', 'Bank AlJazira'], ['1150.SR', 'Alinma REIT'], ['1182.SR', 'Amlak'],
        ['1202.SR', 'Paper Home'], ['1210.SR', 'BCI'], ['1212.SR', 'Astra Industrial'],
        ['1214.SR', 'Al Hassan Ghazi'], ['1220.SR', '22 Alalamia'], ['1230.SR', 'Hail Cement'],
        ['1301.SR', 'Atheeb Telecom'], ['1303.SR', 'Electrical Industries'], ['1304.SR', 'Al Yamamah Steel'],
        ['1321.SR', 'East Pipes'], ['1330.SR', 'Al Khaleej Training'], ['4001.SR', 'Al Othaim Markets'],
        ['4002.SR', 'Mouwasat Medical'], ['4003.SR', 'Extra Stores'], ['4004.SR', 'Dallah Healthcare'],
        ['4006.SR', 'Assila Investments'], ['4007.SR', 'Al Hammadi'], ['4008.SR', 'SICO Saudi REIT'],
        ['4009.SR', 'Shams'], ['4011.SR', 'Dr. Sulaiman Al Habib'], ['4012.SR', 'Al Seef Hospitals'],
        ['4013.SR', 'Dar Al Arkan'], ['4014.SR', 'City Cement'], ['4020.SR', 'Al Khaleejiah'],
        ['4030.SR', 'Bahri'], ['4040.SR', 'Saudi Sea Port'], ['4050.SR', 'SASCO'],
        ['4051.SR', 'Baazeem Trading'], ['4061.SR', 'Anaam Holding'], ['4070.SR', 'Tihama'],
        ['4071.SR', 'Al Arabia'], ['4080.SR', 'Aseer Trading'], ['4081.SR', 'Naseej'],
        ['4082.SR', 'Al Rayan'], ['4090.SR', 'Taiba Investments'], ['4100.SR', 'Makkiyoon'],
        ['4110.SR', 'Bawan'], ['4130.SR', 'Solidarity'], ['4140.SR', 'Saudi Industrial'],
        ['4141.SR', 'Al Sorayai'], ['4142.SR', 'Al Kathiri'], ['4150.SR', 'Arriyadh Development'],
        ['4160.SR', 'Thimar'], ['4161.SR', 'BinDawood Holding'], ['4162.SR', 'Almunajem Foods'],
        ['4163.SR', 'Al Moammar Info'], ['4164.SR', 'Naqi Water'], ['4170.SR', 'Tourism Enterprise'],
        ['4180.SR', 'Fitaihi Group'], ['4190.SR', 'Jarir Marketing'], ['4191.SR', 'Abo Moati'],
        ['4192.SR', 'Saudi Enaya'], ['4200.SR', 'AlDrees Petroleum'], ['4210.SR', 'Saudi Research & Media'],
        ['4220.SR', 'Emaar The Economic City'], ['4230.SR', 'Red Sea Intl'], ['4240.SR', 'Fawaz Alhokair'],
        ['4250.SR', 'Jabal Omar'], ['4260.SR', 'Tihama Advertising'], ['4261.SR', 'Al Majed Oud'],
        ['4270.SR', 'Saudi Printing'], ['4280.SR', 'Al Khaleej Training'], ['4290.SR', 'Alkhaleejiah'],
        ['4291.SR', 'National Gypsum'], ['4292.SR', 'Gulf General'], ['4300.SR', 'Dar Alarkan Sukuk'],
        ['4310.SR', 'Stc Group'], ['4320.SR', 'Al Andalus Property'], ['4330.SR', 'Riyadh Cement'],
        ['4331.SR', 'Alkhabeer REIT'], ['4332.SR', 'Mulkia REIT'], ['4333.SR', 'Musharaka REIT'],
        ['4334.SR', 'Al Rajhi REIT'], ['4335.SR', 'Jadwa REIT'], ['4336.SR', 'SICO Saudi REIT Fund'],
        ['4337.SR', 'Derayah REIT'], ['4338.SR', 'Alinma Retail REIT'], ['4339.SR', 'Al Maather REIT'],
        ['4340.SR', 'Alahli REIT 1'], ['4342.SR', 'Al Rajhi MSCI'], ['4344.SR', 'Sedco Capital REIT'],
        ['4345.SR', 'Alinma Hospitality'], ['4346.SR', 'Bonyan REIT'], ['4347.SR', 'Mulkia Gulf REIT'],
        ['4348.SR', 'Al Khabeer Growth'], ['4349.SR', 'AlJazira REIT'], ['6001.SR', 'Halwani Bros'],
        ['6002.SR', 'Herfy Foods'], ['6004.SR', 'Catering Holding'], ['6010.SR', 'NADEC'],
        ['6012.SR', 'Riyadh Poultry'], ['6013.SR', 'Almarai'], ['6014.SR', 'Al Jouf Agriculture'],
        ['6015.SR', 'Ash-Sharqiyah Dev'], ['6020.SR', 'Gulf Union Alahlia'], ['6030.SR', 'Gulf General Coop'],
        ['6031.SR', 'Walaa Insurance'], ['6032.SR', 'Buruj Insurance'], ['6033.SR', 'Al Alamiya Insurance'],
        ['6040.SR', 'Tabuk Agriculture'], ['6041.SR', 'Aljouf Mineral Water'], ['6042.SR', 'Alhasoob'],
        ['6043.SR', 'Dar Alarkan REIT'], ['6044.SR', 'AlAseel'], ['6045.SR', 'Al Babtain Power'],
        ['6046.SR', 'Al Yamamah Cement'], ['6047.SR', 'Al Kathiri Holding'], ['6048.SR', 'Alamar Foods'],
        ['6049.SR', 'TAM Development'], ['6050.SR', 'Al Ahsa Development'], ['6051.SR', 'Al Hokair Group'],
        ['6060.SR', 'Saudi Vitrified Clay'], ['6070.SR', 'Al Jouf Cement'], ['6080.SR', 'Aslak'],
        ['6090.SR', 'Jazan Energy'], ['7001.SR', 'Etihad Atheeb'], ['7002.SR', 'STC Channels'],
        ['7003.SR', 'Arab Sea Info'], ['7004.SR', 'Elm Company'], ['7005.SR', '2P Company'],
        ['7006.SR', 'Sadr Logistics'], ['7007.SR', 'Alhasoob Trading'], ['7008.SR', 'MIS Company'],
        ['7009.SR', 'Saudi Azm'], ['7010.SR', 'STC Group Solutions'], ['7011.SR', 'Al Moammar Info Systems'],
        ['7012.SR', 'Naqel Express'], ['7013.SR', 'Edarat Telecom'], ['7014.SR', 'Intelligent Oud'],
        ['7015.SR', 'Cleen Energy'], ['7016.SR', 'Asg Stainless'], ['7017.SR', 'Paper Home Trading'],
        ['7018.SR', 'Al Saif Stores'], ['7019.SR', 'Al Majed for Oud'], ['7020.SR', 'Etihad Etisalat Mobily'],
        ['7030.SR', 'Zain KSA'], ['7040.SR', 'Etihad Atheeb Go'], ['7050.SR', 'Al Wafrah'],
    ].map(([symbol, name]) => ({ symbol, name })),
    uae: [
        ['EMAAR.AE', 'Emaar Properties'], ['DIB.AE', 'Dubai Islamic Bank'],
        ['DEWA.AE', 'DEWA'], ['SALIK.AE', 'Salik'],
        ['EMAARDEV.AE', 'Emaar Development'], ['EMIRATESNBD.AE', 'Emirates NBD'],
        ['MASHREQ.AE', 'Mashreq Bank'], ['DUBAICOM.AE', 'du Telecom'],
        ['AIRARABIYA.AE', 'Air Arabia'], ['DFM.AE', 'Dubai Financial Market'],
    ].map(([symbol, name]) => ({ symbol, name })),
    qa: [
        ['QNB.QA', 'QNB Group'], ['IQCD.QA', 'Industries Qatar'], ['QIB.QA', 'Qatar Islamic Bank'],
        ['CBQ.QA', 'Commercial Bank'], ['QATI.QA', 'Qatar Insurance'], ['QNNS.QA', 'Qatar Navigation'],
        ['QFLS.QA', 'Qatar Fuel'], ['MPHC.QA', 'Mesaieed Petrochemical'],
    ].map(([symbol, name]) => ({ symbol, name })),
    kw: [
        ['NBK.KW', 'Natl Bank of Kuwait'], ['KFH.KW', 'Kuwait Finance House'],
        ['ZAIN.KW', 'Zain Group'], ['AGLTY.KW', 'Agility'], ['BOUBYAN.KW', 'Boubyan Bank'],
        ['CBK.KW', 'Commercial Bank of Kuwait'], ['GBK.KW', 'Gulf Bank'],
    ].map(([symbol, name]) => ({ symbol, name })),
    eg: [
        ['COMI.CA', 'CIB Egypt'], ['HRHO.CA', 'EFG Hermes'], ['TMGH.CA', 'Talaat Moustafa'],
        ['EAST.CA', 'Eastern Company'], ['ABUK.CA', 'Abu Qir Fertilizers'],
        ['ORWE.CA', 'Oriental Weavers'], ['SKPC.CA', 'Sidi Kerir'], ['ETEL.CA', 'Telecom Egypt'],
        ['AMOC.CA', 'Alexandria Minerals'], ['HELI.CA', 'Heliopolis Housing'],
    ].map(([symbol, name]) => ({ symbol, name })),
    fx: [
        'EURUSD=X', 'GBPUSD=X', 'USDJPY=X', 'USDCHF=X', 'USDCAD=X', 'AUDUSD=X',
        'USDSAR=X', 'USDAED=X', 'USDQAR=X', 'USDKWD=X', 'USDBHD=X', 'USDOMR=X',
        'USDEGP=X', 'USDTRY=X', 'USDINR=X', 'USDPKR=X', 'EURGBP=X', 'EURJPY=X',
    ].map(s => ({ symbol: s, name: s.replace('=X', '').replace(/(.{3})(.{3})/, '$1/$2') })),
};

async function buildList(market) {
    if (market === 'us') {
        const [nasdaq, nyse, amex] = await Promise.all([
            nasdaqList('nasdaq'), nasdaqList('nyse'), nasdaqList('amex'),
        ]);
        const seen = new Set();
        return [...nasdaq, ...nyse, ...amex].filter(d => {
            if (seen.has(d.symbol) || !/^[A-Z.]{1,6}$/.test(d.symbol)) return false;
            seen.add(d.symbol);
            return true;
        });
    }
    if (market === 'crypto') return binanceList();
    if (market === 'fx') return FALLBACK_LISTS.fx;
    const mics = MARKET_MIC[market];
    if (!mics) throw new Error(`unknown market: ${market}`);
    if (TWELVEDATA_KEY) {
        const all = [];
        for (const mic of mics) {
            try {
                // eslint-disable-next-line no-await-in-loop
                const rows = await tdStocks(mic);
                all.push(...rows);
            } catch (e) { console.warn(`[symbols] ${mic}: ${e.message}`); }
        }
        if (all.length > 0) return all;
    }
    if (FALLBACK_LISTS[market]) return FALLBACK_LISTS[market];
    throw new Error(`no source for market: ${market} (set TWELVEDATA_KEY)`);
}

app.get('/symbols', async (req, res) => {
    const market = String(req.query.market || '').toLowerCase();
    if (!market) {
        res.status(400).json({ error: 'market required, e.g. /symbols?market=ksa (us,uk,eu,ksa,uae,qa,kw,eg,fx,crypto)' });
        return;
    }
    const hit = listCache.get(market);
    if (hit && Date.now() - hit.ts < LIST_TTL_MS) {
        res.set('Cache-Control', 'public, max-age=86400');
        res.status(200).json({ ...hit.payload, cached: true });
        return;
    }
    try {
        const symbols = await buildList(market);
        const payload = { market, count: symbols.length, symbols };
        listCache.set(market, { payload, ts: Date.now() });
        res.set('Cache-Control', 'public, max-age=86400');
        res.status(200).json({ ...payload, cached: false });
    } catch (e) {
        res.status(502).json({ error: e.message });
    }
});

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
