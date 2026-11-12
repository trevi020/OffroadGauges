package com.offroadgauges.car

import android.graphics.Color
import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.offroadgauges.R
import com.offroadgauges.core.Prefs
import com.offroadgauges.render.GaugeRenderer

/**
 * Main screen: a NavigationTemplate (the only one that gives access to the Surface) with the
 * gauge rendering on the Surface and two buttons in the action strip:
 *  - switch screen (Ambient / Attitude)
 *  - zero the inclinometers
 * The same actions are also drawn on the Surface and respond to touch (onClick).
 */
class GaugeScreen(carContext: CarContext) : Screen(carContext) {

    private val renderer = GaugeRenderer(Prefs(carContext))

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            val s = surfaceContainer.surface ?: return
            renderer.attach(s, surfaceContainer.width, surfaceContainer.height)
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            renderer.detach()
        }

        override fun onStableAreaChanged(stableArea: Rect) {
            renderer.setStableArea(stableArea)
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            // Area not covered by the side-by-side cards (music etc.): decides the 1/3 - 2/3 - full mode.
            renderer.setVisibleArea(visibleArea)
        }

        override fun onClick(x: Float, y: Float) {
            renderer.onClick(x, y)
        }

        // Swipe between the two pages (requires Action.PAN in the map action strip)
        override fun onScroll(distanceX: Float, distanceY: Float) {
            renderer.onScroll(distanceX)
        }

        override fun onFling(velocityX: Float, velocityY: Float) {
            renderer.onFling(velocityX)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                renderer.release()
            }
        })
    }

    private fun icon(res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res))
            .setTint(CarColor.DEFAULT)
            .build()

    override fun onGetTemplate(): Template {
        // NavigationTemplate requires at least one action in the action strip: only one is added, with a
        // transparent icon (switches page when touched). Page switching and zeroing are on the Surface.
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_blank))
                    .setOnClickListener { renderer.togglePage() }
                    .build()
            )
            .build()

        // Action.PAN lets Android Auto deliver scroll/fling (swipe) events to the Surface.
        // On touchscreens the Pan button is not shown.
        val mapStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setMapActionStrip(mapStrip)
            .setBackgroundColor(CarColor.createCustom(Color.BLACK, Color.BLACK))
            .build()
    }
}
