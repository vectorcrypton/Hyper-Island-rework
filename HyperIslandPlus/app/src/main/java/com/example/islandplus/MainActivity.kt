package com.example.islandplus

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import kotlin.math.max

class MainActivity : ComponentActivity() {

    private lateinit var permStatus: TextView
    private lateinit var wallStatus: TextView

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) processImage(uri)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(t: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size
        setPadding(0, dp(6), 0, dp(6))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun btn(t: String, action: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false
        setOnClickListener { action() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(56), dp(20), dp(32))
        }
        permStatus = tv("")
        wallStatus = tv("")

        root.addView(tv("Island+", 32f, true))
        root.addView(tv("Dynamische Musik-Insel und Glas-Uhr – ohne Root."))

        root.addView(tv("1 · Berechtigungen", 20f, true))
        root.addView(permStatus)
        root.addView(btn("Benachrichtigungszugriff erlauben") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(btn("Über anderen Apps einblenden erlauben") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(btn("App-Info öffnen (⋮ → Eingeschränkte Einstellungen zulassen)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        root.addView(tv("2 · Insel", 20f, true))
        root.addView(btn("Insel starten") { startIsland() })
        root.addView(btn("Insel stoppen") { stopService(Intent(this, IslandService::class.java)) })

        root.addView(tv("3 · Glas-Uhr auf dem Sperrbildschirm", 20f, true))
        root.addView(btn("Bild wählen (Motiv wird automatisch freigestellt)") { picker.launch("image/*") })
        root.addView(CheckBox(this).apply {
            text = "Uhr gestapelt (Stunden über Minuten)"
            isChecked = prefs.getBoolean("stacked", false)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("stacked", checked).apply() }
        })
        root.addView(btn("Live-Hintergrund aktivieren") { openWallpaper() })
        root.addView(wallStatus)
        root.addView(tv("Im Dialog „nur Sperrbildschirm“ wählen, damit der Homescreen unverändert bleibt.", 13f))

        setContentView(ScrollView(this).apply { addView(root) })

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        val n = if (listenerEnabled()) "✓" else "✗"
        val o = if (Settings.canDrawOverlays(this)) "✓" else "✗"
        permStatus.text = "Benachrichtigungszugriff: $n\nÜber anderen Apps einblenden: $o"
    }

    private fun listenerEnabled() =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun startIsland() {
        if (!Settings.canDrawOverlays(this) || !listenerEnabled()) {
            toast("Bitte zuerst beide Berechtigungen erteilen (Schritt 1).")
            return
        }
        startForegroundService(Intent(this, IslandService::class.java))
        toast("Insel läuft – spiel einen Song ab.")
    }

    private fun openWallpaper() {
        try {
            startActivity(
                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this, GlassClockWallpaperService::class.java)
                )
            )
        } catch (e: Exception) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun status(msg: String) { wallStatus.text = msg }

    private fun processImage(uri: Uri) {
        status("Bild wird verarbeitet …")
        Thread {
            try {
                val dm = resources.displayMetrics
                val src = ImageDecoder.createSource(contentResolver, uri)
                val bmp = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val s = max(
                        dm.widthPixels.toFloat() / info.size.width,
                        dm.heightPixels.toFloat() / info.size.height
                    ).coerceAtMost(1f)
                    dec.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
                }
                File(filesDir, "fg.png").delete()
                File(filesDir, "bg.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                runOnUiThread { status("Hintergrund gespeichert. Motiv wird freigestellt …") }

                val seg = SubjectSegmentation.getClient(
                    SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
                )
                seg.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { r ->
                        val fgBmp = r.foregroundBitmap
                        Thread {
                            if (fgBmp != null) {
                                File(filesDir, "fg.png").outputStream().use { fgBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            }
                            runOnUiThread {
                                status(if (fgBmp != null) "Fertig ✓ Tiefen-Maske erstellt." else "Kein Motiv erkannt – Uhr ohne Tiefe.")
                            }
                        }.start()
                    }
                    .addOnFailureListener { e ->
                        status("Freistellen fehlgeschlagen: ${e.message}\n(Beim ersten Mal Internet nötig – Modell kommt über Google Play Services.)")
                    }
            } catch (e: Exception) {
                runOnUiThread { status("Fehler: ${e.message}") }
            }
        }.start()
    }
}
