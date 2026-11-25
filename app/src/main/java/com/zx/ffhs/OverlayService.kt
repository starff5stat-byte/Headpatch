package com.zx.ffhs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var bubble: TextView
    private lateinit var menu: LinearLayout
    private lateinit var set: PatchSet
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        set = PatchEngine.load(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val lp = params()

        bubble = TextView(this).apply {
            text = "P"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(40, 20, 40, 20)
            setBackgroundColor(Color.parseColor("#CC1A1A1A"))
            setOnClickListener { toggle() }
            setOnTouchListener(drag(lp))
        }
        wm.addView(bubble, lp)

        menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E6101010"))
            setPadding(32, 24, 32, 24)
            visibility = View.GONE
        }

        val sw = Switch(this).apply {
            text = "Headshot 100%"
            setTextColor(Color.WHITE)
            isChecked = true
            setOnCheckedChangeListener { _, on ->
                Thread {
                    val r = PatchEngine.run(this@OverlayService, if (on) "apply" else "restore", set)
                    main.post { Toast.makeText(this@OverlayService, r.take(80), Toast.LENGTH_SHORT).show() }
                }.start()
            }
        }
        menu.addView(sw)
        menu.addView(row("Restore") {
            Thread { PatchEngine.run(this@OverlayService, "restore", set) }.start()
        })
        menu.addView(row("Exit") { stopSelf() })

        wm.addView(menu, params().apply { x = 40; y = 300 })
    }

    private fun row(t: String, f: () -> Unit) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 15f
        setPadding(0, 28, 0, 28)
        setOnClickListener { f() }
    }

    private fun toggle() {
        menu.visibility = if (menu.visibility == View.GONE) View.VISIBLE else View.GONE
    }

    private fun drag(lp: WindowManager.LayoutParams) = object : View.OnTouchListener {
        var dx = 0; var dy = 0; var t = 0L
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dx = lp.x - e.rawX.toInt(); dy = lp.y - e.rawY.toInt()
                    t = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = e.rawX.toInt() + dx; lp.y = e.rawY.toInt() + dy
                    wm.updateViewLayout(bubble, lp)
                }
                MotionEvent.ACTION_UP ->
                    if (System.currentTimeMillis() - t < 200) v.performClick()
            }
            return true
        }
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 300 }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel("p", "p", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, "p")
            .setContentTitle("Panel")
            .setContentText("chal raha hai")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, n)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { wm.removeView(bubble); wm.removeView(menu) }
    }
}
