package com.example.floatingkit

/** Curated per-market catalog for the Markets browser page (each ≤12 = one backend call). */
data class StockDef(val symbol: String, val name: String)
data class Market(val id: String, val label: String, val stocks: List<StockDef>)

object Markets {

    val ALL = listOf(
        Market("us", "🇺🇸 US", listOf(
            StockDef("AAPL", "Apple"),
            StockDef("MSFT", "Microsoft"),
            StockDef("NVDA", "Nvidia"),
            StockDef("TSLA", "Tesla"),
            StockDef("AMZN", "Amazon"),
            StockDef("GOOGL", "Alphabet"),
            StockDef("META", "Meta"),
            StockDef("SPY", "S&P 500 ETF")
        )),
        Market("uk", "🇬🇧 UK", listOf(
            StockDef("HSBA.L", "HSBC"),
            StockDef("VOD.L", "Vodafone"),
            StockDef("BP.L", "BP"),
            StockDef("GSK.L", "GSK"),
            StockDef("LLOY.L", "Lloyds"),
            StockDef("RIO.L", "Rio Tinto")
        )),
        Market("eu", "🇪🇺 Europe", listOf(
            StockDef("AIR.PA", "Airbus"),
            StockDef("MC.PA", "LVMH"),
            StockDef("SAP.DE", "SAP"),
            StockDef("SIE.DE", "Siemens"),
            StockDef("ASML.AS", "ASML"),
            StockDef("SAN.PA", "Sanofi")
        )),
        Market("ksa", "🇸🇦 Saudi (Tadawul)", listOf(
            StockDef("2222.SR", "Aramco"),
            StockDef("1120.SR", "Al Rajhi"),
            StockDef("1211.SR", "Ma'aden"),
            StockDef("2010.SR", "SABIC"),
            StockDef("1180.SR", "Alinma")
        )),
        Market("uae", "🇦🇪 UAE (DFM)", listOf(
            StockDef("EMAAR.AE", "Emaar"),
            StockDef("DIB.AE", "Dubai Islamic Bank"),
            StockDef("DEWA.AE", "DEWA"),
            StockDef("SALIK.AE", "Salik")
        )),
        Market("qa", "🇶🇦 Qatar", listOf(
            StockDef("QNB.QA", "QNB"),
            StockDef("IQCD.QA", "Industries Qatar"),
            StockDef("QIB.QA", "Qatar Islamic Bank")
        )),
        Market("kw", "🇰🇼 Kuwait", listOf(
            StockDef("NBK.KW", "Natl Bank of Kuwait"),
            StockDef("KFH.KW", "Kuwait Finance House"),
            StockDef("ZAIN.KW", "Zain")
        )),
        Market("eg", "🇪🇬 Egypt (EGX)", listOf(
            StockDef("COMI.CA", "CIB"),
            StockDef("HRHO.CA", "EFG Hermes"),
            StockDef("TMGH.CA", "Talaat Moustafa"),
            StockDef("EAST.CA", "Eastern Co"),
            StockDef("ABUK.CA", "Abu Qir")
        )),
        Market("fx", "💱 Forex", listOf(
            StockDef("EURUSD=X", "Euro / Dollar"),
            StockDef("GBPUSD=X", "Pound / Dollar"),
            StockDef("USDSAR=X", "Dollar / Riyal"),
            StockDef("USDEGP=X", "Dollar / Pound EG"),
            StockDef("USDAED=X", "Dollar / Dirham"),
            StockDef("USDQAR=X", "Dollar / Riyal QA")
        )),
        Market("crypto", "₿ Crypto", listOf(
            StockDef("BTC", "Bitcoin"),
            StockDef("ETH", "Ethereum"),
            StockDef("SOL", "Solana"),
            StockDef("BNB", "BNB"),
            StockDef("XRP", "XRP"),
            StockDef("DOGE", "Dogecoin")
        ))
    )
}
