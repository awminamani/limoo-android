package app.limoo.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.annotation.Keep
import android.view.View

/**
 * The dot-grid bar from the app's design language, as a plain Android View so it can live inside a
 * notification (Compose cannot render there). Dots fill left to right; the lit colour is the accent so
 * the strip doubles as a "data is flowing" indicator.
 */
@Keep
class DotStripView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    /**
     * 0f..1f - how much of the strip is filled.
     *
     * RemoteViews drives this from the notification, and it reaches the setter reflectively by the name
     * `setFraction(float)` - which a Kotlin property generates on its own. Do NOT also declare a
     * `fun setFraction(v: Float)`: the two have the same JVM signature and the build fails with a
     * platform declaration clash. `@Keep` stops R8 from stripping that generated setter in release builds.
     */
    @Keep
    var fraction: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var count = 0
    private var step = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val r = h / 2f
        step = r * 2f + r * 0.9f                       // dot diameter + gap, derived from the height
        count = if (step <= 0f) 0 else (w / step).toInt().coerceAtLeast(1)
    }

    override fun onDraw(canvas: Canvas) {
        if (count == 0 || step <= 0f) return
        val r = step / 2.9f
        val cy = height / 2f
        val lit = (fraction * count).toInt()
        for (i in 0 until count) {
            paint.color = if (i < lit) Color.parseColor("#FF3B30") else Color.parseColor("#2A2A2A")
            canvas.drawCircle(i * step + r, cy, r, paint)
        }
    }
}
