package com.android.sys.ui.diagnostics

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import org.opencv.android.OpenCVLoader

/**
 * MainActivity - Unified Single-Switch Dashboard for Dr. Clicker (System UI Diagnostics)
 * 
 * Handles:
 * - Form input validation and SharedPreferences storage
 * - Permission checking (Accessibility, Overlay, Media)
 * - Unified "Engine Activation" toggle with sequential verification
 * - Template image selection from gallery
 * - Dark theme UI with real-time status updates
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "DrClickerPrefs"
        private const val KEY_MIN_PRICE = "min_price"
        private const val KEY_MAX_PRICE = "max_price"
        private const val KEY_MIN_DISTANCE = "min_distance"
        private const val KEY_MAX_DISTANCE = "max_distance"
        private const val KEY_TEMPLATE_PATH = "template_path"
        private const val KEY_ENGINE_ACTIVE = "engine_active"
        private const val DEFAULT_MIN_PRICE = 0
        private const val DEFAULT_MAX_PRICE = 99999
        private const val DEFAULT_MIN_DISTANCE = 0.0f
        private const val DEFAULT_MAX_DISTANCE = 999.0f
        private const val TARGET_PACKAGE = "com.rapido.rider"
        private const val REQUEST_CODE_MEDIA = 1001
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var switchEngine: SwitchMaterial
    private lateinit var etMinPrice: EditText
    private lateinit var etMaxPrice: EditText
    private lateinit var etMinDistance: EditText
    private lateinit var etMaxDistance: EditText
    private lateinit var btnSaveSettings: MaterialButton
    private lateinit var btnSetTemplate: MaterialButton
    private lateinit var tvEngineStatus: TextView
    private lateinit var tvTemplateStatus: TextView
    private lateinit var tvStatusDetails: TextView

    private var pendingEngineActivation = false
    private var selectedTemplateUri: Uri? = null

    /**
     * ActivityResultLauncher for overlay permission request.
     * Handles the result from Settings.ACTION_MANAGE_OVERLAY_PERMISSION.
     */
    private val overlayPermissionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            if (Settings.canDrawOverlays(this)) {
                if (pendingEngineActivation) {
                    pendingEngineActivation = false
                    proceedWithEngineActivation()
                }
            } else {
                switchEngine.isChecked = false
                updateEngineStatus("Overlay permission denied", false)
                Toast.makeText(this, getString(R.string.overlay_permission_required), Toast.LENGTH_LONG).show()
            }
        }

    /**
     * ActivityResultLauncher for template image picker.
     * Handles gallery image selection for the Accept button template.
     */
    private val templatePickerLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri ->
                    selectedTemplateUri = uri
                    saveTemplatePath(uri.toString())
                    tvTemplateStatus.text = getString(R.string.template_set)
                    tvTemplateStatus.setTextColor(ContextCompat.getColor(this, R.color.success_green))
                    Toast.makeText(this, "Template selected successfully", Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize OpenCV
        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, "OpenCV initialization failed!", Toast.LENGTH_LONG).show()
        }

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        initializeViews()
        loadSavedSettings()
        setupListeners()
        updateUIState()
    }

    override fun onResume() {
        super.onResume()
        // Re-check permissions when returning from settings
        if (switchEngine.isChecked) {
            if (!isAccessibilityServiceEnabled()) {
                switchEngine.isChecked = false
                updateEngineStatus(getString(R.string.idle), false)
                Toast.makeText(this, getString(R.string.accessibility_required), Toast.LENGTH_LONG).show()
            } else if (!Settings.canDrawOverlays(this)) {
                switchEngine.isChecked = false
                updateEngineStatus(getString(R.string.idle), false)
            }
        }
        updateUIState()
    }

    /**
     * Initialize all view references from the layout.
     */
    private fun initializeViews() {
        switchEngine = findViewById(R.id.switchEngineActivation)
        etMinPrice = findViewById(R.id.etMinPrice)
        etMaxPrice = findViewById(R.id.etMaxPrice)
        etMinDistance = findViewById(R.id.etMinDistance)
        etMaxDistance = findViewById(R.id.etMaxDistance)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
        btnSetTemplate = findViewById(R.id.btnSetTemplate)
        tvEngineStatus = findViewById(R.id.tvEngineStatus)
        tvTemplateStatus = findViewById(R.id.tvTemplateStatus)
        tvStatusDetails = findViewById(R.id.tvStatusDetails)
    }

    /**
     * Load previously saved settings from SharedPreferences.
     * Applies safe fallbacks for blank or invalid values.
     */
    private fun loadSavedSettings() {
        val minPrice = prefs.getInt(KEY_MIN_PRICE, DEFAULT_MIN_PRICE)
        val maxPrice = prefs.getInt(KEY_MAX_PRICE, DEFAULT_MAX_PRICE)
        val minDistance = prefs.getFloat(KEY_MIN_DISTANCE, DEFAULT_MIN_DISTANCE)
        val maxDistance = prefs.getFloat(KEY_MAX_DISTANCE, DEFAULT_MAX_DISTANCE)
        val templatePath = prefs.getString(KEY_TEMPLATE_PATH, null)
        val engineActive = prefs.getBoolean(KEY_ENGINE_ACTIVE, false)

        etMinPrice.setText(minPrice.toString())
        etMaxPrice.setText(maxPrice.toString())
        etMinDistance.setText(minDistance.toString())
        etMaxDistance.setText(maxDistance.toString())

        if (templatePath != null) {
            selectedTemplateUri = Uri.parse(templatePath)
            tvTemplateStatus.text = getString(R.string.template_set)
            tvTemplateStatus.setTextColor(ContextCompat.getColor(this, R.color.success_green))
        }

        switchEngine.isChecked = engineActive
        if (engineActive) {
            updateEngineStatus(getString(R.string.scanning), true)
        }
    }

    /**
     * Setup all UI component click and change listeners.
     */
    private fun setupListeners() {
        // Unified Engine Activation Switch
        switchEngine.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // User turned ON - execute verification checks in order
                performActivationChecks()
            } else {
                // User turned OFF - stop services
                stopEngine()
            }
        }

        // Save Settings Button
        btnSaveSettings.setOnClickListener {
            saveCurrentSettings()
        }

        // Set Template Button
        btnSetTemplate.setOnClickListener {
            checkMediaPermissionAndPickTemplate()
        }
    }

    /**
     * Check A (Accessibility): Verify Dr. Clicker is enabled in Accessibility Settings.
     * Check B (Overlay): Verify overlay permission is granted.
     * Check C (Activation): Start services if both permissions are confirmed.
     */
    private fun performActivationChecks() {
        // Check A: Accessibility Service
        if (!isAccessibilityServiceEnabled()) {
            switchEngine.isChecked = false
            Toast.makeText(this, getString(R.string.accessibility_required), Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            return
        }

        // Check B: Overlay Permission
        if (!Settings.canDrawOverlays(this)) {
            pendingEngineActivation = true
            switchEngine.isChecked = false
            Toast.makeText(this, getString(R.string.overlay_permission_required), Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }

        // Check C: Both permissions confirmed - activate engine
        proceedWithEngineActivation()
    }

    /**
     * Start the floating overlay service and set engine to active state.
     * Called only after both Accessibility and Overlay permissions are verified.
     */
    private fun proceedWithEngineActivation() {
        // Save engine state
        prefs.edit().putBoolean(KEY_ENGINE_ACTIVE, true).apply()

        // Start Floating Overlay Service
        val overlayIntent = Intent(this, FloatingOverlayService::class.java)
        overlayIntent.action = FloatingOverlayService.ACTION_START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(overlayIntent)
        } else {
            startService(overlayIntent)
        }

        updateEngineStatus(getString(R.string.scanning), true)
        updateStatusDetails("Engine active. Monitoring ${TARGET_PACKAGE}...")
        Toast.makeText(this, getString(R.string.service_running), Toast.LENGTH_SHORT).show()
    }

    /**
     * Safely stop the engine and remove overlay.
     */
    private fun stopEngine() {
        prefs.edit().putBoolean(KEY_ENGINE_ACTIVE, false).apply()

        // Stop Floating Overlay Service
        val overlayIntent = Intent(this, FloatingOverlayService::class.java)
        overlayIntent.action = FloatingOverlayService.ACTION_STOP
        startService(overlayIntent)

        updateEngineStatus(getString(R.string.idle), false)
        updateStatusDetails("Engine stopped. Waiting for activation...")
        Toast.makeText(this, getString(R.string.service_stopped), Toast.LENGTH_SHORT).show()
    }

    /**
     * Save current form values to SharedPreferences with safe fallbacks.
     * Prevents NumberFormatExceptions by using default values for blank inputs.
     */
    private fun saveCurrentSettings() {
        val minPrice = parseIntSafe(etMinPrice.text.toString(), DEFAULT_MIN_PRICE)
        val maxPrice = parseIntSafe(etMaxPrice.text.toString(), DEFAULT_MAX_PRICE)
        val minDistance = parseFloatSafe(etMinDistance.text.toString(), DEFAULT_MIN_DISTANCE)
        val maxDistance = parseFloatSafe(etMaxDistance.text.toString(), DEFAULT_MAX_DISTANCE)

        prefs.edit().apply {
            putInt(KEY_MIN_PRICE, minPrice)
            putInt(KEY_MAX_PRICE, maxPrice)
            putFloat(KEY_MIN_DISTANCE, minDistance)
            putFloat(KEY_MAX_DISTANCE, maxDistance)
            apply()
        }

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        updateStatusDetails("Settings saved. Price: ₹$minPrice-₹$maxPrice, Distance: ${minDistance}km-${maxDistance}km")
    }

    /**
     * Check media permission and launch template picker.
     * Handles both Android 13+ (READ_MEDIA_IMAGES) and older versions.
     */
    private fun checkMediaPermissionAndPickTemplate() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                launchTemplatePicker()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(this, permission) -> {
                AlertDialog.Builder(this)
                    .setTitle("Media Permission Required")
                    .setMessage("This app needs access to your photos to select the Accept button template image.")
                    .setPositiveButton("Grant") { _, _ ->
                        ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_CODE_MEDIA)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            else -> {
                ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_CODE_MEDIA)
            }
        }
    }

    /**
     * Launch the system image picker for template selection.
     */
    private fun launchTemplatePicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        templatePickerLauncher.launch(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_MEDIA -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    launchTemplatePicker()
                } else {
                    Toast.makeText(this, "Media permission is required to select template", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Save template image path to SharedPreferences.
     */
    private fun saveTemplatePath(path: String) {
        prefs.edit().putString(KEY_TEMPLATE_PATH, path).apply()
    }

    /**
     * Check if this app's accessibility service is enabled in system settings.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val serviceName = "${packageName}/${AutoAcceptEngineService::class.java.canonicalName}"
        return enabledServices.contains(serviceName)
    }

    /**
     * Update the engine status text and color.
     */
    private fun updateEngineStatus(status: String, isActive: Boolean) {
        tvEngineStatus.text = status
        val colorRes = if (isActive) R.color.status_active else R.color.status_idle
        tvEngineStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    /**
     * Update the detailed status text view.
     */
    private fun updateStatusDetails(details: String) {
        tvStatusDetails.text = details
    }

    /**
     * Update overall UI state based on current conditions.
     */
    private fun updateUIState() {
        val engineActive = prefs.getBoolean(KEY_ENGINE_ACTIVE, false)
        if (engineActive && !isAccessibilityServiceEnabled()) {
            switchEngine.isChecked = false
            updateEngineStatus(getString(R.string.idle), false)
        }
    }

    /**
     * Safely parse an integer from string input with fallback default.
     * Prevents NumberFormatException on blank or invalid input.
     */
    private fun parseIntSafe(value: String, defaultValue: Int): Int {
        return try {
            if (value.isBlank()) defaultValue else value.toInt()
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }

    /**
     * Safely parse a float from string input with fallback default.
     * Prevents NumberFormatException on blank or invalid input.
     */
    private fun parseFloatSafe(value: String, defaultValue: Float): Float {
        return try {
            if (value.isBlank()) defaultValue else value.toFloat()
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }
}
