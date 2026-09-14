package com.nimchat.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "NimChat"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
        }

        val subtitle = TextView(this).apply {
            text = "Online chat • Android"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setPadding(0, 16, 0, 0)
        }

        root.addView(title)
        root.addView(subtitle)
        setContentView(root)
    }
}
