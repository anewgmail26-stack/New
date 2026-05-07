package com.example.androidvpnapp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import android.widget.FrameLayout
import android.widget.ImageView
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
    private lateinit var startStopButton: Button
    private lateinit var serverNameText: TextView
    private lateinit var serverSubtitleText: TextView
    private lateinit var payloadNameText: TextView
    private lateinit var payloadSubtitleText: TextView

    private var configInput: EditText? = null
    private var linkInput: EditText? = null
    private var currentRawConfig = ""

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
        currentRawConfig = configStore.loadV2RayConfig()
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
            setPadding(dp(16), dp(16), dp(16), dp(14))
            setBackgroundColor(SURFACE)
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
        root.addView(buildBottomNav())

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
        refreshServerSpinner()
        refreshPayloadSpinner()
        updateGeneratedConfig()
    }

    private fun buildHeader(): View {
        val headerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(22), dp(18), dp(24))
        }

        headerContent.addView(TextView(this).apply {
            text = "◎"
            textSize = 40f
            gravity = Gravity.CENTER
            setTextColor(GREEN)
            background = oval(Color.WHITE, Color.argb(120, 255, 255, 255), dp(5))
            elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(78), dp(78))
        })
        headerContent.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 27f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))
        })
        headerContent.addView(TextView(this).apply {
            text = getString(R.string.dashboard_tagline)
            textSize = 13f
            setTextColor(Color.argb(220, 255, 255, 255))
            gravity = Gravity.CENTER
        })

        return FrameLayout(this).apply {
            background = rounded(GREEN, GREEN_DARK, dp(0), dp(30))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(14)) }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.bg_tunnel_network)
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0.26f
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(headerContent)
        }
    }

    private fun buildStatsRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        uploadText = statText("⬆", "Upload", "0 KB")
        downloadText = statText("⬇", "Download", "0 KB")
        addView(uploadText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, dp(6), 0)
        })
        addView(downloadText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(6), 0, 0, 0)
        })
    }

    private fun buildStatusBlock(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(18), 0, dp(12))
        statusText = TextView(this@MainActivity).apply {
            text = "Disconnected"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(RED)
            gravity = Gravity.CENTER
        }
        durationText = TextView(this@MainActivity).apply {
            text = "VPN Duration : 00:00:00"
            textSize = 15f
            setTextColor(GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        addView(statusText)
        addView(durationText)
    }

    private fun buildServerCard(): View = selectorCard(
        icon = "⌁",
        title = "Server",
        onClick = { serverSpinner.performClick() },
        nameBinder = { serverNameText = it },
        subtitleBinder = { serverSubtitleText = it },
        spinnerBinder = {
            serverSpinner = it
            serverSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    servers.getOrNull(position)?.let { server ->
                        configStore.saveSelectedServerId(server.id)
                        updateServerCard(server)
                    }
                    updateGeneratedConfig()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
    )

    private fun buildPayloadCard(): View = selectorCard(
        icon = "◆",
        title = "Payload / Tweak",
        onClick = { payloadSpinner.performClick() },
        nameBinder = { payloadNameText = it },
        subtitleBinder = { payloadSubtitleText = it },
        spinnerBinder = {
            payloadSpinner = it
            payloadSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    payloadTweaks.getOrNull(position)?.let { payload ->
                        configStore.saveSelectedPayloadId(payload.id)
                        updatePayloadCard(payload)
                    }
                    updateGeneratedConfig()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
    )

    private fun selectorCard(
        icon: String,
        title: String,
        onClick: () -> Unit,
        nameBinder: (TextView) -> Unit,
        subtitleBinder: (TextView) -> Unit,
        spinnerBinder: (Spinner) -> Unit
    ): View = card().apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }

        addView(TextView(this@MainActivity).apply {
            text = icon
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(GREEN)
            background = oval(GREEN_SOFT, GREEN_LIGHT, dp(1))
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(0, 0, dp(14), 0) })

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(GREEN)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Loading"
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(DARK)
                nameBinder(this)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Tap to choose"
                textSize = 13f
                setTextColor(GRAY)
                subtitleBinder(this)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        addView(TextView(this@MainActivity).apply {
            text = "⌄"
            textSize = 28f
            setTextColor(GREEN)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT))

        addView(Spinner(this@MainActivity).apply {
            alpha = 0f
            visibility = View.VISIBLE
            spinnerBinder(this)
        }, LinearLayout.LayoutParams(1, 1))
    }

    private fun buildDnsRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = rounded(Color.WHITE, GREEN_LIGHT, dp(1), dp(24))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(16)) }
        addView(TextView(this@MainActivity).apply {
            text = "DNS"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(GREEN)
            gravity = Gravity.CENTER
            background = rounded(GREEN_SOFT, GREEN_SOFT, 0, dp(14))
        }, LinearLayout.LayoutParams(dp(48), dp(32)).apply { setMargins(0, 0, dp(12), 0) })
        addView(TextView(this@MainActivity).apply {
            text = "Use default DNS"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(DARK)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        dnsCheckbox = CheckBox(this@MainActivity).apply {
            isChecked = configStore.loadDnsEnabled()
            buttonTintList = ColorStateList.valueOf(GREEN)
            setOnCheckedChangeListener { _, isChecked ->
                configStore.saveDnsEnabled(isChecked)
                updateGeneratedConfig()
            }
        }
        addView(dnsCheckbox)
        setOnClickListener { dnsCheckbox.toggle() }
    }

    private fun buildStartStopButton(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(2), 0, dp(18))
        addView(FrameLayout(this@MainActivity).apply {
            background = oval(Color.TRANSPARENT, GREEN_SOFT, dp(10))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            startStopButton = Button(this@MainActivity).apply {
                text = "START"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(RED)
                background = oval(Color.WHITE, RED, dp(3))
                elevation = dp(8).toFloat()
                stateListAnimator = null
                setOnClickListener { toggleConnection() }
            }
            addView(startStopButton, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }, LinearLayout.LayoutParams(dp(166), dp(166)))
    }

    private fun buildBottomNav(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(10), dp(6), dp(8))
        background = rounded(Color.WHITE, GREEN_LIGHT, dp(1), dp(28))
        listOf(
            NavAction("↻", "Updates") { showToast("Updates coming soon.") },
            NavAction("✈", "Telegram") { showToast("Telegram coming soon.") },
            NavAction("☰", "Tools") { showToolsDialog() },
            NavAction("⇲", "Exit") { finish() }
        ).forEach { action ->
            addView(TextView(this@MainActivity).apply {
                text = "${action.icon}\n${action.label}"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(GREEN)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener { action.handler() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun showToolsDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        content.addView(TextView(this).apply {
            text = "Import Config"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(DARK)
        })
        content.addView(TextView(this).apply {
            text = "Paste a VLESS share link or raw JSON, then save it before connecting."
            textSize = 13f
            setTextColor(GRAY)
            setPadding(0, dp(4), 0, dp(10))
        })

        val vlessInput = EditText(this).apply {
            hint = "Paste VLESS link"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(false)
            minLines = 2
            background = rounded(Color.WHITE, GREEN_LIGHT, dp(1), dp(14))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        linkInput = vlessInput
        content.addView(vlessInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(10)) })

        val rawConfigInput = EditText(this).apply {
            hint = "Paste VLESS/JSON"
            setText(currentRawConfig)
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = rounded(Color.WHITE, GREEN_LIGHT, dp(1), dp(14))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        configInput = rawConfigInput
        content.addView(rawConfigInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        content.addView(TextView(this).apply {
            text = "Core status: Placeholder core ready"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(GREEN)
            setPadding(0, dp(12), 0, 0)
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("Tools")
            .setView(content)
            .setNegativeButton("Close", null)
            .setPositiveButton("Save/Import", null)
            .create()

        dialog.setOnDismissListener {
            configInput = null
            linkInput = null
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(GREEN)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (saveOrImportToolsConfig()) {
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(GRAY)
        }
        dialog.show()
    }

    private fun refreshServerSpinner() {
        servers = configStore.loadServers()
        val labels = servers.map { "${it.name}  •  ${it.host}:${it.port}" }
        serverSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val selectedIndex = servers.indexOfFirst { it.id == configStore.loadSelectedServerId() }.coerceAtLeast(0)
        serverSpinner.setSelection(selectedIndex)
        servers.getOrNull(selectedIndex)?.let { updateServerCard(it) }
    }

    private fun refreshPayloadSpinner() {
        val labels = payloadTweaks.map { "${it.name}  •  ${it.mode}" }
        payloadSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val selectedIndex = payloadTweaks.indexOfFirst { it.id == configStore.loadSelectedPayloadId() }.coerceAtLeast(0)
        payloadSpinner.setSelection(selectedIndex)
        payloadTweaks.getOrNull(selectedIndex)?.let { updatePayloadCard(it) }
    }

    private fun updateServerCard(server: TunnelServer) {
        serverNameText.text = server.name
        serverSubtitleText.text = "${server.host}:${server.port}"
    }

    private fun updatePayloadCard(payload: PayloadTweak) {
        payloadNameText.text = payload.name
        payloadSubtitleText.text = payload.mode
    }

    private fun updateGeneratedConfig() {
        if (!::serverSpinner.isInitialized || !::payloadSpinner.isInitialized || !::dnsCheckbox.isInitialized) return
        val server = servers.getOrNull(serverSpinner.selectedItemPosition) ?: return
        val payload = payloadTweaks.getOrNull(payloadSpinner.selectedItemPosition) ?: return
        val generated = TunnelProfile(server, payload, dnsCheckbox.isChecked).toInternalJson()
        currentRawConfig = generated
        val activeConfigInput = configInput
        if (activeConfigInput != null && !activeConfigInput.hasFocus()) {
            activeConfigInput.setText(generated)
        }
        configStore.saveV2RayConfig(generated)
    }

    private fun saveOrImportToolsConfig(): Boolean {
        val pastedLink = linkInput?.text?.toString()?.trim().orEmpty()
        if (pastedLink.isNotEmpty()) {
            return importVlessLink(pastedLink)
        }
        return saveRawJsonConfig(configInput?.text?.toString().orEmpty())
    }

    private fun saveRawJsonConfig(config: String = currentRawConfig): Boolean {
        val pastedConfig = config.trim()
        if (pastedConfig.isEmpty()) {
            configInput?.error = "Config cannot be empty."
            showToast("Config cannot be empty.")
            return false
        }

        try {
            JSONObject(pastedConfig)
        } catch (error: Exception) {
            configInput?.error = "Pasted config is not valid JSON."
            showToast("Pasted config is not valid JSON: ${error.message}")
            return false
        }

        currentRawConfig = pastedConfig
        configStore.saveV2RayConfig(pastedConfig)
        showToast("Config saved.")
        return true
    }

    private fun importVlessLink(link: String): Boolean {
        val serverResult = VlessLinkParser.parseToServer(link)
        if (serverResult.isFailure) {
            val message = serverResult.exceptionOrNull()?.message ?: "Invalid VLESS link."
            linkInput?.error = message
            showToast(message)
            return false
        }

        val server = serverResult.getOrThrow()
        val jsonResult = VlessLinkParser.toInternalJson(link)
        if (jsonResult.isFailure) {
            val message = jsonResult.exceptionOrNull()?.message ?: "Could not convert VLESS link."
            linkInput?.error = message
            showToast(message)
            return false
        }

        val importedConfig = jsonResult.getOrThrow()
        configStore.saveImportedVlessServer(server)
        configStore.saveV2RayConfig(importedConfig)
        currentRawConfig = importedConfig
        configInput?.setText(importedConfig)
        refreshServerSpinner()
        showToast("Imported ${server.remark}.")
        return true
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
            startStopButton.background = oval(Color.WHITE, GREEN, dp(3))
            timerHandler.removeCallbacks(timerRunnable)
            timerHandler.post(timerRunnable)
        } else {
            setStatus("Disconnected", RED)
            startStopButton.text = "START"
            startStopButton.setTextColor(RED)
            startStopButton.background = oval(Color.WHITE, RED, dp(3))
            timerHandler.removeCallbacks(timerRunnable)
            durationText.text = "VPN Duration : 00:00:00"
            uploadText.text = "⬆\nUpload\n0 KB"
            downloadText.text = "⬇\nDownload\n0 KB"
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
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(Color.WHITE, GREEN, dp(2), dp(26))
        elevation = dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(6), 0, dp(12)) }
    }

    private fun statText(icon: String, label: String, value: String): TextView = TextView(this).apply {
        text = "$icon\n$label\n$value"
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(DARK)
        gravity = Gravity.CENTER
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = rounded(Color.WHITE, GREEN_LIGHT, dp(1), dp(20))
        elevation = dp(1).toFloat()
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

    private data class NavAction(val icon: String, val label: String, val handler: () -> Unit)

    companion object {
        private const val REQUEST_VPN_PERMISSION = 100
        private const val REQUEST_NOTIFICATIONS = 101
        private val GREEN = Color.rgb(0, 155, 64)
        private val GREEN_DARK = Color.rgb(0, 120, 52)
        private val GREEN_LIGHT = Color.rgb(188, 232, 204)
        private val GREEN_SOFT = Color.rgb(230, 248, 235)
        private val RED = Color.rgb(198, 31, 49)
        private val DARK = Color.rgb(24, 40, 35)
        private val GRAY = Color.rgb(94, 112, 106)
        private val SURFACE = Color.rgb(246, 250, 247)
    }
}
