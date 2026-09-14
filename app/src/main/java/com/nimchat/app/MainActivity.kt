package com.nimchat.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.signInAnonymously
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class Profile(val id: String, val username: String, val display_name: String? = null)

@Serializable
data class Conversation(val id: String? = null)

@Serializable
data class Member(val conversation_id: String, val user_id: String)

@Serializable
data class Message(
    val id: String? = null,
    val conversation_id: String,
    val sender_id: String,
    val body: String,
    val created_at: String? = null
)

private val supabase = createSupabaseClient(
    supabaseUrl = BuildConfig.SUPABASE_URL,
    supabaseKey = BuildConfig.SUPABASE_KEY
) {
    install(Auth)
    install(Postgrest)
    install(Realtime)
}

class MainActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var currentUser: String? = null
    private var currentUsername = ""
    private var activeConversation: String? = null
    private var chatUser = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scope.launch {
            try {
                var user = supabase.auth.currentUserOrNull()
                if (user == null) {
                    supabase.auth.signInAnonymously()
                    user = supabase.auth.currentUserOrNull()
                }
                currentUser = user?.id
                if (currentUser == null) {
                    showAuth("احراز هویت ناموفق بود")
                    return@launch
                }
                showAuth()
            } catch (e: Exception) {
                showAuth("اتصال Supabase برقرار نشد: ${e.message ?: "خطای نامشخص"}")
            }
        }
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

    private fun showAuth(error: String? = null) {
        val root = base().apply { gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(text("NimChat", 34f, true), lp())
        root.addView(text("پیام‌رسان واقعی با Supabase", 17f), lp(0, 12, 0, 25))

        if (error != null) {
            root.addView(text(error, 14f).apply { setTextColor(Color.rgb(180, 40, 40)) }, lp(0, 0, 0, 15))
        }

        val name = input("نام کاربری")
        root.addView(name, lp(-1, 0, 0, 14))
        val enter = button("ورود به NimChat")
        root.addView(enter, lp(-1, 0, 0, 10))

        enter.setOnClickListener {
            val username = name.text.toString().trim().lowercase()
            if (username.length < 2) {
                name.error = "حداقل ۲ حرف وارد کن"
                return@setOnClickListener
            }
            scope.launch {
                try {
                    val uid = currentUser ?: return@launch
                    supabase.from("profiles").insert(Profile(uid, username, username))
                    currentUsername = username
                    showHome()
                } catch (e: Exception) {
                    // Existing profile is fine; any other error is shown.
                    try {
                        val profile = supabase.from("profiles").select {
                            filter { eq("id", currentUser ?: "") }
                        }.decodeSingle<Profile>()
                        currentUsername = profile.username
                        showHome()
                    } catch (_: Exception) {
                        name.error = e.message ?: "ثبت نام کاربر ناموفق بود"
                    }
                }
            }
        }
        root.addView(text("نسخه 0.3 • اتصال واقعی به Supabase", 13f).apply { setTextColor(Color.GRAY) }, lp(0, 20, 0, 0))
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
            scope.launch {
                supabase.auth.signOut()
                currentUser = null
                currentUsername = ""
                showAuth()
            }
        }
        root.addView(header, lp(-1, 0, 0, 24))
        root.addView(text("سلام $currentUsername 👋", 20f, true), lp(0, 0, 0, 20))

        val search = input("نام کاربری برای شروع گفتگو")
        root.addView(search, lp(-1, 0, 0, 12))
        val start = button("＋  شروع گفتگوی واقعی")
        root.addView(start, lp(-1, 0, 0, 18))
        start.setOnClickListener {
            val username = search.text.toString().trim().lowercase()
            if (username.isEmpty()) {
                search.error = "نام کاربری را وارد کن"
                return@setOnClickListener
            }
            scope.launch { openConversation(username) }
        }

        root.addView(text("پیام‌رسانی", 18f, true), lp(0, 0, 0, 8))
        root.addView(text("پیام‌ها اکنون در دیتابیس Supabase ذخیره می‌شوند.", 14f), lp())
        setContentView(root)
    }

    private suspend fun openConversation(targetUsername: String) {
        try {
            val target = supabase.from("profiles").select {
                filter { eq("username", targetUsername) }
            }.decodeSingleOrNull<Profile>()

            if (target == null) {
                Toast.makeText(this, "این کاربر پیدا نشد.", Toast.LENGTH_SHORT).show()
                return
            }
            if (target.id == currentUser) {
                Toast.makeText(this, "نام کاربری خودت را وارد نکن.", Toast.LENGTH_SHORT).show()
                return
            }

            val conversation = supabase.from("conversations").insert(Conversation()) { select() }.decodeSingle<Conversation>()
            val conversationId = conversation.id ?: return
            supabase.from("conversation_members").insert(Member(conversationId, currentUser!!))
            supabase.from("conversation_members").insert(Member(conversationId, target.id))
            activeConversation = conversationId
            chatUser = target.username
            showChat()
        } catch (e: Exception) {
            Toast.makeText(this, "شروع گفتگو ناموفق بود: ${e.message ?: "خطا"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showChat() {
        val conversationId = activeConversation ?: return
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
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val composer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val messageInput = input("پیام بنویس...")
        val send = button("ارسال")
        composer.addView(messageInput, LinearLayout.LayoutParams(0, -2, 1f))
        composer.addView(send, LinearLayout.LayoutParams(-2, -2))
        root.addView(composer, lp(-1, 10, 0, 0))

        send.setOnClickListener {
            val value = messageInput.text.toString().trim()
            if (value.isEmpty()) return@setOnClickListener
            scope.launch {
                try {
                    val message = Message(conversation_id = conversationId, sender_id = currentUser!!, body = value)
                    supabase.from("messages").insert(message)
                    addBubble(list, value, true)
                    messageInput.text.clear()
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "ارسال ناموفق: ${e.message ?: "خطا"}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        setContentView(root)
        scope.launch {
            try {
                val messages = supabase.from("messages").select {
                    filter { eq("conversation_id", conversationId) }
                    order("created_at", Order.ASCENDING)
                }.decodeList<Message>()
                messages.forEach { addBubble(list, it.body, it.sender_id == currentUser) }
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "خواندن پیام‌ها ناموفق بود", Toast.LENGTH_SHORT).show()
            }
        }
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
