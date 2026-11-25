package com.zx.ffhs

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {
    private lateinit var log: TextView
    private lateinit var set: PatchSet
    private val REQ = 41

    private val listener = Shizuku.OnRequestPermissionResultListener { id, res ->
        if (id == REQ) log("shizuku: " + if (res == PackageManager.PERMISSION_GRANTED) "granted" else "denied")
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        set = PatchEngine.load(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        log = TextView(this).apply { textSize = 13f }
        root.addView(btn("1. Shizuku connect") { Shizuku.requestPermission(REQ) })
        root.addView(btn("2. Overlay permission") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(btn("3. Start panel") {
            startForegroundService(Intent(this, OverlayService::class.java))
        })
        root.addView(btn("Probe") { run("probe") })
        root.addView(btn("Apply") { run("apply") })
        root.addView(btn("Restore") { run("restore") })
        root.addView(log)
        setContentView(ScrollView(this).apply { addView(root) })
        log("game version: " + (PatchEngine.installedVersion(this) ?: "not installed"))
        log("set version: " + set.gameVersion)
        log("strings: " + set.strings.count { it.enabled } + " / bytes: " + set.entries.count { it.enabled })
    }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { f() }
    }

    private fun run(action: String) {
        val v = PatchEngine.installedVersion(this) ?: return toast("FF Max installed nahi hai")
        if (action == "apply" && v != set.gameVersion)
            log("WARN: version mismatch game=$v set=${set.gameVersion}")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
            return toast("pehle Shizuku permission do")
        Thread {
            val r = PatchEngine.run(this, action, set)
            runOnUiThread { log("[$action] $r") }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun log(s: String) = runOnUiThread { log.append(s + "\n") }

    override fun onStart() { super.onStart(); Shizuku.addRequestPermissionResultListener(listener) }
    override fun onStop() { super.onStop(); Shizuku.removeRequestPermissionResultListener(listener) }
}
