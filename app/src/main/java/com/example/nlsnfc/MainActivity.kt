package com.example.nlsnfc
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.google.android.material.tabs.TabLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main activity for the NLSNFC app.
 *
 * Purpose:
 * - Provide a simple NFC stress-read test UI (continuously reads tags and increments a counter).
 * - Optionally POST each successful read to a remote server without interrupting the test.
 *
 * Notes for maintainers:
 * - Keep UI updates on the main thread; network work is done on a background thread.
 * - Logging uses a single tag (LOG_TAG) to simplify Logcat filtering.
 */
class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvUid: TextView
    private lateinit var tvType: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvReadCount: TextView
    private lateinit var lvHistory: ListView
    private lateinit var lvErrors: ListView
    private lateinit var tabLayout: TabLayout

    private lateinit var historyAdapter: ArrayAdapter<String>
    private lateinit var errorAdapter: ArrayAdapter<String>
    private var lastReadingSummary: String? = null
    private var nfcPromptShowing: Boolean = false
    private var readCounter: Int = 0
    // Upper bound for items kept in History/Errors adapters to avoid unbounded growth
    private val MAX_HISTORY = 200

    /**
     * System file path where the device's serial number is stored.
     * This path is specific to Newland Android devices (e.g., NLS-MT90).
     */
    private val SN_PATH = "/sys/bus/platform/devices/newland-misc/SN"

    // Single Logcat tag for all app diagnostics
    private val LOG_TAG = "NLSNFC"

    // Re-arm reader mode periodically to allow consecutive reads of the same card
    private val handler = Handler(Looper.getMainLooper())
    private var pollingActive = false
    private val pollIntervalMs = 1000L
    private val rearmReaderRunnable = object : Runnable {
        /**
         * Periodically re-arms the NFC reader mode to allow consecutive reads of the same card.
         * If NFC is disabled, waits until it is enabled again before attempting to re-arm.
         * If a transient error occurs while re-arming, ignores it and continues polling.
         */
        override fun run() {
            val adapter = nfcAdapter
            if (!pollingActive || adapter == null) return
            if (!adapter.isEnabled) {
                // Try later when NFC is enabled again
                handler.postDelayed(this, pollIntervalMs)
                return
            }
            // Toggle reader mode to force rediscovery, even if the same tag remains present
            try {
                disableReaderMode()
                enableReaderMode()
            } catch (_: Throwable) {
                // Ignore any transient errors; keep polling
            }
            handler.postDelayed(this, pollIntervalMs)
        }
    }

        /**
         * Called when the activity is created.
         * Sets up the UI and enables edge-to-edge mode.
         * Sets the content view to R.layout.activity_main.
         * Sets up the window insets listener to apply the system bars insets.
         * Sets up the NFC adapter and updates the UI to reflect its state.
         * Sets up the tabs and their listeners.
         * Sets up the adapters for the history and errors lists.
         * Enables the NFC adapter if available.
         */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        tvStatus = findViewById(R.id.tvStatus)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        tvUid = findViewById(R.id.tvUid)
        tvType = findViewById(R.id.tvType)
        tvTime = findViewById(R.id.tvTime)
        tvReadCount = findViewById(R.id.tvReadCount)
        lvHistory = findViewById(R.id.lvHistory)
        lvErrors = findViewById(R.id.lvErrors)
        tabLayout = findViewById(R.id.tabLayout)

        historyAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        lvHistory.adapter = historyAdapter
        errorAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        lvErrors.adapter = errorAdapter

        // Execution guard: allow ONLY Android 11+ (API 30 or newer). Older versions show a warning and exit after 10 seconds.
        run {
            val isAndroid11Plus = Build.VERSION.SDK_INT >= 30
            if (!isAndroid11Plus) {
                val message = buildString {
                    append("This app requires Android 11 (API 30) or newer.\n\nThe app will close.")
                }
                tvStatus.text = message
                val dlg = AlertDialog.Builder(this)
                    .setTitle("Unsupported Android version")
                    .setMessage(message)
                    .setCancelable(false)
                    .create()
                dlg.show()
                handler.postDelayed({
                    try { dlg.dismiss() } catch (_: Throwable) {}
                    finish()
                }, 10_000)
                return
            }
        }

        // Setup tabs
        tabLayout.addTab(tabLayout.newTab().setText("History"))
        tabLayout.addTab(tabLayout.newTab().setText("Errors"))
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            /**
             * Called when a tab is selected.
             * Shows the history list if the first tab is selected, and hides the errors list.
             * Shows the errors list if the second tab is selected, and hides the history list.
             *
             * @param tab The tab that was selected.
             */
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (tab.position == 0) {
                    lvHistory.visibility = android.view.View.VISIBLE
                    lvErrors.visibility = android.view.View.GONE
                } else {
                    lvHistory.visibility = android.view.View.GONE
                    lvErrors.visibility = android.view.View.VISIBLE
                }
            }

            /**
             * Called when a tab is unselected.
             * No-op implementation; does nothing.
             *
             * @param tab The tab that was unselected.
             */
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            /**
             * Called when a tab is reselected.
             * No-op implementation; does nothing.
             *
             * @param tab The tab that was reselected.
             */
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        // Default to History tab
        tabLayout.getTabAt(0)?.select()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            tvStatus.text = "This device does not support NFC"
            Toast.makeText(this, "NFC not supported", Toast.LENGTH_LONG).show()
        } else {
            updateUiForNfcState()
        }

        // Populate second line with device model and serial/ID
        val devType = getDeviceType()
        val devSn = getDeviceSn()
        val info = buildString {
            append(devType.ifBlank { "Unknown device" })
            if (devSn.isNotBlank()) {
                append(" | SN: ")
                append(devSn)
            }
        }
        tvDeviceInfo.text = info
    }

    /**
     * Called when the activity is about to enter an in-active state.
     * Updates the UI to reflect the current NFC state, and prompts the user if NFC is disabled.
     * Additionally, it attempts to mitigate the "ghost" error by disabling and re-enabling the NFC adapter
     * before starting to read. This uses reflection to call the hidden disable() and enable() methods,
     * and falls back to normal behavior if not available.
     * Finally, it starts periodic re-arming so the same card can be read repeatedly.
     */
    override fun onResume() {
        super.onResume()
        // Update UI and prompt user if NFC is disabled
        updateUiForNfcState()
        // Try to mitigate the previous "ghost" error by resetting the NFC adapter
        // using disable() followed by enable() before starting to read.
        // On most devices these methods are hidden/privileged; we attempt via reflection
        // and fall back to normal behavior if not available.
        resetNfcAndStartReaderMode()
        // Start periodic re-arming so the same card can be read repeatedly
        pollingActive = true
        handler.postDelayed(rearmReaderRunnable, pollIntervalMs)
    }

    /**
     * Called when the activity is about to enter an inactive state.
     * In this method, we stop periodic polling for NFC tags and disable reader mode.
     * This is done to conserve system resources and prevent unnecessary NFC polling.
     */
    override fun onPause() {
        super.onPause()
        // Stop periodic polling and disable reader mode
        pollingActive = false
        handler.removeCallbacks(rearmReaderRunnable)
        disableReaderMode()
    }

    /**
     * Enables NFC reader mode for the current activity.
     * This method should be called when NFC reading is needed, such as in onResume().
     * It will start the device looking for tags and call the onTagDiscovered() callback
     * when a tag is discovered.
     *
     * @see NfcAdapter.enableReaderMode
     */
    private fun enableReaderMode() {
        val adapter = nfcAdapter ?: return
        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        adapter.enableReaderMode(this, this, flags, null)
    }

    /**
     * Disables NFC reader mode.
     * This method should be called when NFC is no longer needed, such as in onPause().
     * It will stop the device from looking for tags and prevent the onTagDiscovered()
     * callback from being called.
     */
    private fun disableReaderMode() {
        nfcAdapter?.disableReaderMode(this)
    }

    /**
     * Attempts to call hidden NfcAdapter.disable() and NfcAdapter.enable() to reset the adapter
     * before enabling reader mode. If reflection or permission is not available, it falls back
     * to enabling reader mode directly when NFC is already enabled.
     */
    private fun resetNfcAndStartReaderMode() {
        val adapter = nfcAdapter ?: return
        Thread {
            var resetAttempted = false
            try {
                // Reflectively call hidden methods; these may require privileged permission on OEM builds
                val disableMethod = NfcAdapter::class.java.getMethod("disable")
                val enableMethod = NfcAdapter::class.java.getMethod("enable")
                disableMethod.invoke(adapter)
                // Small delay to allow hardware stack to settle
                try { Thread.sleep(300) } catch (_: InterruptedException) {}
                enableMethod.invoke(adapter)
                resetAttempted = true
            } catch (_: Throwable) {
                // Ignore and fall back
            }

            runOnUiThread {
                // Refresh UI and enable reader mode if NFC is enabled
                updateUiForNfcState()
                if (adapter.isEnabled) {
                    // If reset was attempted, the stack may still be coming up; add a tiny delay
                    if (resetAttempted) {
                        // Post a slight delay before enabling reader mode
                        Thread {
                            try { Thread.sleep(150) } catch (_: InterruptedException) {}
                            runOnUiThread { enableReaderMode() }
                        }.start()
                    } else {
                        enableReaderMode()
                    }
                }
            }
        }.start()
    }

    /**
     * Called when the NFC tag is no longer present after a successful read.
     * This method is responsible for updating the UI to reflect the new reading,
     * incrementing the read counter, and moving the previous reading to the history list.
     * It also handles any errors that may occur during reading, such as tag not found
     * or other exceptions. If an error occurs, it updates the UI to reflect the error,
     * but does not stop NFC reading.
     *
     * @param tag The NFC tag that was just read, or null if the tag is not available.
     */
    override fun onTagDiscovered(tag: Tag?) {
        try {
            if (tag == null) throw IllegalStateException("Tag is null")

            val uid = bytesToHex(tag.id)
            val type = detectTagType(tag)
            // Fully qualified protocol class names supported by the tag
            val protocols = tag.techList.joinToString(", ")
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            // Build multiline summary
            val summary = "Read\n$time\n$type\n$uid\n$protocols"

            // We'll track the current count to report to server
            var currentCount: Int
            runOnUiThread {
                // Increment and display read counter
                readCounter += 1
                currentCount = readCounter
                tvReadCount.text = "Counter: $readCounter"
                // Move previous reading to history
                lastReadingSummary?.let { prev ->
                    historyAdapter.insert(prev, 0)
                    trimAdapter(historyAdapter)
                }

                // Update current reading views
                tvStatus.text = "Read"
                tvTime.text = time
                tvType.text = type
                tvUid.text = "$uid\n$protocols"

                lastReadingSummary = summary
                // Fire-and-forget optional server update (does not block UI)
                postLastReadAsync(
                    devType = getDeviceType(),
                    devSn = getDeviceSn(),
                    counter = currentCount,
                    uid = uid,
                    dateTime = time
                ) { err ->
                    // On failure, add a short note to errors list without disrupting flow
                    err?.let {
                        val es = "${time} | POST_FAIL | ${it}"
                        errorAdapter.insert(es, 0)
                        trimAdapter(errorAdapter)
                    }
                }
            }
        } catch (e: Throwable) {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val errorSummary = "$time | ERROR | ${e.message ?: e::class.java.simpleName}"
            runOnUiThread {
                // Increment and display read counter
                readCounter += 1
                tvReadCount.text = "Reads: $readCounter"
                // Move previous reading to history
                lastReadingSummary?.let { prev ->
                    historyAdapter.insert(prev, 0)
                    trimAdapter(historyAdapter)
                }

                // Update UI to reflect the error, but do not stop NFC reading
                tvStatus.text = "Read error"
                tvUid.text = ""
                tvType.text = ""
                tvTime.text = time
                lastReadingSummary = errorSummary

                // Add error to error list and trim
                errorAdapter.insert(errorSummary, 0)
                trimAdapter(errorAdapter)
            }
        }
    }

    /**
     * Returns the device model string for server reporting and UI.
     * Uses Build.MODEL only (manufacturer omitted as requested).
     */
    private fun getDeviceType(): String {
        val model = Build.MODEL ?: ""
        return model.trim()
    }

    /**
     * Returns a device serial/ID string for server reporting.
     * Uses ANDROID_ID as a stable per-device identifier for the app.
     */
    private fun getDeviceSn(): String {
        // 1) Try Newland OEM sysfs path first
        try {
            val f = File(SN_PATH)
            if (f.exists() && f.canRead()) {
                val raw = f.readText(Charsets.UTF_8).trim()
                val sn = raw.replace("\u0000", "").trim() // strip any NULs
                if (sn.isNotEmpty()) {
                    Log.i(LOG_TAG, "SN source: newland sysfs | value=$sn")
                    return sn
                }
            }
        } catch (_: Throwable) {
            // ignore and fallback
        }

        // 2) Fallback: ANDROID_ID as stable per-device ID
        return try {
            val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
            Log.i(LOG_TAG, "SN source: ANDROID_ID | value=$id")
            id
        } catch (_: Throwable) {
            Log.w(LOG_TAG, "SN source: ANDROID_ID lookup failed; using UNKNOWN")
            "UNKNOWN"
        }
    }

    /**
     * Posts last read info to the remote server using application/x-www-form-urlencoded.
     * Runs in a background thread; callback receives error message on failure or null on success.
     */
    private fun postLastReadAsync(
        devType: String,
        devSn: String,
        counter: Int,
        uid: String,
        dateTime: String,
        onDone: (error: String?) -> Unit
    ) {
        // If required fields are empty, skip silently
        if (devType.isBlank() && devSn.isBlank()) {
            Log.w(LOG_TAG, "POST skipped: both DEV_TYPE and DEV_SN are blank")
            onDone(null)
            return
        }
        Thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("https://labndevor.leoaidc.com/create")
                Log.i(
                    LOG_TAG,
                    "POST start: url=${url} DEV_TYPE=${devType} DEV_SN=${devSn} NFC-COUNTER=${counter} NFC-UID=${uid} NFC-DATETIME=${dateTime}"
                )
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = 5000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }

                val body = buildForm(
                    "DEV_TYPE" to devType,
                    "DEV_SN" to devSn,
                    "NFC-COUNTER" to counter.toString(),
                    "NFC-UID" to uid,
                    "NFC-DATETIME" to dateTime
                )
                BufferedWriter(OutputStreamWriter(conn.outputStream, Charsets.UTF_8)).use { w ->
                    w.write(body)
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    Log.i(LOG_TAG, "POST success: HTTP $code")
                    runOnUiThread { onDone(null) }
                } else {
                    val msg = conn.responseMessage
                    Log.w(LOG_TAG, "POST http_error: code=$code message=$msg")
                    runOnUiThread { onDone("HTTP $code ${msg ?: ""}".trim()) }
                }
            } catch (t: Throwable) {
                Log.e(LOG_TAG, "POST exception: ${t.message}", t)
                runOnUiThread { onDone(t.message ?: t::class.java.simpleName) }
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    /**
     * Builds a URL-encoded form string from a given list of key-value pairs.
     * Each pair is concatenated with "&" and each key-value pair is URL-encoded using UTF-8.
     * @param pairs A list of key-value pairs to build the form string from.
     * @return The URL-encoded form string.
     */
    private fun buildForm(vararg pairs: Pair<String, String>): String {
        return pairs.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
    }

    /**
     * Detects the type(s) of a given NFC tag.
     * Returns a comma-separated string of the tag types.
     * If the tag does not support any of the known types, returns "Unknown".
     *
     * @param tag The NFC tag to detect the type of.
     * @return A comma-separated string of the tag types.
     */
    private fun detectTagType(tag: Tag): String {
        val techs = tag.techList
        val types = mutableListOf<String>()
        if (techs.contains(NfcA::class.java.name)) types.add("NfcA")
        if (techs.contains(NfcB::class.java.name)) types.add("NfcB")
        if (techs.contains(NfcF::class.java.name)) types.add("NfcF")
        if (techs.contains(NfcV::class.java.name)) types.add("NfcV")
        if (techs.contains(MifareClassic::class.java.name)) types.add("MIFARE Classic")
        if (techs.contains(MifareUltralight::class.java.name)) types.add("MIFARE Ultralight")
        if (techs.contains(IsoDep::class.java.name)) types.add("ISO-DEP")
        if (techs.contains(Ndef::class.java.name)) types.add("NDEF")
        if (techs.contains(NdefFormatable::class.java.name)) types.add("NDEF Formatable")

        return if (types.isEmpty()) "Unknown" else types.joinToString(
            separator = ", ",
            prefix = "",
            postfix = ""
        )
    }

    /**
     * Converts a byte array to a hexadecimal string representation.
     * If the input byte array is null, returns an empty string.
     * Otherwise, returns a string where each byte is represented as a two-digit
     * hexadecimal number separated by colons.
     *
     * @param bytes The byte array to convert.
     * @return A hexadecimal string representation of the byte array.
     */
    private fun bytesToHex(bytes: ByteArray?): String {
        if (bytes == null) return ""
        return bytes.joinToString(":") { b -> String.format(Locale.US, "%02X", b) }
    }

    /**
     * Updates the UI to reflect the current NFC state.
     * If NFC is disabled, updates the UI to prompt the user to enable it.
     * If NFC is enabled, updates the UI to reflect this state.
     */
    private fun updateUiForNfcState() {
        val adapter = nfcAdapter
        if (adapter == null) {
            tvStatus.text = "This device does not support NFC"
            tvStatus.setOnClickListener(null)
            return
        }

        if (!adapter.isEnabled) {
            tvStatus.text = "NFC is disabled. Tap here to enable."
            tvStatus.setOnClickListener {
                startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            }

            // Additionally, present a prompt once when we detect it's disabled
            if (!nfcPromptShowing) {
                nfcPromptShowing = true
                AlertDialog.Builder(this)
                    .setTitle("Enable NFC")
                    .setMessage("NFC is turned off. Would you like to open settings to enable it?")
                    .setCancelable(true)
                    .setPositiveButton("Open Settings") { dialog, _ ->
                        startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                        dialog.dismiss()
                        nfcPromptShowing = false
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        dialog.dismiss()
                        nfcPromptShowing = false
                    }
                    .show()
            }
        } else {
            tvStatus.text = "Tap an NFC card to read"
            tvStatus.setOnClickListener(null)
        }
    }

    /**
     * Trims an adapter to keep at most [MAX_HISTORY] items to control memory usage.
     * Removes items from the end beyond the limit and notifies the adapter of the change.
     *
     * @param adapter The adapter to trim.
     */
    private fun trimAdapter(adapter: ArrayAdapter<String>) {
        // Ensure we keep at most MAX_HISTORY items to control memory usage
        if (adapter.count > MAX_HISTORY) {
            // Remove items from the end beyond the limit
            while (adapter.count > MAX_HISTORY) {
                adapter.remove(adapter.getItem(adapter.count - 1))
            }
            adapter.notifyDataSetChanged()
        }
    }
}