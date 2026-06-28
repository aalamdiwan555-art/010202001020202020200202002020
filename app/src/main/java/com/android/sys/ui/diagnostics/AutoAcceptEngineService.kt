package com.android.sys.ui.diagnostics

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.graphics.createBitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * AutoAcceptEngineService - Complete background accessibility service.
 *
 * Implements:
 * - Dual-mode verification pipeline (Text-based screen reading + OpenCV template matching)
 * - Hardware Bitmap to Software Bitmap conversion fix for Android 11+
 * - Safe elliptical touch zone targeting for capsule buttons
 * - Valid accessibility gesture stroke path with 1-pixel line shift
 * - InputDispatcher stuck pointer workaround (Android 15 multi-touch reset)
 * - Companion lifecycle service alignment (static instance tracking)
 * - Low-latency click dispatch with randomized reflex delay
 * - ToneGenerator double-beep feedback
 *
 * Monitors exclusively: com.rapido.rider
 */
class AutoAcceptEngineService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoAcceptEngine"
        private const val PREFS_NAME = "DrClickerPrefs"
        private const val KEY_MIN_PRICE = "min_price"
        private const val KEY_MAX_PRICE = "max_price"
        private const val KEY_MIN_DISTANCE = "min_distance"
        private const val KEY_MAX_DISTANCE = "max_distance"
        private const val KEY_TEMPLATE_PATH = "template_path"

        private const val DEFAULT_MIN_PRICE = 0
        private const val DEFAULT_MAX_PRICE = 99999
        private const val DEFAULT_MIN_DISTANCE = 0.0f
        private const val DEFAULT_MAX_DISTANCE = 999.0f

        private const val TARGET_PACKAGE = "com.rapido.rider"
        private const val MATCH_THRESHOLD = 0.85
        private const val IDLE_RESET_THRESHOLD = 3
        private const val SCREENSHOT_INTERVAL_MS = 500L
        private const val MIN_REFLEX_DELAY_MS = 10L
        private const val MAX_REFLEX_DELAY_MS = 100L
        private const val TONE_DURATION_MS = 80
        private const val TONE_VOLUME = 100

        // The critical static instance - managed by OS lifecycle
        @Volatile
        var instance: AutoAcceptEngineService? = null
            private set
    }

    private lateinit var prefs: SharedPreferences
    private val screenshotExecutor: Executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME)

    // OpenCV template matching state
    private var templateMat: Mat? = null
    private var templateLoaded = false
    private var safeWidthMin = 0
    private var safeWidthMax = 0
    private var safeHeightMin = 0
    private var safeHeightMax = 0

    // Engine state
    @Volatile
    private var engineActive = false
    @Volatile
    private var isProcessingScreenshot = false
    @Volatile
    private var consecutiveMatches = 0
    @Volatile
    private var consecutiveIdleCycles = 0
    @Volatile
    private var lastScreenshotTime = 0L

    // Parsed criteria from SharedPreferences
    private var minPrice = DEFAULT_MIN_PRICE
    private var maxPrice = DEFAULT_MAX_PRICE
    private var minDistance = DEFAULT_MIN_DISTANCE
    private var maxDistance = DEFAULT_MAX_DISTANCE

    // Text parsing results from accessibility tree
    private var currentFare: Double? = null
    private var currentPickupDistance: Double? = null
    private var currentDropDistance: Double? = null

    // Gesture tracking for InputDispatcher workaround
    private var lastGestureFailed = false
    private var gestureFailureCount = 0

    /**
     * Called when the accessibility service is connected by the OS.
     * CRITICAL: This is the ONLY valid way to get a service context.
     * Never instantiate this service manually.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Service connected - instance assigned")

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadSettings()
        loadTemplateFromPrefs()
        configureServiceInfo()

        engineActive = true
        Log.i(TAG, "Engine activated. Monitoring package: $TARGET_PACKAGE")
    }

    /**
     * Configure accessibility service info with required capabilities.
     */
    private fun configureServiceInfo() {
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            notificationTimeout = 100
            packageNames = arrayOf(TARGET_PACKAGE)
        }
        serviceInfo = info
    }

    /**
     * Load diagnostic criteria from SharedPreferences with safe fallbacks.
     */
    private fun loadSettings() {
        minPrice = prefs.getInt(KEY_MIN_PRICE, DEFAULT_MIN_PRICE)
        maxPrice = prefs.getInt(KEY_MAX_PRICE, DEFAULT_MAX_PRICE)
        minDistance = prefs.getFloat(KEY_MIN_DISTANCE, DEFAULT_MIN_DISTANCE)
        maxDistance = prefs.getFloat(KEY_MAX_DISTANCE, DEFAULT_MAX_DISTANCE)
        Log.d(TAG, "Settings loaded: Price ₹$minPrice-₹$maxPrice, Distance ${minDistance}km-${maxDistance}km")
    }

    /**
     * Load the template image from SharedPreferences path and prepare OpenCV Mat.
     * Converts to grayscale and calculates safe elliptical touch zones.
     */
    private fun loadTemplateFromPrefs() {
        val templatePath = prefs.getString(KEY_TEMPLATE_PATH, null)
        if (templatePath == null) {
            Log.w(TAG, "No template path found in preferences")
            templateLoaded = false
            return
        }

        try {
            val uri = android.net.Uri.parse(templatePath)
            contentResolver.openInputStream(uri)?.use { stream ->
                val bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                if (bitmap != null) {
                    // Convert to OpenCV Mat
                    val mat = Mat()
                    Utils.bitmapToMat(bitmap, mat)

                    // Convert to grayscale
                    val grayMat = Mat()
                    Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_BGRA2GRAY)

                    templateMat = grayMat
                    templateLoaded = true

                    // Calculate safe elliptical touch zones (25% to 75% of dimensions)
                    safeWidthMin = (grayMat.cols() * 0.25).toInt()
                    safeWidthMax = (grayMat.cols() * 0.75).toInt()
                    safeHeightMin = (grayMat.rows() * 0.25).toInt()
                    safeHeightMax = (grayMat.rows() * 0.75).toInt()

                    Log.i(TAG, "Template loaded: ${grayMat.cols()}x${grayMat.rows()}, " +
                            "Safe zone: X[$safeWidthMin-$safeWidthMax], Y[$safeHeightMin-$safeHeightMax]")

                    mat.release()
                    bitmap.recycle()
                } else {
                    Log.e(TAG, "Failed to decode template bitmap")
                    templateLoaded = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading template: ${e.message}")
            templateLoaded = false
        }
    }

    /**
     * Main accessibility event handler.
     * Deep-traverses the active window node hierarchy for text parsing (MODE A).
     * Triggers screenshot-based template matching (MODE B) on window updates.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!engineActive) return
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName != TARGET_PACKAGE) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // MODE A: Text-based screen reading
                val rootNode = rootInActiveWindow
                rootNode?.let { node ->
                    parseTextFromNodeHierarchy(node)
                    node.recycle()
                }

                // MODE B: OpenCV template matching (throttled)
                triggerScreenshotIfReady()
            }
            else -> {
                // Other events may also indicate UI changes
                val rootNode = rootInActiveWindow
                rootNode?.let { node ->
                    parseTextFromNodeHierarchy(node)
                    node.recycle()
                }
            }
        }
    }

    /**
     * Deep-traverse the accessibility node hierarchy recursively.
     * Parse raw textual string sequences using regex to locate:
     * - Pricing symbols ("₹", "Rs.") to verify Fare
     * - Spatial tags ("km", "km away", "Pickup") to determine distances
     */
    private fun parseTextFromNodeHierarchy(node: AccessibilityNodeInfo) {
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) {
            parseTextForDiagnostics(text)
        }

        // Recursively traverse child nodes
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                parseTextFromNodeHierarchy(child)
                child.recycle()
            }
        }
    }

    /**
     * Parse individual text strings for fare and distance information.
     * Uses regex patterns to extract numeric values associated with pricing and distance.
     */
    private fun parseTextForDiagnostics(text: String) {
        val lowerText = text.lowercase()

        // Parse fare: Look for ₹ or Rs. followed by numbers
        val fareRegex = "[₹Rs\.]+\s*([0-9,]+(?:\.[0-9]+)?)".toRegex(RegexOption.IGNORE_CASE)
        val fareMatch = fareRegex.find(text)
        if (fareMatch != null) {
            try {
                val fareStr = fareMatch.groupValues[1].replace(",", "")
                currentFare = fareStr.toDouble()
                Log.d(TAG, "Parsed fare: ₹$currentFare")
            } catch (e: NumberFormatException) {
                // Safe fallback - ignore unparseable values
            }
        }

        // Parse pickup distance: Look for "km" or "km away" near pickup context
        val distanceRegex = "([0-9]+(?:\.[0-9]+)?)\s*km".toRegex(RegexOption.IGNORE_CASE)
        val distanceMatches = distanceRegex.findAll(text).toList()

        if (distanceMatches.isNotEmpty()) {
            try {
                val distValue = distanceMatches[0].groupValues[1].toDouble()

                if (lowerText.contains("pickup") || lowerText.contains("away") || lowerText.contains("distance")) {
                    currentPickupDistance = distValue
                    Log.d(TAG, "Parsed pickup distance: ${distValue}km")
                } else if (lowerText.contains("drop") || lowerText.contains("destination")) {
                    currentDropDistance = distValue
                    Log.d(TAG, "Parsed drop distance: ${distValue}km")
                }
            } catch (e: NumberFormatException) {
                // Safe fallback
            }
        }
    }

    /**
     * Check if current parsed values meet user criteria.
     */
    private fun criteriaMet(): Boolean {
        val fareOk = currentFare?.let { it in minPrice.toDouble()..maxPrice.toDouble() } ?: true
        val pickupOk = currentPickupDistance?.let { it in minDistance.toDouble()..maxDistance.toDouble() } ?: true
        val dropOk = currentDropDistance?.let { it in minDistance.toDouble()..maxDistance.toDouble() } ?: true

        return fareOk && pickupOk && dropOk
    }

    /**
     * Trigger screenshot capture if enough time has passed since last capture.
     * Throttled to prevent excessive screenshot requests.
     */
    private fun triggerScreenshotIfReady() {
        val currentTime = SystemClock.uptimeMillis()
        if (currentTime - lastScreenshotTime < SCREENSHOT_INTERVAL_MS) return
        if (isProcessingScreenshot) return
        if (!templateLoaded) return
        if (!criteriaMet()) {
            Log.d(TAG, "Criteria not met, skipping screenshot")
            return
        }

        isProcessingScreenshot = true
        lastScreenshotTime = currentTime

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            screenshotExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    processScreenshot(screenshotResult)
                    isProcessingScreenshot = false
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot failed with error code: $errorCode")
                    isProcessingScreenshot = false

                    // Handle rate limiting
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        lastScreenshotTime = SystemClock.uptimeMillis() +
                                ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS
                    }
                }
            }
        )
    }

    /**
     * Process the captured screenshot for template matching.
     * Implements the Hardware Bitmap to Software Bitmap fix.
     */
    private fun processScreenshot(screenshotResult: ScreenshotResult) {
        try {
            // THE BITMAP MEMORY FIX:
            // Android 11+ takeScreenshot() returns Hardware-backed Bitmap (Config.HARDWARE)
            // MUST copy to software-backed bitmap before sending to OpenCV Mat
            val hardwareBuffer: HardwareBuffer? = screenshotResult.hardwareBuffer
            val colorSpace: ColorSpace? = screenshotResult.colorSpace

            if (hardwareBuffer == null) {
                Log.e(TAG, "HardwareBuffer is null")
                return
            }

            val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
            if (hardwareBitmap == null) {
                Log.e(TAG, "Failed to wrap hardware buffer")
                return
            }

            // Copy to software-backed ARGB_8888 bitmap
            val softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
            if (softwareBitmap == null) {
                Log.e(TAG, "Failed to create software bitmap")
                hardwareBitmap.recycle()
                return
            }

            // Convert to OpenCV Mat
            val screenMat = Mat()
            Utils.bitmapToMat(softwareBitmap, screenMat)

            // Convert to grayscale for template matching
            val grayScreenMat = Mat()
            Imgproc.cvtColor(screenMat, grayScreenMat, Imgproc.COLOR_BGRA2GRAY)

            // Perform template matching
            val matchResult = performTemplateMatching(grayScreenMat)

            // Clean up
            screenMat.release()
            grayScreenMat.release()
            hardwareBitmap.recycle()
            softwareBitmap.recycle()
            hardwareBuffer.close()

            if (matchResult != null) {
                consecutiveMatches++
                consecutiveIdleCycles = 0
                Log.i(TAG, "Template match found at (${matchResult.x}, ${matchResult.y}) " +
                        "with score >= $MATCH_THRESHOLD")

                // Check InputDispatcher stuck pointer workaround
                if (consecutiveMatches >= IDLE_RESET_THRESHOLD || lastGestureFailed) {
                    injectMultiTouchReset()
                }

                // Dispatch touch gesture with humanized parameters
                dispatchHumanizedTouch(matchResult)
            } else {
                consecutiveIdleCycles++
                if (consecutiveIdleCycles >= IDLE_RESET_THRESHOLD) {
                    consecutiveMatches = 0
                    consecutiveIdleCycles = 0
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing screenshot: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Perform OpenCV template matching using TM_CCOEFF_NORMED.
     * Returns the match location if correlation score >= threshold.
     */
    private fun performTemplateMatching(screenMat: Mat): org.opencv.core.Point? {
        val template = templateMat ?: return null

        // Validate dimensions
        if (screenMat.cols() < template.cols() || screenMat.rows() < template.rows()) {
            Log.w(TAG, "Screen smaller than template")
            return null
        }

        // Create result matrix
        val resultCols = screenMat.cols() - template.cols() + 1
        val resultRows = screenMat.rows() - template.rows() + 1
        val resultMat = Mat(resultRows, resultCols, CvType.CV_32FC1)

        // Run template matching
        Imgproc.matchTemplate(screenMat, template, resultMat, Imgproc.TM_CCOEFF_NORMED)

        // Find best match
        val minMaxResult = Core.minMaxLoc(resultMat)
        val maxVal = minMaxResult.maxVal
        val maxLoc = minMaxResult.maxLoc

        resultMat.release()

        return if (maxVal >= MATCH_THRESHOLD) {
            maxLoc
        } else {
            null
        }
    }

    /**
     * Dispatch a humanized touch gesture with:
     * - Safe elliptical touch zone targeting (25%-75% of template dimensions)
     * - Valid accessibility gesture stroke path (1-pixel line shift)
     * - Randomized reflex delay (10-100ms)
     * - ToneGenerator double-beep feedback
     */
    private fun dispatchHumanizedTouch(matchLoc: org.opencv.core.Point) {
        // Safe Elliptical Touch Zone: restrict to 25%-75% of template dimensions
        val randomX = matchLoc.x + Random.nextInt(safeWidthMin, safeWidthMax)
        val randomY = matchLoc.y + Random.nextInt(safeHeightMin, safeHeightMax)

        // Ensure coordinates stay within screen bounds
        val displayMetrics = resources.displayMetrics
        val finalClickX = min(max(randomX.toFloat(), 0f), displayMetrics.widthPixels.toFloat())
        val finalClickY = min(max(randomY.toFloat(), 0f), displayMetrics.heightPixels.toFloat())

        Log.d(TAG, "Dispatching touch at ($finalClickX, $finalClickY)")

        // Randomized reflex delay (10-100ms)
        val reflexDelay = Random.nextLong(MIN_REFLEX_DELAY_MS, MAX_REFLEX_DELAY_MS + 1)

        mainHandler.postDelayed({
            // THE CLICK FIX:
            // Touch gestures with only moveTo(x,y) are zero-length vectors
            // and are silently discarded by Android's InputDispatcher.
            // Add a microscopic 1-pixel line shift to formulate a valid path vector.
            val path = Path()
            path.moveTo(finalClickX, finalClickY)
            path.lineTo(finalClickX, finalClickY + 1f)

            // Use ViewConfiguration.getTapTimeout() for clean physical tap (typically 40-100ms)
            val tapDuration = ViewConfiguration.getTapTimeout().toLong()

            val stroke = GestureDescription.StrokeDescription(
                path,
                0L,
                tapDuration
            )

            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(stroke)

            val gesture = gestureBuilder.build()

            // Dispatch gesture with result callback
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    lastGestureFailed = false
                    gestureFailureCount = 0
                    Log.i(TAG, "Gesture completed successfully")

                    // Double-beep feedback
                    playDoubleBeep()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    lastGestureFailed = true
                    gestureFailureCount++
                    Log.w(TAG, "Gesture cancelled. Failure count: $gestureFailureCount")

                    // InputDispatcher stuck pointer workaround
                    if (gestureFailureCount >= 2) {
                        injectMultiTouchReset()
                        gestureFailureCount = 0
                    }
                }
            }, mainHandler)
        }, reflexDelay)
    }

    /**
     * ANDROID 15 GESTURE FIX:
     * InputDispatcher Stuck Pointer Workaround.
     *
     * On Android 15, simulated touch events can occasionally become stuck in 'down' state,
     * rejecting subsequent clicks. To release stuck pointer states, inject an asynchronous
     * multi-touch reset gesture (simulating two simultaneous points touching the screen).
     */
    private fun injectMultiTouchReset() {
        Log.w(TAG, "Injecting multi-touch reset gesture")

        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f
        val centerY = displayMetrics.heightPixels / 2f

        // Create two simultaneous touch points
        val path1 = Path()
        path1.moveTo(centerX - 50f, centerY)
        path1.lineTo(centerX - 50f, centerY + 1f)

        val path2 = Path()
        path2.moveTo(centerX + 50f, centerY)
        path2.lineTo(centerX + 50f, centerY + 1f)

        val stroke1 = GestureDescription.StrokeDescription(path1, 0L, 50L)
        val stroke2 = GestureDescription.StrokeDescription(path2, 0L, 50L)

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(stroke1)
        gestureBuilder.addStroke(stroke2)

        dispatchGesture(gestureBuilder.build(), null, null)
    }

    /**
     * Play a sharp double-beep using ToneGenerator for 80ms.
     */
    private fun playDoubleBeep() {
        try {
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_DURATION_MS)
            mainHandler.postDelayed({
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_DURATION_MS)
            }, 150)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing tone: ${e.message}")
        }
    }

    /**
     * Called when the service is interrupted.
     */
    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    /**
     * Called when the service is destroyed by the OS.
     * CRITICAL: Set instance to null to prevent null context access.
     */
    override fun onDestroy() {
        super.onDestroy()
        engineActive = false
        instance = null
        toneGenerator.release()
        templateMat?.release()
        templateMat = null
        Log.i(TAG, "Service destroyed - instance cleared")
    }
}
