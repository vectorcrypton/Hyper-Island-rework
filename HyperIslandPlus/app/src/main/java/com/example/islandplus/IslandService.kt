package com.example.islandplus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager

/** Zeichnet die eigene Insel als Overlay über alles und füttert sie mit den Medien-Infos. */
class IslandService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var msm: MediaSessionManager
    private lateinit var island: IslandView
    private var attached = false
    private var controller: MediaController? = null
    private var lastKey: String? = null
    private val ui = Handler(Looper.getMainLooper())
    private val component by lazy { ComponentName(this, MediaNotificationListener::class.java) }
    private val idleHide = Runnable { hide() }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = render(true)
        override fun onPlaybackStateChanged(state: PlaybackState?) = render(false)
        override fun onSessionDestroyed() { pick(msm.getActiveSessions(component)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("island", "Island+", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "island")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Island+ aktiv")
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        msm = getSystemService(MediaSessionManager::class.java)
        island = IslandView(this).apply {
            onPrev = { controller?.transportControls?.skipToPrevious() }
            onNext = { controller?.transportControls?.skipToNext() }
            onPlayPause = {
                val c = controller
                if (c?.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
                else c?.transportControls?.play()
            }
            positionProvider = { currentPosition() }
        }
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component)
            pick(msm.getActiveSessions(component))
        } catch (e: SecurityException) {
            stopSelf() // Benachrichtigungszugriff fehlt
        }
    }

    private fun pick(list: List<MediaController>?) {
        val best = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list?.firstOrNull()
        if (best?.sessionToken == controller?.sessionToken && best != null) {
            render(false); return
        }
        controller?.unregisterCallback(callback)
        controller = best
        best?.registerCallback(callback, ui)
        lastKey = null
        render(true)
    }

    private fun render(allowExpand: Boolean) {
        val c = controller
        val md = c?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (c == null || md == null || title == null) { hide(); return }

        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val cover = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING

        show()
        island.bind(title, artist, cover, playing)

        val key = "$title|$artist"
        if (allowExpand && key != lastKey) island.expandTemporarily()
        lastKey = key

        ui.removeCallbacks(idleHide)
        if (!playing) ui.postDelayed(idleHide, 45_000)
    }

    private fun currentPosition(): Pair<Long, Long> {
        val c = controller ?: return 0L to 0L
        val st = c.playbackState ?: return 0L to 0L
        val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        return pos to dur
    }

    private fun show() {
        if (attached) return
        val d = resources.displayMetrics.density
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (10 * d).toInt()   // Abstand vom oberen Rand – bei Bedarf anpassen
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try {
            wm.addView(island, lp)
            attached = true
        } catch (e: Exception) {
            stopSelf() // Overlay-Berechtigung fehlt
        }
    }

    private fun hide() {
        ui.removeCallbacks(idleHide)
        lastKey = null
        island.reset()
        if (attached) {
            try { wm.removeView(island) } catch (_: Exception) {}
            attached = false
        }
    }

    override fun onDestroy() {
        try { msm.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        controller?.unregisterCallback(callback)
        hide()
        super.onDestroy()
    }
}
