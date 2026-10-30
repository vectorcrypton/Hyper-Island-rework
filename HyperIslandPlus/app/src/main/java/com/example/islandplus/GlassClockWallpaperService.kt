package com.example.islandplus

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File
import java.util.Calendar
import java.util.Locale

/**
 * Live-Hintergrund: Foto + "Glas"-Uhr + freigestelltes Motiv DAVOR (= Tiefeneffekt).
 * Die Uhr hat Milchglas-Füllung (unscharfer Hintergrund + Tönung), Glanzkante und Schatten.
 */
class GlassClockWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = GlassEngine()

    inner class GlassEngine : Engine() {
        private val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        private var bg: Bitmap? = null
        private var fg: Bitmap? = null
        private var soft: Bitmap? = null
        private var stamp = -1L
        private var w = 0
        private var h = 0
        private val mBg = Matrix()
        private val mFg = Matrix()
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val liftPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                1.15f, 0f, 0f, 0f, 28f,
                0f, 1.15f, 0f, 0f, 28f,
                0f, 0f, 1.15f, 0f, 32f,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        private var registered = false
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { draw() }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            w = width; h = height
            updateMatrices()
            draw()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) { draw() }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                reloadIfNeeded()
                register()
                draw()
            } else unregister()
        }

        override fun onDestroy() {
            unregister()
            super.onDestroy()
        }

        private fun register() {
            if (registered) return
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
            registered = true
        }

        private fun unregister() {
            if (!registered) return
            try { unregisterReceiver(receiver) } catch (_: Exception) {}
            registered = false
        }

        // ---------- Bilder laden ----------
        private fun reloadIfNeeded() {
            val fb = File(filesDir, "bg.jpg")
            val ff = File(filesDir, "fg.png")
            val s = fb.lastModified() * 31 + ff.lastModified()
            if (s == stamp) return
            stamp = s
            bg = if (fb.exists()) BitmapFactory.decodeFile(fb.path) else null
            fg = if (ff.exists()) BitmapFactory.decodeFile(ff.path) else null
            soft = bg?.let { makeSoft(it) }
            updateMatrices()
        }

        private fun fit(bm: Bitmap, m: Matrix) {
            val s = maxOf(w / bm.width.toFloat(), h / bm.height.toFloat())
            m.reset()
            m.setScale(s, s)
            m.postTranslate((w - bm.width * s) / 2f, (h - bm.height * s) / 2f)
        }

        private fun updateMatrices() {
            if (w == 0) return
            bg?.let { fit(it, mBg) }
            fg?.let { fit(it, mFg) }
        }

        // ---------- Unschärfe (einfacher Box-Blur auf verkleinertem Bild) ----------
        private fun makeSoft(src: Bitmap): Bitmap {
            val sw = 160
            val sh = (src.height * sw / src.width.toFloat()).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(src, sw, sh, true)
            val px = IntArray(sw * sh)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            val tmp = IntArray(px.size)
            repeat(3) {
                blurPass(px, tmp, sw, sh, 5, true)
                blurPass(tmp, px, sw, sh, 5, false)
            }
            val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            out.setPixels(px, 0, sw, 0, 0, sw, sh)
            return Bitmap.createScaledBitmap(out, src.width, src.height, true)
        }

        private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val len = if (horizontal) w else h
            val lines = if (horizontal) h else w
            val stride = if (horizontal) 1 else w
            val lineStep = if (horizontal) w else 1
            val div = 2 * r + 1
            for (l in 0 until lines) {
                val base = l * lineStep
                var rr = 0; var gg = 0; var bb = 0
                for (i in -r..r) {
                    val p = src[base + i.coerceIn(0, len - 1) * stride]
                    rr += (p shr 16) and 255; gg += (p shr 8) and 255; bb += p and 255
                }
                for (i in 0 until len) {
                    dst[base + i * stride] = (0xFF shl 24) or ((rr / div) shl 16) or ((gg / div) shl 8) or (bb / div)
                    val add = src[base + (i + r + 1).coerceAtMost(len - 1) * stride]
                    val sub = src[base + (i - r).coerceAtLeast(0) * stride]
                    rr += ((add shr 16) and 255) - ((sub shr 16) and 255)
                    gg += ((add shr 8) and 255) - ((sub shr 8) and 255)
                    bb += (add and 255) - (sub and 255)
                }
            }
        }

        // ---------- Zeichnen ----------
        private fun showClock(): Boolean =
            isPreview || (wallpaperFlags and WallpaperManager.FLAG_LOCK) != 0

        private fun draw() {
            if (w == 0) return
            val holder = surfaceHolder
            val c = holder.lockCanvas() ?: return
            try {
                c.drawColor(Color.rgb(24, 32, 40))
                bg?.let { c.drawBitmap(it, mBg, bmpPaint) }
                if (showClock()) drawClock(c)
                fg?.let { c.drawBitmap(it, mFg, bmpPaint) }   // Motiv VOR der Uhr = Tiefe
            } finally {
                holder.unlockCanvasAndPost(c)
            }
        }

        private fun fitPaint(face: Typeface, ref: String, targetW: Float, digitH: Float): Paint {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.typeface = face
            p.textSize = 200f
            val r = Rect()
            p.getTextBounds("0123456789", 0, 10, r)
            p.textSize = 200f * digitH / r.height()
            p.textScaleX = 1f
            p.textScaleX = targetW / p.measureText(ref)
            return p
        }

        private fun drawClock(c: Canvas) {
            val cal = Calendar.getInstance()
            val hh = "%02d".format(Locale.ROOT, cal.get(Calendar.HOUR_OF_DAY))
            val mm = "%02d".format(Locale.ROOT, cal.get(Calendar.MINUTE))
            val face = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            val white = Color.rgb(236, 247, 255)
            val blue = Color.rgb(130, 205, 255)

            if (!prefs.getBoolean("stacked", false)) {
                // hoch & schmal, HHMM in einer Zeile (wie auf deinem Sperrbildschirm)
                val dh = h * 0.34f
                val p = fitPaint(face, "0000", w * 0.9f, dh)
                val x0 = (w - p.measureText(hh + mm)) / 2f
                val base = h * 0.08f + dh
                val hp = Path(); p.getTextPath(hh, 0, 2, x0, base, hp)
                val mp = Path(); p.getTextPath(mm, 0, 2, x0 + p.measureText(hh), base, mp)
                glass(c, hp, white, base - dh, base)
                glass(c, mp, blue, base - dh, base)
            } else {
                // gestapelt: HH über MM
                val dh = h * 0.19f
                val p = fitPaint(face, "00", w * 0.62f, dh)
                val x0 = w * 0.1f
                val b1 = h * 0.08f + dh
                val b2 = b1 + dh + h * 0.03f
                val hp = Path(); p.getTextPath(hh, 0, 2, x0, b1, hp)
                val mp = Path(); p.getTextPath(mm, 0, 2, x0, b2, mp)
                glass(c, hp, white, b1 - dh, b1)
                glass(c, mp, blue, b2 - dh, b2)
            }
        }

        private fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)

        /** Milchglas: Schatten außen, unscharfer + aufgehellter Hintergrund innen, Tönung, Glanz, Kante. */
        private fun glass(c: Canvas, path: Path, tint: Int, top: Float, bottom: Float) {
            val wf = w.toFloat()

            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                setShadowLayer(34f, 0f, 14f, Color.argb(110, 0, 25, 50))
            }
            c.save(); c.clipOutPath(path); c.drawPath(path, shadow); c.restore()

            c.save()
            c.clipPath(path)
            soft?.let { c.drawBitmap(it, mBg, liftPaint) }
            val body = Paint().apply {
                shader = LinearGradient(0f, top, 0f, bottom, withAlpha(tint, 150), withAlpha(tint, 50), Shader.TileMode.CLAMP)
            }
            c.drawPaint(body)
            val sheen = Paint().apply {
                shader = LinearGradient(0f, top, wf, bottom, Color.argb(95, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            }
            c.drawPaint(sheen)
            c.restore()

            val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f
                shader = LinearGradient(0f, top, wf * 0.8f, bottom, Color.argb(230, 255, 255, 255), Color.argb(40, 255, 255, 255), Shader.TileMode.CLAMP)
            }
            c.drawPath(path, rim)
        }
    }
}
