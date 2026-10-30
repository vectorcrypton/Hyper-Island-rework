package com.example.islandplus

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.palette.graphics.Palette
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Die eigene "Insel": kompakte Pille oben, die bei Songwechsel / Tippen zur großen
 * Karte aufspringt. Hintergrund = unscharfes Albumcover + aus dem Cover berechnete Farbe.
 */
class IslandView(ctx: Context) : FrameLayout(ctx) {

    var onPrev: () -> Unit = {}
    var onPlayPause: () -> Unit = {}
    var onNext: () -> Unit = {}
    /** liefert (Position ms, Dauer ms) */
    var positionProvider: () -> Pair<Long, Long> = { 0L to 0L }

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    private val collapsedW = dp(148f)
    private val collapsedH = dp(36f)
    private val expandedW = ctx.resources.displayMetrics.widthPixels - dp(24f)
    private val expandedH = dp(184f)
    private val trackW = expandedW - dp(32f)
    private val textLeft = dp(16f + 124f + 16f)

    private var progress = 0f
    private var expanded = false
    private var playing = false
    private var radius = collapsedH / 2f
    private var tint = Color.BLACK
    private var scrimColor = Color.BLACK
    private var accent = Color.WHITE
    private var lastCover: Bitmap? = null
    private var coverAlpha = 0f
    private var anim: ValueAnimator? = null
    private var tintAnim: ValueAnimator? = null
    private val ui = Handler(Looper.getMainLooper())
    private val collapseRunnable = Runnable { setExpanded(false) }
    private val ticker = object : Runnable {
        override fun run() {
            updateBar()
            if (expanded && playing) ui.postDelayed(this, 250)
        }
    }

    // ---------- Views ----------
    private val bgArt = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        scaleX = 1.35f; scaleY = 1.35f
        alpha = 0f
        setRenderEffect(RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP))
    }
    private val scrimDrawable = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0))
    private val scrim = View(ctx).apply { background = scrimDrawable; alpha = 0f }

    private val compactArt = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        outlineProvider = ovalOutline()
        setBackgroundColor(Color.argb(60, 255, 255, 255))
    }
    private val eq = EqView(ctx)
    private val compactLayer = FrameLayout(ctx)

    private val full = FrameLayout(ctx)
    private val art = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        outlineProvider = roundOutline(dp(24f).toFloat())
        setBackgroundColor(Color.argb(40, 255, 255, 255))
    }
    private val titleTv = TextView(ctx).apply {
        setTextColor(Color.WHITE); textSize = 19f
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private val artistTv = TextView(ctx).apply {
        setTextColor(Color.argb(185, 255, 255, 255)); textSize = 14f
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private val prevBtn = ctl(android.R.drawable.ic_media_previous) { onPrev() }
    private val playBtn = ctl(android.R.drawable.ic_media_pause) { onPlayPause() }
    private val nextBtn = ctl(android.R.drawable.ic_media_next) { onNext() }

    private val track = View(ctx).apply {
        background = GradientDrawable().apply { cornerRadius = dp(3f).toFloat(); setColor(Color.argb(70, 255, 255, 255)) }
    }
    private val fill = View(ctx).apply {
        background = GradientDrawable().apply { cornerRadius = dp(3f).toFloat(); setColor(Color.WHITE) }
    }

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        setBackgroundColor(Color.BLACK)

        val topCenter = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        val topStart = Gravity.TOP or Gravity.START

        // Inhalt der großen Karte (feste Größe, wird beim Aufklappen nur "enthüllt")
        full.addView(art, FrameLayout.LayoutParams(dp(124f), dp(124f), topStart).apply {
            leftMargin = dp(16f); topMargin = dp(16f)
        })
        val tw = expandedW - textLeft - dp(16f)
        full.addView(titleTv, FrameLayout.LayoutParams(tw, WRAP, topStart).apply {
            leftMargin = textLeft; topMargin = dp(22f)
        })
        full.addView(artistTv, FrameLayout.LayoutParams(tw, WRAP, topStart).apply {
            leftMargin = textLeft; topMargin = dp(52f)
        })
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(prevBtn, LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(playBtn, LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(nextBtn, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        full.addView(controls, FrameLayout.LayoutParams(tw, dp(56f), topStart).apply {
            leftMargin = textLeft; topMargin = dp(80f)
        })
        val barBox = FrameLayout(context).apply {
            addView(track, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(fill, FrameLayout.LayoutParams(0, MATCH))
        }
        full.addView(barBox, FrameLayout.LayoutParams(trackW, dp(5f), topStart).apply {
            leftMargin = dp(16f); topMargin = dp(160f)
        })

        // Kompakte Pille
        compactLayer.addView(compactArt, FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER_VERTICAL or Gravity.START).apply {
            marginStart = dp(10f)
        })
        compactLayer.addView(eq, FrameLayout.LayoutParams(dp(22f), dp(16f), Gravity.CENTER_VERTICAL or Gravity.END).apply {
            marginEnd = dp(14f)
        })

        addView(bgArt, FrameLayout.LayoutParams(expandedW, expandedH, topCenter))
        addView(scrim, FrameLayout.LayoutParams(expandedW, expandedH, topCenter))
        addView(full, FrameLayout.LayoutParams(expandedW, expandedH, topCenter))
        addView(compactLayer, FrameLayout.LayoutParams(MATCH, MATCH))

        setOnClickListener { toggle() }
        applyProgress()
    }

    // ---------- Öffentliche API ----------
    fun bind(title: String?, artist: String?, cover: Bitmap?, isPlaying: Boolean) {
        titleTv.text = title ?: "Unbekannt"
        artistTv.text = artist ?: ""
        playing = isPlaying
        playBtn.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        eq.setActive(isPlaying)
        if (cover !== lastCover) {
            lastCover = cover
            applyCover(cover)
        }
        if (expanded) { ui.removeCallbacks(ticker); ui.post(ticker) }
    }

    fun expandTemporarily(ms: Long = 5000) {
        setExpanded(true)
        ui.removeCallbacks(collapseRunnable)
        ui.postDelayed(collapseRunnable, ms)
    }

    fun reset() {
        ui.removeCallbacks(collapseRunnable)
        ui.removeCallbacks(ticker)
        anim?.cancel()
        expanded = false
        progress = 0f
        applyProgress()
    }

    // ---------- Intern ----------
    private fun ctl(res: Int, click: () -> Unit) = ImageButton(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        background = null
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        setOnClickListener { click(); extend() }
    }

    private fun extend() = expandTemporarily(8000)

    private fun toggle() {
        if (expanded) {
            ui.removeCallbacks(collapseRunnable)
            setExpanded(false)
        } else expandTemporarily(8000)
    }

    private fun setExpanded(e: Boolean) {
        if (expanded == e) return
        expanded = e
        anim?.cancel()
        anim = ValueAnimator.ofFloat(progress, if (e) 1f else 0f).apply {
            duration = if (e) 520 else 380
            interpolator = if (e) OvershootInterpolator(0.9f) else DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; applyProgress() }
            start()
        }
        ui.removeCallbacks(ticker)
        if (e) ui.post(ticker)
    }

    private fun applyProgress() {
        val p = progress
        full.alpha = ((p - 0.45f) / 0.5f).coerceIn(0f, 1f)
        full.visibility = if (p < 0.05f) View.INVISIBLE else View.VISIBLE
        compactLayer.alpha = (1f - p * 2.5f).coerceIn(0f, 1f)
        bgArt.alpha = coverAlpha * p.coerceIn(0f, 1f)
        scrim.alpha = p.coerceIn(0f, 1f)
        val rEnd = dp(38f).toFloat()
        radius = collapsedH / 2f + (rEnd - collapsedH / 2f) * p.coerceIn(0f, 1.1f)
        requestLayout()
        invalidateOutline()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (collapsedW + (expandedW - collapsedW) * progress).toInt()
            .coerceIn(1, resources.displayMetrics.widthPixels)
        val h = (collapsedH + (expandedH - collapsedH) * progress).toInt().coerceAtLeast(1)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }

    private fun updateBar() {
        val (pos, dur) = positionProvider()
        val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        val w = (trackW * frac).toInt()
        val lp = fill.layoutParams
        if (lp.width != w) { lp.width = w; fill.layoutParams = lp }
    }

    private fun applyCover(b: Bitmap?) {
        art.setImageBitmap(b)
        compactArt.setImageBitmap(b)
        bgArt.setImageBitmap(b)
        coverAlpha = if (b != null) 0.9f else 0f
        applyProgress()
        if (b == null) {
            animateTint(Color.rgb(20, 20, 22), Color.BLACK, Color.WHITE)
            return
        }
        Palette.from(b).generate { p ->
            val sw = p?.vibrantSwatch ?: p?.dominantSwatch ?: p?.mutedSwatch
            val base = sw?.rgb ?: Color.rgb(40, 40, 44)
            animateTint(adjust(base, 0.9f, 0.40f), adjust(base, 1f, 0.22f), accentOf(base))
        }
    }

    private fun animateTint(toTint: Int, toScrim: Int, toAccent: Int) {
        tintAnim?.cancel()
        val fromT = tint; val fromS = scrimColor; val fromA = accent
        val ev = ArgbEvaluator()
        tintAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            addUpdateListener {
                val f = it.animatedFraction
                tint = ev.evaluate(f, fromT, toTint) as Int
                scrimColor = ev.evaluate(f, fromS, toScrim) as Int
                accent = ev.evaluate(f, fromA, toAccent) as Int
                applyColors()
            }
            start()
        }
    }

    private fun applyColors() {
        setBackgroundColor(tint)
        val r = Color.red(scrimColor); val g = Color.green(scrimColor); val b = Color.blue(scrimColor)
        scrimDrawable.colors = intArrayOf(Color.argb(110, r, g, b), Color.argb(235, r, g, b))
        eq.color = accent
    }

    private fun adjust(c: Int, s: Float, v: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[1] = (hsv[1] * s).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * v).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    private fun accentOf(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[1] = hsv[1].coerceIn(0.35f, 0.9f)
        hsv[2] = max(hsv[2], 0.85f)
        return Color.HSVToColor(hsv)
    }

    private fun roundOutline(r: Float) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, r)
        }
    }

    private fun ovalOutline() = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacks(collapseRunnable)
        ui.removeCallbacks(ticker)
        anim?.cancel()
        tintAnim?.cancel()
        super.onDetachedFromWindow()
    }
}

/** Kleiner animierter Equalizer für die kompakte Pille. */
class EqView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    var color: Int = Color.WHITE
        set(v) { field = v; paint.color = v; invalidate() }
    private var phase = 0f
    private var active = false
    private val animator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    fun setActive(a: Boolean) {
        if (a == active) return
        active = a
        if (a) { if (isAttachedToWindow) animator.start() } else { animator.cancel(); invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val n = 4
        val gap = width * 0.12f
        val bw = (width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val f = if (active) 0.35f + 0.65f * (0.5f + 0.5f * sin(phase + i * 1.4f)) else 0.25f
            val bh = height * f
            val x = i * (bw + gap)
            canvas.drawRoundRect(x, (height - bh) / 2f, x + bw, (height + bh) / 2f, bw / 2f, bw / 2f, paint)
        }
    }
}
