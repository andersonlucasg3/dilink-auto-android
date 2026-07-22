package com.dilinkauto.client.auto

import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.lifecycleScope
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.R

/**
 * Android Auto mirror screen: a map-only NavigationTemplate whose surface
 * shows the VD stream. Taps come back through [SurfaceCallback.onClick].
 */
class MirrorScreen(
    carContext: CarContext
) : Screen(carContext), SurfaceCallback {

    private var controller: AaMirrorController? = null

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    override fun onGetTemplate(): Template {
        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.aa_exit))
                    .setOnClickListener { finish() }
                    .build()
            )
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .build()
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        val width = surfaceContainer.width
        val height = surfaceContainer.height
        val dpi = surfaceContainer.dpi
        FileLog.i(TAG, "AA surface available: ${width}x${height}@${dpi}dpi")
        controller?.stop()
        controller = AaMirrorController(carContext, lifecycleScope).also {
            it.start(width, height, dpi, surface)
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        FileLog.i(TAG, "AA surface destroyed")
        controller?.stop()
        controller = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        // Letterboxing/insets ignored in the MVP — surface matches VD dims
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        // Same as above
    }

    override fun onClick(x: Float, y: Float) {
        controller?.tap(x, y)
    }

    private companion object {
        private const val TAG = "MirrorScreen"
    }
}
