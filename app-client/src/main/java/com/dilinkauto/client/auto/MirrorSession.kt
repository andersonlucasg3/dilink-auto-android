package com.dilinkauto.client.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class MirrorSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return MirrorScreen(carContext)
    }
}
