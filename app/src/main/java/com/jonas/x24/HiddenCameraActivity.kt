package com.jonas.x24

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

class HiddenCameraActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // This activity would be transparent (Theme.Translucent.NoTitleBar)
        // It would immediately launch the camera intent, or use Camera2 API to take a picture
        // silently if possible (though shutter sound is often mandatory).

        // For Level 1/2, we stick to the standard Intent but launched from here to keep MainActivity clean?
        // Actually, CommandManager already launches the intent.
        // This activity is reserved for future "Stealth Mode" implementation where we use a SurfaceView
        // 1x1 pixel to capture without full UI.

        Log.d("x24Hidden", "Hidden Camera Activity Launched")
        finish()
    }
}
