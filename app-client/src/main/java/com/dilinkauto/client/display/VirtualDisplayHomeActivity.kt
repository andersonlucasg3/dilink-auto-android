package com.dilinkauto.client.display

import android.app.Activity
import android.os.Bundle

/**
 * A blank black Activity that serves as the "home screen" of the virtual display.
 *
 * When launched with FLAG_ACTIVITY_CLEAR_TASK, it clears all other activities
 * on the virtual display's task stack, returning the VD to a blank state.
 *
 * The car server navigates to its own LauncherScreen when this is visible
 * (the car receives a black video stream).
 */
class VirtualDisplayHomeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No setContentView — defaults to black background from theme
    }
}
