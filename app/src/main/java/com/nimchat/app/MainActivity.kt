package com.nimchat.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("nimchat", MODE_PRIVATE) }
    private var currentUser: String? = null
    private var chatUser = "NimFriend"
    private val messages = mutableListOf("سلام! به NimChat خوش آمدی 👋")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentUser = prefs.getString("username", null)
        if (currentUser == null) showAuth() else showHome()
    }

    private fun base(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(248, 249, 251))
        setPadding(28, 28, 28, 20)
    }

    private fun text(value: String, size: Float, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(25, 28, 35))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
    }

    private fun input(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        textSize = 17f
        maxLines = 1
    }

    private fun showAuth() {
        val root = base().apply { gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(text("NimChat", 34f, true), lp())
        root.addView(text("پیام‌رسان ساده، سریع و خصوصی", 17f), lp(0, 12, 0, 30))

        val name = input("نام کاربری")
        root.addView(name, lp(-1, 0, 0, 14))

        val enter = button("ورود به NimChat")
        root.addView(enter, lp(-1, 0, 0, 10))
        enter.setOnClickListener {
            val username = name.text.toString().trim()
            if (username.length < 2) {
                name.error = "حداقل ۲ حرف وارد کن"
                return@setOnClickListener
            }
            currentUser = username
            prefs.edit().putString("username", username).apply()
            showHome()
        }
        root.addView(text("نسخه MVP • ورود محلی فعلاً بدون سرور", 13f).apply {
            setTextColor(Color.GRAY)
        }, lp(0, 20, 0, 0))
        setContentView(root)
    }

    private fun showHome() {
        val root = base()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("NimChat", 29f, true), LinearLayout.LayoutParams(0, -2, 1f))
        val logout = button("خروج")
        logout.textSize = 13f
        header.addView(logout, LinearLayout.LayoutParams(-2, -2))
        logout.setOnClickListener {
            prefs.edit().remove("username").apply()
            currentUser = null
            showAuth()
        }
        root.addView(header, lp(-1, 0, 0, 24))

        root.addView(text("سلام ${currentUser ?: "دوست"} 👋", 20f, true), lp(0, 0, 0, 20))

        val search = input("جستجوی کاربر یا گفتگو")
        root.addView(search, lp(-1, 0, 0, 18))

        root.addView(text("گفتگوها", 18f, true), lp(0, 0, 0, 10))
        val chat = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 16, 18, 16)
            setBackgroundColor(Color.WHITE)
        }
        val avatar = text("N", 22f, true).apply { gravity = Gravity.CENTER }
        chat.addView(avatar, LinearLayout.LayoutParams(52, 52))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 0, 0, 0) }
        info.addView(text(chatUser, 18f, true))
        info.addView(text(messages.lastOrNull() ?: "شروع گفتگو", 14f).apply { setTextColor(Color.DKGRAY) })
        chat.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(chat, lp(-1, 0, 0, 12))
        chat.setOnClickListener { showChat() }

        val add = button("＋  شروع گفتگوی جدید")
        root.addView(add, lp(-1, 12, 0, 0))
        add.setOnClickListener { showChat() }
        setContentView(root)
    }

    private fun showChat() {
        val root = base()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = button("‹")
        back.textSize = 28f
        header.addView(back, LinearLayout.LayoutParams(54, 54))
        header.addView(text(chatUser, 22f, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text("● آنلاین", 13f).apply { setTextColor(Color.rgb(40, 150, 80)) })
        back.setOnClickListener { showHome() }
        root.addView(header, lp(-1, 0, 0, 14))

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 8, 4, 8)
        }
        messages.forEach { addBubble(list, it, false) }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val composer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val input = input("پیام بنویس...")
        val send = button("ارسال")
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        composer.addView(send, LinearLayout.LayoutParams(-2, -2))
        root.addView(composer, lp(-1, 10, 0, 0))

        send.setOnClickListener {
            val value = input.text.toString().trim()
            if (value.isEmpty()) return@setOnClickListener
            messages.add(value)
            addBubble(list, value, true)
            input.text.clear()
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        setContentView(root)
    }

    private fun addBubble(parent: LinearLayout, value: String, mine: Boolean) {
        val row = LinearLayout(this).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(4, 5, 4, 5)
        }
        val bubble = text(value, 16f).apply {
            setPadding(18, 12, 18, 12)
            setBackgroundColor(if (mine) Color.rgb(220, 235, 255) else Color.WHITE)
        }
        row.addView(bubble, LinearLayout.LayoutParams(-2, -2))
        parent.addView(row)
    }

    private fun lp(w: Int = -1, top: Int = 0, bottom: Int = 0, left: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(left, top, 0, bottom)
        }
}
