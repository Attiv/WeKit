package dev.ujhhgtg.wekit.features.items.beautify

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.min

/** Tail styles for built-in theme bubbles. */
enum class BubbleTail {
    /** Plain rounded rectangle. */
    NONE,

    /** Small curved tail at the bottom outer corner (Telegram / iMessage style). */
    BOTTOM_CURVE,

    /** Triangle flag at the top outer corner (WhatsApp style). */
    TOP_TRIANGLE,
}

/**
 * Bubble background for built-in themes: a rounded rectangle plus an optional tail on the sender
 * side. Body and tail are unioned into a single path so translucent colors don't double-blend at
 * the seam. Being drawn (not decoded) it is resolution-independent and needs no nine-patch asset.
 *
 * The path is built for a right-side (self) tail and mirrored horizontally for the other side.
 */
class ThemeBubbleDrawable(
    color: Int,
    private val cornerRadiusPx: Float,
    private val tail: BubbleTail,
    private val isSelfSender: Boolean,
    density: Float,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    private val tailWidth = 6f * density
    private val tailSpan = 12f * density
    private val attachRadius = 4f * density

    private val path = Path()

    override fun onBoundsChange(bounds: Rect) {
        rebuildPath(bounds)
    }

    private fun rebuildPath(bounds: Rect) {
        path.rewind()
        if (bounds.isEmpty) return

        val full = RectF(bounds)
        if (tail == BubbleTail.NONE) {
            val radius = min(cornerRadiusPx, min(full.width(), full.height()) / 2f)
            path.addRoundRect(full, radius, radius, Path.Direction.CW)
            return
        }

        val body = RectF(full.left, full.top, full.right - tailWidth, full.bottom)
        val radius = min(cornerRadiusPx, min(body.width(), body.height()) / 2f)
        val attach = min(attachRadius, radius)
        val span = min(tailSpan, body.height() / 2f)

        // The corner the tail grows out of stays nearly square so the tail merges into the body.
        val radii = when (tail) {
            BubbleTail.BOTTOM_CURVE ->
                floatArrayOf(radius, radius, radius, radius, attach, attach, radius, radius)

            else ->
                floatArrayOf(radius, radius, attach, attach, radius, radius, radius, radius)
        }
        path.addRoundRect(body, radii, Path.Direction.CW)

        val tailPath = Path()
        when (tail) {
            BubbleTail.BOTTOM_CURVE -> {
                tailPath.moveTo(body.right, body.bottom - span)
                tailPath.quadTo(
                    body.right + tailWidth * 0.4f, body.bottom - span * 0.25f,
                    full.right, body.bottom,
                )
                tailPath.quadTo(
                    body.right - span * 0.2f, body.bottom,
                    body.right - span * 0.6f, body.bottom,
                )
                tailPath.close()
            }

            BubbleTail.TOP_TRIANGLE -> {
                tailPath.moveTo(body.right - span * 0.5f, body.top)
                tailPath.lineTo(full.right, body.top)
                tailPath.lineTo(body.right, body.top + span * 0.8f)
                tailPath.close()
            }

            BubbleTail.NONE -> {}
        }
        path.op(tailPath, Path.Op.UNION)

        if (!isSelfSender) {
            path.transform(Matrix().apply { setScale(-1f, 1f, full.centerX(), 0f) })
        }
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
