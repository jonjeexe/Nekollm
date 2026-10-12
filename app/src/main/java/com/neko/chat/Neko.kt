package com.neko.chat

/** Chat wrapper: every message goes straight to the model. */
class Neko(private val model: NekoModel) {
    private var history = ""
    private val keep = (model.blockSize - 120).coerceAtLeast(model.blockSize / 2)

    fun reset() {
        history = ""
    }

    @Synchronized
    fun respond(msg: String): String {
        history += "User: $msg\nBot:"
        var ctx = history.takeLast(keep)
        val cut = ctx.indexOf("User:")
        if (cut != -1) ctx = ctx.substring(cut)
        val reply = model.reply(ctx)
        history += " $reply\n"
        return reply
    }
}
