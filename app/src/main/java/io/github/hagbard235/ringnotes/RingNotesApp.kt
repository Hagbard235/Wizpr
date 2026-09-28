package io.github.hagbard235.ringnotes

import android.app.Application
import android.content.Context

class RingNotesApp : Application() {
    val controller: RingController by lazy { RingController(this) }
}

val Context.ringController: RingController
    get() = (applicationContext as RingNotesApp).controller
