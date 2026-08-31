package io.github.shhhapp.shhh

import android.app.Application
import io.github.shhhapp.shhh.widget.registerWallpaperColorsListener

class ShhhApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Every process start re-arms the wallpaper listener, so the
        // transparent widget recolors the moment a wallpaper change lands —
        // for as long as Android keeps this process cached. After a process
        // death the next publish (a tap, a surface refresh, the half-hourly
        // update) both refreshes the colors and re-arms this.
        registerWallpaperColorsListener(this)
    }
}
