package com.example.floatingkit

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Direct port of LiveFootball_II DraggableContainer.
 * Lets a horizontal ticker scroll horizontally while vertical drags
 * move the whole floating window.
 */
class DraggableContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onDragStart: (() -> Unit)? = null
    var onDrag: ((dx: Int, dy: Int) -> Unit)? = null
    var onDragEnd: ((saved: Boolean) -> Unit)? = null

    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var decided = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.rawX
                startY = ev.rawY
                dragging = false
                decided = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!decided) {
                    val dx = ev.rawX - startX
                    val dy = ev.rawY - startY
                    if (dx * dx + dy * dy > 256) {
                        decided = true
                        dragging = dy * dy > dx * dx
                        if (dragging) {
                            onDragStart?.invoke()
                            return true
                        }
                    }
                }
                return dragging
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(ev)
        when (ev.action) {
            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.rawX - startX).toInt()
                val dy = (ev.rawY - startY).toInt()
                onDrag?.invoke(dx, dy)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = (ev.rawX - startX).toInt()
                val dy = (ev.rawY - startY).toInt()
                val saved = dx * dx + dy * dy > 64
                dragging = false
                onDragEnd?.invoke(saved)
                return true
            }
        }
        return false
    }
}
