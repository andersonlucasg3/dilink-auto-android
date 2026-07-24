package com.dilinkauto.client.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.dilinkauto.client.R

/**
 * Diagnostic fallback screen: a plain PaneTemplate, the simplest template any
 * AA host accepts. If the host renders this but not the NavigationTemplate
 * mirror, the problem is the nav template; if neither renders, the gate is
 * host-side app validation (install source), not the template.
 */
class DiLinkHomeScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val openMirror = Row.Builder()
            .setTitle(carContext.getString(R.string.aa_open_mirror))
            .setOnClickListener { screenManager.push(MirrorScreen(carContext)) }
            .build()

        val exit = Row.Builder()
            .setTitle(carContext.getString(R.string.aa_exit))
            .setOnClickListener { finish() }
            .build()

        val pane = Pane.Builder()
            .addRow(openMirror)
            .addRow(exit)
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}
