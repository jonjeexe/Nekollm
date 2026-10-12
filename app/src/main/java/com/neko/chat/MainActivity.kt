package com.neko.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private lateinit var chatBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnMic: ImageButton
    private lateinit var status: TextView
    private lateinit var wallpaperView: ImageView
    private lateinit var scrim: View

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wallBitmap: Bitmap? = null

    private val prefs by lazy { getSharedPreferences("neko", MODE_PRIVATE) }
    private val modelFile get() = File(filesDir, "model.cde")
    private val wallFile get() = File(filesDir, "wallpaper.jpg")

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }
    private val wallPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importWallpaper(uri)
    }
    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) send(text, true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Theme: 0 = auto/system, 1 = light, 2 = dark
        AppCompatDelegate.setDefaultNightMode(nightModeFor(themeChoice()))
        super.onCreate(savedInstanceState)

        // Edge-to-edge so the wallpaper fills the whole screen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false

        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.root)
        content = findViewById(R.id.content)
        chatBox = findViewById(R.id.chatBox)
        scroll = findViewById(R.id.scroll)
        input = findViewById(R.id.input)
        btnSend = findViewById(R.id.btnSend)
        btnMic = findViewById(R.id.btnMic)
        status = findViewById(R.id.status)
        wallpaperView = findViewById(R.id.wallpaper)
        scrim = findViewById(R.id.scrim)

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val controller = WindowCompat.getInsetsController(window, root)
        controller.isAppearanceLightStatusBars = !night
        controller.isAppearanceLightNavigationBars = !night

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            content.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        // Wrapping multi-line text box that still shows a "Send" key on the keyboard
        input.setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else false
        }

        btnSend.setOnClickListener { send() }
        btnMic.setOnClickListener { startVoice() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu(it) }

        tts = TextToSpeech(applicationContext) { s ->
            ttsReady = s == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
        }

        ChatState.ui = this
        loadWallpaper()
        if (ChatState.neko == null && !ChatState.loading && modelFile.exists()) loadSavedModel()
        refresh()
    }

    override fun onDestroy() {
        if (ChatState.ui === this) ChatState.ui = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    // ---------------- Menu ----------------

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor, Gravity.END)
        pm.menu.add(0, 1, 0, "Import model")
        pm.menu.add(0, 2, 1, "Settings")
        pm.menu.add(0, 3, 2, "Clear chat")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> if (ChatState.busy) toast("Please wait…") else modelPicker.launch(arrayOf("*/*"))
                2 -> showSettings()
                3 -> clearChat()
            }
            true
        }
        pm.show()
    }

    // ---------------- Settings ----------------

    private fun SeekBar.onChange(cb: (Int) -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) cb(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun showSettings() {
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(v)
            .setPositiveButton("Done", null)
            .create()

        // Theme
        val rg = v.findViewById<RadioGroup>(R.id.rgTheme)
        rg.check(when (themeChoice()) { 1 -> R.id.rbLight; 2 -> R.id.rbDark; else -> R.id.rbAuto })
        rg.setOnCheckedChangeListener { _, id ->
            val c = when (id) { R.id.rbLight -> 1; R.id.rbDark -> 2; else -> 0 }
            if (c != themeChoice()) {
                prefs.edit().putInt("theme", c).apply()
                dlg.dismiss()
                AppCompatDelegate.setDefaultNightMode(nightModeFor(c)) // recreates the screen
            }
        }

        // Wallpaper
        v.findViewById<Button>(R.id.btnWallChoose).setOnClickListener { wallPicker.launch(arrayOf("image/*")) }
        v.findViewById<Button>(R.id.btnWallRemove).setOnClickListener { removeWallpaper() }

        // Wallpaper blur
        val tvBlur = v.findViewById<TextView>(R.id.tvBlur)
        val sbBlur = v.findViewById<SeekBar>(R.id.sbBlur)
        sbBlur.progress = blurLevel()
        tvBlur.text = "Wallpaper blur: ${blurLevel()}"
        sbBlur.onChange { p ->
            prefs.edit().putInt("blur", p).apply()
            tvBlur.text = "Wallpaper blur: $p"
            applyWallpaper()
        }

        // Text size
        val tvSize = v.findViewById<TextView>(R.id.tvSize)
        val tvPrev = v.findViewById<TextView>(R.id.tvPreview)
        val sbSize = v.findViewById<SeekBar>(R.id.sbSize)
        sbSize.progress = textSp() - 12
        tvSize.text = "Text size: ${textSp()} sp"
        tvPrev.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp().toFloat())
        sbSize.onChange { p ->
            val sp = 12 + p
            prefs.edit().putInt("text_sp", sp).apply()
            tvSize.text = "Text size: $sp sp"
            tvPrev.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
            refresh()
        }

        // Voice replies
        val sw = v.findViewById<SwitchCompat>(R.id.swSpeak)
        sw.isChecked = prefs.getBoolean("speak", false)
        sw.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("speak", on).apply()
            if (!on) tts?.stop()
        }

        dlg.show()
    }

    private fun themeChoice(): Int = prefs.getInt("theme", 0)
    private fun blurLevel(): Int = prefs.getInt("blur", 0)
    private fun textSp(): Int = prefs.getInt("text_sp", 16)

    private fun nightModeFor(choice: Int): Int = when (choice) {
        1 -> AppCompatDelegate.MODE_NIGHT_NO
        2 -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    // ---------------- Wallpaper ----------------

    private fun loadWallpaper() {
        if (!wallFile.exists()) {
            wallBitmap = null
            applyWallpaper()
            return
        }
        val f = wallFile
        Thread {
            val bmp = try { BitmapFactory.decodeFile(f.path) } catch (t: Throwable) { null }
            runOnUiThread {
                wallBitmap = bmp
                applyWallpaper()
            }
        }.start()
    }

    private fun applyWallpaper() {
        val bmp = wallBitmap
        if (bmp == null) {
            wallpaperView.visibility = View.GONE
            scrim.visibility = View.GONE
            wallpaperView.setImageDrawable(null)
            if (Build.VERSION.SDK_INT >= 31) wallpaperView.setRenderEffect(null)
            return
        }
        wallpaperView.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        val blur = blurLevel()
        if (Build.VERSION.SDK_INT >= 31) {
            wallpaperView.setImageBitmap(bmp)
            wallpaperView.setRenderEffect(
                if (blur > 0) RenderEffect.createBlurEffect(blur * 3f, blur * 3f, Shader.TileMode.MIRROR) else null
            )
        } else {
            // Older phones: shrink then stretch back for a soft blur
            val f = 1 + blur
            val b = if (blur > 0) Bitmap.createScaledBitmap(bmp, maxOf(1, bmp.width / f), maxOf(1, bmp.height / f), true) else bmp
            wallpaperView.setImageBitmap(b)
        }
    }

    private fun importWallpaper(uri: Uri) {
        val resolver = contentResolver
        val out = wallFile
        Thread {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
                while (maxSide / sample > 1600) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                var bmp = resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
                    ?: throw IllegalStateException("Could not read this image")
                val rot = try {
                    resolver.openInputStream(uri)?.use {
                        when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270
                            else -> 0
                        }
                    } ?: 0
                } catch (e: Exception) { 0 }
                if (rot != 0) {
                    val m = Matrix().apply { postRotate(rot.toFloat()) }
                    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                }
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                runOnUiThread {
                    wallBitmap = bmp
                    applyWallpaper()
                    toast("Wallpaper set")
                }
            } catch (t: Throwable) {
                runOnUiThread { toast("Wallpaper failed: ${t.message}") }
            }
        }.start()
    }

    private fun removeWallpaper() {
        wallFile.delete()
        wallBitmap = null
        applyWallpaper()
    }

    // ---------------- Model ----------------

    private fun importModel(uri: Uri) {
        ChatState.busy = true
        ChatState.status = "Importing model…"
        refresh()
        val dir = filesDir
        val resolver = contentResolver
        Thread {
            val tmp = File(dir, "model.cde.tmp")
            try {
                resolver.openInputStream(uri)!!.use { src ->
                    tmp.outputStream().use { dst -> src.copyTo(dst) }
                }
                val model = NekoModel.load(tmp)          // validates the file
                tmp.copyTo(File(dir, "model.cde"), overwrite = true)
                tmp.delete()
                ChatState.main.post {
                    ChatState.neko = Neko(model)
                    ChatState.msgs.clear()
                    ChatState.msgs.add(ChatState.Msg(false, "Model imported! Say hi to Neko."))
                    ChatState.busy = false
                    ChatState.status = "Model ready"
                    ChatState.ui?.refresh()
                    ChatState.ui?.toast("Model imported")
                }
            } catch (t: Throwable) {
                tmp.delete()
                ChatState.main.post {
                    ChatState.busy = false
                    ChatState.status = if (ChatState.neko != null) "Model ready" else "Import failed"
                    ChatState.ui?.refresh()
                    ChatState.ui?.toast("Import failed: ${t.message}")
                }
            }
        }.start()
    }

    private fun loadSavedModel() {
        ChatState.loading = true
        ChatState.busy = true
        ChatState.status = "Loading model…"
        val file = modelFile
        Thread {
            try {
                val model = NekoModel.load(file)
                ChatState.main.post {
                    ChatState.neko = Neko(model)
                    ChatState.loading = false
                    ChatState.busy = false
                    ChatState.status = "Model ready"
                    ChatState.ui?.refresh()
                }
            } catch (t: Throwable) {
                ChatState.main.post {
                    ChatState.loading = false
                    ChatState.busy = false
                    ChatState.status = "Saved model could not be loaded. Import it again."
                    ChatState.ui?.refresh()
                }
            }
        }.start()
    }

    // ---------------- Chat + voice ----------------

    private fun startVoice() {
        if (ChatState.neko == null) { toast("Import a model first"); return }
        if (ChatState.busy) { toast("Please wait…"); return }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Neko")
        }
        try {
            voiceLauncher.launch(i)
        } catch (e: ActivityNotFoundException) {
            toast("Voice input is not available on this device")
        }
    }

    private fun send(override: String? = null, speakReply: Boolean = false) {
        val n = ChatState.neko ?: return
        if (ChatState.busy) return
        val msg = (override ?: input.text.toString()).trim()
        if (msg.isEmpty()) return
        if (override == null) input.setText("")
        tts?.stop()
        val speak = speakReply || prefs.getBoolean("speak", false)
        ChatState.msgs.add(ChatState.Msg(true, msg))
        val pending = ChatState.Msg(false, "…")
        ChatState.msgs.add(pending)
        ChatState.busy = true
        refresh()
        Thread {
            val reply = try {
                n.respond(msg).ifBlank { "…" }
            } catch (t: Throwable) {
                "Error: ${t.message}"
            }
            ChatState.main.post {
                pending.text = reply
                ChatState.busy = false
                ChatState.ui?.refresh()
                if (speak) ChatState.ui?.speak(reply)
            }
        }.start()
    }

    fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "neko")
    }

    private fun clearChat() {
        if (ChatState.busy) {
            toast("Please wait…")
            return
        }
        tts?.stop()
        ChatState.msgs.clear()
        ChatState.neko?.reset()
        refresh()
    }

    // ---------------- UI ----------------

    fun refresh() {
        status.text = ChatState.status
        val ready = ChatState.neko != null
        val canSend = ready && !ChatState.busy
        btnSend.isEnabled = canSend
        btnSend.alpha = if (canSend) 1f else 0.4f
        btnMic.isEnabled = canSend
        btnMic.alpha = if (canSend) 1f else 0.4f
        input.isEnabled = ready
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp().toFloat())
        chatBox.removeAllViews()
        for (m in ChatState.msgs) addBubble(m.text, m.user)
        scroll.post { scroll.scrollTo(0, chatBox.height) }
    }

    private fun addBubble(text: String, user: Boolean) {
        val tv = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp().toFloat())
            setTextColor(getColor(if (user) R.color.on_user else R.color.on_bot))
            setBackgroundResource(if (user) R.drawable.bubble_user else R.drawable.bubble_bot)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = if (user) Gravity.END else Gravity.START
        lp.setMargins(dp(if (user) 48 else 0), dp(4), dp(if (user) 0 else 48), dp(4))
        chatBox.addView(tv, lp)
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
