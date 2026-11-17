package com.dilinkauto.client

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * App-icon → bitmap rasterization shared by the wire path
 * ([ClientApp.loadIconPng]) and the allowlist UI (audit R3-DRY-09).
 *
 * BitmapDrawable icons are used directly unless [rescale] is set; other
 * drawables are drawn onto a fresh [size]×[size] canvas. The UI path passes
 * `rescale = false` so high-density bitmaps keep their pixels and Compose
 * scales at draw time; the wire path passes `rescale = true` so the
 * transmitted PNG is exactly [size]px.
 *
 * Ownership of the returned bitmap (audit A-M12): with `rescale = false` it
 * belongs to the drawable and must never be recycled; with `rescale = true`
 * it is either a scaled copy or a freshly drawn canvas bitmap — *except* that
 * `Bitmap.createScaledBitmap` returns the source itself when it is already
 * [size]×[size], so even the wire path must compare identity against the
 * drawable's own bitmap before recycling. [ClientApp.loadIconPng] does that
 * check; do not add a blind `recycle()` here.
 */
object IconRenderer {

    fun toBitmap(icon: Drawable, size: Int, rescale: Boolean): Bitmap {
        val existing = (icon as? BitmapDrawable)?.bitmap
        if (existing != null) {
            return if (rescale) Bitmap.createScaledBitmap(existing, size, size, true)
            else existing
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, size, size)
        icon.draw(Canvas(bmp))
        return bmp
    }
}
