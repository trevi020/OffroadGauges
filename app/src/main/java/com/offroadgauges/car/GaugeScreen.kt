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
 * Schermata principale: un NavigationTemplate (l'unico che da' accesso alla Surface) con il
 * rendering dei gauge sulla Surface e due pulsanti nella action strip:
 *  - cambia schermata (Ambiente / Assetto)
 *  - azzera gli inclinometri
 * Le stesse azioni sono anche disegnate sulla Surface e rispondono al tocco (onClick).
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
            // Area non coperta dalle schede affiancate (musica ecc.): decide la modalita' 1/3 - 2/3 - intero.
            renderer.setVisibleArea(visibleArea)
        }

        override fun onClick(x: Float, y: Float) {
            renderer.onClick(x, y)
        }

        // Swipe tra le due pagine (richiede Action.PAN nella map action strip)
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
        // NavigationTemplate vuole almeno un'azione nella action strip: ne metto una sola, con icona
        // trasparente (cambia pagina se toccata). Cambio pagina e azzeramento sono sulla Surface.
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_blank))
                    .setOnClickListener { renderer.togglePage() }
                    .build()
            )
            .build()

        // Action.PAN serve ad Android Auto per consegnare scroll/fling (swipe) alla Surface.
        // Su touchscreen il pulsante Pan non viene mostrato.
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
