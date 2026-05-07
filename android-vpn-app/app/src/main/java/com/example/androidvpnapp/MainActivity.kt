package com.example.androidvpnapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var configStore: ConfigStore
    private lateinit var statusText: TextView
    private lateinit var configInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = ConfigStore(applicationContext)
        buildUi()
        requestNotificationPermissionIfNeeded()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) {
                startVpnService(MyVpnService.ACTION_CONNECT)
                setStatus("VPN status: connecting")
            } else {
                setStatus("VPN status: permission denied")
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 40)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(title)

        statusText = TextView(this).apply {
            text = "VPN status: disconnected"
            textSize = 18f
            setPadding(0, 28, 0, 20)
        }
        root.addView(statusText)

        configInput = EditText(this).apply {
            hint = "Paste V2Ray/Xray JSON config here"
            setText(configStore.loadV2RayConfig())
            minLines = 10
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(280)
            )
        }
        root.addView(configInput)

        val saveButton = Button(this).apply {
            text = "Save config"
            setOnClickListener { saveConfig(validateJson = false) }
        }
        root.addView(saveButton)

        val importButton = Button(this).apply {
            text = "Import config from pasted JSON"
            setOnClickListener { saveConfig(validateJson = true) }
        }
        root.addView(importButton)

        val connectButton = Button(this).apply {
            text = "Connect"
            setOnClickListener { connectClicked() }
        }
        root.addView(connectButton)

        val disconnectButton = Button(this).apply {
            text = "Disconnect"
            setOnClickListener {
                startVpnService(MyVpnService.ACTION_DISCONNECT)
                setStatus("VPN status: disconnected")
            }
        }
        root.addView(disconnectButton)

        val scrollView = ScrollView(this).apply {
            addView(root)
        }
        setContentView(scrollView)
    }

    private fun saveConfig(validateJson: Boolean): Boolean {
        val pastedConfig = configInput.text.toString().trim()
        if (pastedConfig.isEmpty()) {
            configInput.error = "Config cannot be empty."
            showToast("Config cannot be empty.")
            return false
        }

        if (validateJson) {
            try {
                JSONObject(pastedConfig)
            } catch (error: Exception) {
                configInput.error = "Pasted config is not valid JSON."
                showToast("Pasted config is not valid JSON: ${error.message}")
                return false
            }
        }

        configStore.saveV2RayConfig(pastedConfig)
        showToast(if (validateJson) "Imported JSON config." else "Config saved.")
        return true
    }

    private fun connectClicked() {
        if (!saveConfig(validateJson = true)) {
            setStatus("VPN status: config required")
            return
        }

        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            startActivityForResult(permissionIntent, REQUEST_VPN_PERMISSION)
        } else {
            startVpnService(MyVpnService.ACTION_CONNECT)
            setStatus("VPN status: connecting")
        }
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

    private fun setStatus(status: String) {
        statusText.text = status
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQUEST_VPN_PERMISSION = 2001
        private const val REQUEST_NOTIFICATIONS = 2002
    }
}
