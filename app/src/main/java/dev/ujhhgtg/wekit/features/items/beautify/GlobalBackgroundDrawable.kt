package dev.ujhhgtg.wekit.features.items.beautify

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The chat wallpaper layer of [ApplyGlobalBackground], drawn center-cropped into its bounds.
 *
 * It is a [Drawable] living in the decor view's [android.view.ViewOverlay] instead of a full-screen
 * ImageView added as a child of the decor view. A full-screen ImageView on top of every WeChat
 * window makes vendor "pick up whatever picture is under your finger" gestures — realme/ColorOS 的
 * AI 传送门 among them — resolve *any* long press (on a voice message, on a bubble, anywhere) to a
 * long press on an image. Overlay drawables are not part of the view hierarchy, so those scans
 * never find this one, while it still draws above every child and stays out of touch dispatch,
 * accessibility and content capture entirely.
 */
class GlobalBackgroundDrawable : Drawable() {

    /**
     * What [image] currently shows (the picked image uri, or a theme token). A resume that would
     * install the same thing again is skipped.
     */
    var token: String? = null

    /** Hidden while a full-screen image viewer is open, without being detached. */
    var hidden: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    private var image: Drawable? = null
    private var imageAlpha: Int = 255

    fun setImage(drawable: Drawable?) {
        image = drawable?.mutate()?.apply { alpha = imageAlpha }
        layoutImage()
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        layoutImage()
        invalidateSelf()
    }

    /** Scales [image] to cover the bounds, keeping its aspect ratio and centering the overflow. */
    private fun layoutImage() {
        val current = image ?: return
        if (bounds.isEmpty) return

        val width = current.intrinsicWidth
        val height = current.intrinsicHeight
        // The theme wallpapers are gradients and carry no intrinsic size: they simply fill.
        if (width <= 0 || height <= 0) {
            current.bounds = bounds
            return
        }

        val scale = max(bounds.width().toFloat() / width, bounds.height().toFloat() / height)
        val scaledWidth = (width * scale).roundToInt()
        val scaledHeight = (height * scale).roundToInt()
        val left = bounds.left + (bounds.width() - scaledWidth) / 2
        val top = bounds.top + (bounds.height() - scaledHeight) / 2
        current.setBounds(left, top, left + scaledWidth, top + scaledHeight)
    }

    override fun draw(canvas: Canvas) {
        if (hidden) return
        val current = image ?: return
        if (bounds.isEmpty) return

        // The image overflows the bounds on the cropped axis; clip so it never bleeds outside.
        val saved = canvas.save()
        canvas.clipRect(bounds)
        current.draw(canvas)
        canvas.restoreToCount(saved)
    }

    override fun setAlpha(alpha: Int) {
        if (imageAlpha == alpha) return
        imageAlpha = alpha
        image?.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        image?.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
