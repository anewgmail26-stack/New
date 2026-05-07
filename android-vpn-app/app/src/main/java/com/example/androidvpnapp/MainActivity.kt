package com.example.androidvpnapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var configStore: ConfigStore
    private lateinit var statusText: TextView
    private lateinit var uploadText: TextView
    private lateinit var downloadText: TextView
    private lateinit var durationText: TextView
    private lateinit var serverSpinner: Spinner
    private lateinit var payloadSpinner: Spinner
    private lateinit var dnsCheckbox: CheckBox
    private lateinit var configInput: EditText
    private lateinit var linkInput: EditText
    private lateinit var startStopButton: Button

    private val timerHandler = Handler(Looper.getMainLooper())
    private var connected = false
    private var connectedAt = 0L
    private var servers = emptyList<TunnelServer>()
    private val payloadTweaks = SampleTunnelCatalog.payloadTweaks

    private val timerRunnable = object : Runnable {
        override fun run() {
            updateDuration()
            if (connected) {
                timerHandler.postDelayed(this, 1_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = ConfigStore(applicationContext)
        buildUi()
        requestNotificationPermissionIfNeeded()
    }

    override fun onDestroy() {
        timerHandler.removeCallbacks(timerRunnable)
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) {
                startVpnService(MyVpnService.ACTION_CONNECT)
                setConnectedState(true)
            } else {
                setStatus("Disconnected", RED)
                showToast("VPN permission was denied.")
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(18))
            setBackgroundColor(Color.rgb(245, 248, 245))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        root.addView(buildHeader())
        root.addView(buildStatsRow())
        root.addView(buildStatusBlock())
        root.addView(buildServerCard())
        root.addView(buildPayloadCard())
        root.addView(buildDnsRow())
        root.addView(buildStartStopButton())
        root.addView(buildImportCard())
        root.addView(buildBottomNav())

        setContentView(ScrollView(this).apply { addView(root) })
        refreshServerSpinner()
        refreshPayloadSpinner()
        updateGeneratedConfig()
    }

    private fun buildHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(TextView(this@MainActivity).apply {
                text = "◎"
                textSize = 46f
                gravity = Gravity.CENTER
                setTextColor(GREEN)
                background = oval(Color.WHITE, GREEN, dp(3))
                layoutParams = LinearLayout.LayoutParams(dp(86), dp(86))
            })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_name)
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(DARK)
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(2))
            })
            addView(TextView(this@MainActivity).apply {
                text = "Tunnel profile manager • placeholder core"
                textSize = 13f
                setTextColor(GRAY)
                gravity = Gravity.CENTER
            })
        }
    }

    private fun buildStatsRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, dp(24), 0, dp(8))
        uploadText = statText("⬇ 0 KB")
        downloadText = statText("⬆ 0 KB")
        addView(uploadText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(downloadText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun buildStatusBlock(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        statusText = TextView(this@MainActivity).apply {
            text = "Disconnected"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(RED)
            gravity = Gravity.CENTER
        }
        durationText = TextView(this@MainActivity).apply {
            text = "VPN Duration : 00:00:00"
            textSize = 15f
            setTextColor(DARK)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(18))
        }
        addView(statusText)
        addView(durationText)
    }

    private fun buildServerCard(): View = card().apply {
        addView(TextView(this@MainActivity).apply {
            text = "📍 Server"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(GREEN)
        })
        serverSpinner = Spinner(this@MainActivity)
        serverSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                servers.getOrNull(position)?.let { configStore.saveSelectedServerId(it.id) }
                updateGeneratedConfig()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        addView(serverSpinner)
    }

    private fun buildPayloadCard(): View = card().apply {
        addView(TextView(this@MainActivity).apply {
            text = "🌐 Payload / Tweak"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(GREEN)
        })
        payloadSpinner = Spinner(this@MainActivity)
        payloadSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                payloadTweaks.getOrNull(position)?.let { configStore.saveSelectedPayloadId(it.id) }
                updateGeneratedConfig()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        addView(payloadSpinner)
    }

    private fun buildDnsRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(18))
        dnsCheckbox = CheckBox(this@MainActivity).apply {
            text = "DNS (Default DNS)"
            textSize = 18f
            setTextColor(DARK)
            isChecked = configStore.loadDnsEnabled()
            buttonTintList = android.content.res.ColorStateList.valueOf(GREEN)
            setOnCheckedChangeListener { _, isChecked ->
                configStore.saveDnsEnabled(isChecked)
                updateGeneratedConfig()
            }
        }
        addView(dnsCheckbox)
    }

    private fun buildStartStopButton(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, dp(22))
        startStopButton = Button(this@MainActivity).apply {
            text = "START"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(RED)
            background = oval(Color.WHITE, Color.LTGRAY, dp(10))
            setOnClickListener { toggleConnection() }
            layoutParams = LinearLayout.LayoutParams(dp(184), dp(184))
        }
        addView(startStopButton)
    }

    private fun buildImportCard(): View = card().apply {
        addView(TextView(this@MainActivity).apply {
            text = "Config Import"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(DARK)
        })
        linkInput = EditText(this@MainActivity).apply {
            hint = "Paste vless:// share link"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(false)
            minLines = 2
        }
        addView(linkInput)
        addView(Button(this@MainActivity).apply {
            text = "Import VLESS link"
            setOnClickListener { importVlessLink() }
        })
        configInput = EditText(this@MainActivity).apply {
            hint = "Raw JSON config is still supported"
            setText(configStore.loadV2RayConfig())
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        addView(configInput)
        addView(Button(this@MainActivity).apply {
            text = "Save raw JSON config"
            setOnClickListener { saveRawJsonConfig() }
        })
    }

    private fun buildBottomNav(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, dp(20), 0, 0)
        listOf("⬇\nUpdates", "✈\nTelegram", "🛠\nTools", "⇲\nExit").forEach { label ->
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(GREEN)
                typeface = Typeface.DEFAULT_BOLD
                setOnClickListener {
                    if (label.contains("Exit")) finish() else showToast("${label.substringAfter('\n')} coming soon.")
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun refreshServerSpinner() {
        servers = configStore.loadServers()
        val labels = servers.map { "${it.name}  •  ${it.host}:${it.port}" }
        serverSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val selectedIndex = servers.indexOfFirst { it.id == configStore.loadSelectedServerId() }.coerceAtLeast(0)
        serverSpinner.setSelection(selectedIndex)
    }

    private fun refreshPayloadSpinner() {
        val labels = payloadTweaks.map { "${it.name}  •  ${it.mode}" }
        payloadSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val selectedIndex = payloadTweaks.indexOfFirst { it.id == configStore.loadSelectedPayloadId() }.coerceAtLeast(0)
        payloadSpinner.setSelection(selectedIndex)
    }

    private fun updateGeneratedConfig() {
        val server = servers.getOrNull(serverSpinner.selectedItemPosition) ?: return
        val payload = payloadTweaks.getOrNull(payloadSpinner.selectedItemPosition) ?: return
        val generated = TunnelProfile(server, payload, dnsCheckbox.isChecked).toInternalJson()
        if (!configInput.hasFocus()) {
            configInput.setText(generated)
        }
        configStore.saveV2RayConfig(generated)
    }

    private fun saveRawJsonConfig(): Boolean {
        val pastedConfig = configInput.text.toString().trim()
        if (pastedConfig.isEmpty()) {
            configInput.error = "Config cannot be empty."
            showToast("Config cannot be empty.")
            return false
        }

        try {
            JSONObject(pastedConfig)
        } catch (error: Exception) {
            configInput.error = "Pasted config is not valid JSON."
            showToast("Pasted config is not valid JSON: ${error.message}")
            return false
        }

        configStore.saveV2RayConfig(pastedConfig)
        showToast("Raw JSON config saved.")
        return true
    }

    private fun importVlessLink() {
        val link = linkInput.text.toString()
        val serverResult = VlessLinkParser.parseToServer(link)
        if (serverResult.isFailure) {
            val message = serverResult.exceptionOrNull()?.message ?: "Invalid VLESS link."
            linkInput.error = message
            showToast(message)
            return
        }

        val server = serverResult.getOrThrow()
        val jsonResult = VlessLinkParser.toInternalJson(link)
        if (jsonResult.isFailure) {
            val message = jsonResult.exceptionOrNull()?.message ?: "Could not convert VLESS link."
            linkInput.error = message
            showToast(message)
            return
        }

        configStore.saveImportedVlessServer(server)
        configStore.saveV2RayConfig(jsonResult.getOrThrow())
        configInput.setText(jsonResult.getOrThrow())
        refreshServerSpinner()
        showToast("Imported ${server.remark}.")
    }

    private fun toggleConnection() {
        if (connected) {
            startVpnService(MyVpnService.ACTION_DISCONNECT)
            setConnectedState(false)
            return
        }

        if (!saveRawJsonConfig()) {
            setStatus("Disconnected", RED)
            return
        }

        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            startActivityForResult(permissionIntent, REQUEST_VPN_PERMISSION)
        } else {
            startVpnService(MyVpnService.ACTION_CONNECT)
            setConnectedState(true)
        }
    }

    private fun setConnectedState(isConnected: Boolean) {
        connected = isConnected
        if (isConnected) {
            connectedAt = SystemClock.elapsedRealtime()
            setStatus("Connected", GREEN)
            startStopButton.text = "STOP"
            startStopButton.setTextColor(GREEN)
            timerHandler.removeCallbacks(timerRunnable)
            timerHandler.post(timerRunnable)
        } else {
            setStatus("Disconnected", RED)
            startStopButton.text = "START"
            startStopButton.setTextColor(RED)
            timerHandler.removeCallbacks(timerRunnable)
            durationText.text = "VPN Duration : 00:00:00"
            uploadText.text = "⬇ 0 KB"
            downloadText.text = "⬆ 0 KB"
        }
    }

    private fun updateDuration() {
        val elapsed = ((SystemClock.elapsedRealtime() - connectedAt) / 1_000L).coerceAtLeast(0L)
        val hours = elapsed / 3_600L
        val minutes = (elapsed % 3_600L) / 60L
        val seconds = elapsed % 60L
        durationText.text = "VPN Duration : %02d:%02d:%02d".format(hours, minutes, seconds)
    }

    private fun startVpnService(action: String) {
        val intent = Intent(this, MyVpnService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14))
        background = rounded(Color.WHITE, GREEN, dp(3), dp(34))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(8), 0, dp(14)) }
    }

    private fun statText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(DARK)
        gravity = Gravity.CENTER
    }

    private fun setStatus(status: String, color: Int) {
        statusText.text = status
        statusText.setTextColor(color)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun rounded(fill: Int, stroke: Int, strokeWidth: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radius.toFloat()
            setStroke(strokeWidth, stroke)
        }

    private fun oval(fill: Int, stroke: Int, strokeWidth: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(strokeWidth, stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_VPN_PERMISSION = 100
        private const val REQUEST_NOTIFICATIONS = 101
        private val GREEN = Color.rgb(0, 155, 28)
        private val RED = Color.rgb(190, 0, 20)
        private val DARK = Color.rgb(25, 34, 38)
        private val GRAY = Color.rgb(96, 110, 118)
    }
}
