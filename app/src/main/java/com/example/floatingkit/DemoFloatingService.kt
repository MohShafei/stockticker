package com.example.floatingkit

import android.content.Context

/**
 * Live data source: real stock prices via [StockApi] (Yahoo, free, no key).
 * Falls back to last-good / placeholder rows when offline.
 */
class DemoFloatingService : FloatingOverlayService() {

    override suspend fun getItems(): List<FloatingItem> =
        StockApi.getFloatingItems(this)

    // Poll Yahoo every 60s (free endpoint, be nice; values are delayed ~15min anyway).
    override fun refreshMs(): Long = 60_000L

    override fun onItemTap(item: FloatingItem?) {
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }

    override fun onItemTapExpanded(chip: ChipWindow) {
        chip.lastItem?.let { onItemTap(it) }
    }
}

/** Kept for the Flash pulse demo button (tests badge → pulseCard path). */
object DemoStore {
    fun flashFirst(context: Context) {
        // Inject a synthetic badge item via StockApi cache is complex;
        // simplest: force a settings refresh — live badges come from price moves.
        FloatingOverlayService.broadcastSettings(context)
    }
}
