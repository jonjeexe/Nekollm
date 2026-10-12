package com.neko.chat

import android.os.Handler
import android.os.Looper

/** Process-wide chat state so the chat survives theme changes (activity recreation). */
object ChatState {
    class Msg(val user: Boolean, var text: String)

    val msgs = ArrayList<Msg>()
    var neko: Neko? = null
    var busy = false
    var loading = false
    var status = "No model loaded. Tap ⋮ → Import model and pick your model.cde."
    var ui: MainActivity? = null
    val main = Handler(Looper.getMainLooper())
}
