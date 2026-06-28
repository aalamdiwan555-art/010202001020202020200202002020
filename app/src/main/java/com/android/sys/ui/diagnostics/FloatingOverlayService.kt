package com.android.sys.ui.diagnostics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * FloatingOverlayService - Compact floating control widget with touch pass-through.
 *
 * Renders a compact floating control widget using WindowManager LayoutParams
 * TYPE_APPLICATION_OVERLAY with exact window flags to ensure drivers can
 * interact with overlay inputs while leaving transparent space fully pass-through
 * to let inputs flow to the Rapido Captain map underneath.
 *
 * Key flags:
 * - FLAG_NOT_FOCUSABLE: Allows touches to pass through to underlying windows
 * - FLAG_NOT_TOUCH_MODAL: Allows touches outside the overlay to reach other windows
 * - FLAG_LAYOUT_IN_SCREEN: Positions window in screen coordinates
 */
class FloatingOverlayService : Service() {

    companion object {
        private const val TAG = "FloatingOverlayService"
        private const val NOTIFICATION_CHANNEL_ID = "diagnostics_service_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_UPDATE_STATUS = "ACTION_UPDATE_STATUS"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_IS_ACTIVE = "extra_is_active"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var isOverlayActive = false

    private var statusIndicator: View? = null
    private var tvOverlayStatus: TextView? = null
    private var floatingWidget: LinearLayout? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!isOverlayActive) {
                    startForeground(NOTIFICATION_ID, createNotification())
                    showOverlay()
                }
            }
            ACTION_STOP -> {
                if (isOverlayActive) {
                    hideOverlay()
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_UPDATE_STATUS -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: getString(R.string.overlay_active)
                val isActive = intent.getBooleanExtra(EXTRA_IS_ACTIVE, true)
                updateOverlayStatus(status, isActive)
            }
        }
        return START_STICKY
    }

    /**
     * Inflate and display the floating overlay widget.
     * Uses FLAG_NOT_FOCUSABLE and FLAG_NOT_TOUCH_MODAL for seamless touch pass-through.
     */
    private fun showOverlay() {
        if (overlayView != null) return

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.floating_overlay_layout, null)

        // Initialize overlay views
        floatingWidget = overlayView?.findViewById(R.id.floatingWidget)
        statusIndicator = overlayView?.findViewById(R.id.statusIndicator)
        tvOverlayStatus = overlayView?.findViewById(R.id.tvOverlayStatus)
        val btnClose = overlayView?.findViewById<ImageButton>(R.id.btnCloseOverlay)

        // Setup close button
        btnClose?.setOnClickListener {
            hideOverlay()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            // Also update MainActivity switch state
            val prefs = getSharedPreferences("DrClickerPrefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("engine_active", false).apply()
        }

        // Setup drag functionality for the widget
        setupDragFunctionality()

        // Configure WindowManager LayoutParams with touch pass-through flags
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // Critical flags for touch pass-through:
            // FLAG_NOT_FOCUSABLE - allows touches to pass to underlying windows
            // FLAG_NOT_TOUCH_MODAL - allows touches outside overlay to reach other apps
            // FLAG_LAYOUT_IN_SCREEN - positions in screen coordinates
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.END
        params.x = 16
        params.y = 100

        overlayParams = params

        try {
            windowManager?.addView(overlayView, params)
            isOverlayActive = true
            updateOverlayStatus(getString(R.string.overlay_active), true)
        } catch (e: Exception) {
            e.printStackTrace()
            overlayView = null
        }
    }

    /**
     * Setup touch listener for dragging the floating widget.
     * Only the widget itself responds to touches; transparent areas pass through.
     */
    private fun setupDragFunctionality() {
        floatingWidget?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = overlayParams?.x ?: 0
                    initialY = overlayParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    overlayParams?.x = initialX - dx
                    overlayParams?.y = initialY + dy
                    overlayParams?.let { windowManager?.updateViewLayout(overlayView, it) }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Update the overlay status indicator and text.
     */
    private fun updateOverlayStatus(status: String, isActive: Boolean) {
        tvOverlayStatus?.text = status
        val drawableRes = when {
            isActive -> R.drawable.status_indicator_active
            status.contains("Scanning") -> R.drawable.status_indicator_scanning
            else -> R.drawable.status_indicator_idle
        }
        statusIndicator?.background = ContextCompat.getDrawable(this, drawableRes)
    }

    /**
     * Remove the overlay from WindowManager.
     */
    private fun hideOverlay() {
        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            overlayView = null
            isOverlayActive = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
    }

    /**
     * Create the notification channel for Android O+.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    /**
     * Build the foreground service notification.
     */
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
