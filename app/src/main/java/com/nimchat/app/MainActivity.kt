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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

@Serializable
data class Profile(val id: String, val username: String, val display_name: String? = null)

@Serializable
data class Conversation(val id: String? = null, val created_by: String)

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentUser: String? = null
    private var currentUsername = ""
    private var activeConversation: String? = null
    private var chatUser = ""
    private var messageRefreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentUser = supabase.auth.currentUserOrNull()?.id
        if (currentUser != null) loadExistingProfile() else showAuth()
    }

    override fun onDestroy() {
        messageRefreshJob?.cancel()
        scope.coroutineContext[SupervisorJob]?.cancel()
        super.onDestroy()
    }

    private fun loadExistingProfile() {
        scope.launch {
            try {
                val uid = currentUser ?: return@launch
                val profile = withTimeout(8000) {
                    supabase.from("profiles").select { filter { eq("id", uid) } }.decodeSingleOrNull<Profile>()
                }
                if (profile != null) {
                    currentUsername = profile.username
                    showHome()
                } else showAuth()
            } catch (_: Exception) {
                showAuth()
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
        messageRefreshJob?.cancel()
        val root = base().apply { gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(text("NimChat", 34f, true), lp())
        root.addView(text("پیام‌رسان واقعی با Supabase", 17f), lp(0, 12, 0, 25))
        if (error != null) root.addView(text(error, 14f).apply { setTextColor(Color.rgb(180, 40, 40)) }, lp(0, 0, 0, 15))
        val name = input("نام کاربری")
        root.addView(name, lp(-1, 0, 0, 14))
        val enter = button("ورود به NimChat")
        root.addView(enter, lp(-1, 0, 0, 10))
        enter.setOnClickListener {
            val username = name.text.toString().trim().lowercase()
            if (!username.matches(Regex("[a-z0-9_]{2,30}"))) {
                name.error = "۲ تا ۳۰ حرف انگلیسی، عدد یا _"
                return@setOnClickListener
            }
            enter.isEnabled = false
            enter.text = "در حال اتصال..."
            scope.launch {
                try {
                    var uid = currentUser
                    if (uid == null) {
                        withTimeout(10000) { supabase.auth.signInAnonymously() }
                        uid = supabase.auth.currentUserOrNull()?.id
                    }
                    if (uid == null) throw IllegalStateException("احراز هویت انجام نشد")
                    currentUser = uid
                    val existing = withTimeout(8000) {
                        supabase.from("profiles").select { filter { eq("id", uid!!) } }.decodeSingleOrNull<Profile>()
                    }
                    if (existing == null) {
                        withTimeout(8000) { supabase.from("profiles").insert(Profile(uid!!, username, username)) }
                    } else if (existing.username != username) {
                        throw IllegalStateException("این حساب قبلاً با نام کاربری ${existing.username} ثبت شده است")
                    }
                    currentUsername = username
                    showHome()
                } catch (e: Exception) {
                    enter.isEnabled = true
                    enter.text = "ورود به NimChat"
                    showAuth("ورود انجام نشد: ${friendlyError(e.message)}")
                }
            }
        }
        root.addView(text("نسخه 0.5 • اتصال واقعی به Supabase", 13f).apply { setTextColor(Color.GRAY) }, lp(0, 20, 0, 0))
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
                try { withTimeout(5000) { supabase.auth.signOut() } } catch (_: Exception) {}
                currentUser = null
                currentUsername = ""
                activeConversation = null
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
            start.isEnabled = false
            scope.launch {
                try { openConversation(username) }
                finally { start.isEnabled = true }
            }
        }
        root.addView(text("پیام‌رسانی", 18f, true), lp(0, 0, 0, 8))
        root.addView(text("پیام‌ها در دیتابیس Supabase ذخیره می‌شوند و داخل گفتگو خودکار تازه‌سازی می‌شوند.", 14f), lp())
        setContentView(root)
    }

    private suspend fun openConversation(targetUsername: String) {
        try {
            val target = withTimeout(8000) {
                supabase.from("profiles").select { filter { eq("username", targetUsername) } }.decodeSingleOrNull<Profile>()
            }
            if (target == null) {
                Toast.makeText(this, "این کاربر پیدا نشد.", Toast.LENGTH_SHORT).show()
                return
            }
            val me = currentUser ?: throw IllegalStateException("جلسه کاربر وجود ندارد")
            if (target.id == me) {
                Toast.makeText(this, "نام کاربری خودت را وارد نکن.", Toast.LENGTH_SHORT).show()
                return
            }
            val conversation = withTimeout(8000) {
                supabase.from("conversations").insert(Conversation(created_by = me)) { select() }.decodeSingle<Conversation>()
            }
            val conversationId = conversation.id ?: throw IllegalStateException("شناسه گفتگو ساخته نشد")
            withTimeout(8000) {
                supabase.from("conversation_members").insert(Member(conversationId, me))
                supabase.from("conversation_members").insert(Member(conversationId, target.id))
            }
            activeConversation = conversationId
            chatUser = target.username
            showChat()
        } catch (e: Exception) {
            Toast.makeText(this, "شروع گفتگو ناموفق بود: ${friendlyError(e.message)}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showChat() {
        val conversationId = activeConversation ?: return
        messageRefreshJob?.cancel()
        val root = base()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = button("‹")
        back.textSize = 28f
        header.addView(back, LinearLayout.LayoutParams(54, 54))
        header.addView(text(chatUser, 22f, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text("گفتگو", 13f).apply { setTextColor(Color.GRAY) })
        back.setOnClickListener { messageRefreshJob?.cancel(); showHome() }
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
            send.isEnabled = false
            scope.launch {
                try {
                    val me = currentUser ?: throw IllegalStateException("جلسه کاربر وجود ندارد")
                    val message = Message(conversation_id = conversationId, sender_id = me, body = value)
                    withTimeout(8000) { supabase.from("messages").insert(message) }
                    messageInput.text.clear()
                    refreshMessages(conversationId, list, scroll)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "ارسال ناموفق: ${friendlyError(e.message)}", Toast.LENGTH_SHORT).show()
                } finally { send.isEnabled = true }
            }
        }
        setContentView(root)
        messageRefreshJob = scope.launch {
            while (true) {
                refreshMessages(conversationId, list, scroll)
                delay(2000)
            }
        }
    }

    private suspend fun refreshMessages(conversationId: String, list: LinearLayout, scroll: ScrollView) {
        try {
            val messages = withTimeout(8000) {
                supabase.from("messages").select {
                    filter { eq("conversation_id", conversationId) }
                    order("created_at", Order.ASCENDING)
                }.decodeList<Message>()
            }
            list.removeAllViews()
            messages.forEach { addBubble(list, it.body, it.sender_id == currentUser) }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        } catch (_: Exception) {
            // A temporary network failure is retried by the next refresh cycle.
        }
    }

    private fun addBubble(parent: LinearLayout, value: String, mine: Boolean) {
        val row = LinearLayout(this).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(4, 5, 4, 5
        }
        val bubble = text(value, 16f).apply {
            setPadding(18, 12, 18, 12)
            setBackgroundColor(if (mine) Color.rgb(220, 235, 255) else Color.WHITE)
        }
        row.addView(bubble, LinearLayout.LayoutParams(-2, -2))
        parent.addView(row)
    }

    private fun friendlyError(message: String?): String {
        val m = message ?: return "خطای نامشخص"
        return when {
            m.contains("Anonymous", true) || m.contains("anonymous", true) -> "ورود مهمان در Supabase فعال نیست."
            m.contains("duplicate", true) || m.contains("unique", true) -> "این نام کاربری قبلاً ثبت شده است."
            m.contains("timeout", true) || m.contains("timed out", true) -> "اتصال به سرور زمان‌بر شد. دوباره تلاش کن."
            else -> m.take(180)
        }
    }

    private fun lp(w: Int = -1, top: Int = 0, bottom: Int = 0, left: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(left, top, 0, bottom)
        }
}
