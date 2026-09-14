package com.nimchat.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.signInAnonymously
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class Profile(val id: String, val username: String, val display_name: String? = null)

@Serializable
data class Message(
    val id: String? = null,
    val conversation_id: String,
    val sender_id: String,
    val body: String,
    val created_at: String? = null
)

@Serializable
data class CreateConversationParams(@SerialName("target_user") val targetUser: String)

@Serializable
data class ConversationSummary(
    val conversation_id: String,
    val other_user_id: String,
    val other_username: String,
    val other_display_name: String? = null,
    val last_message: String? = null,
    val last_message_at: String? = null
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
    private var realtimeJob: Job? = null
    private var fallbackRefreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentUser = supabase.auth.currentUserOrNull()?.id
        if (currentUser == null) showAuth() else loadExistingProfile()
    }

    override fun onDestroy() {
        stopChatRealtime()
        scope.cancel()
        try { supabase.realtime.removeAllChannels() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun base(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(248, 249, 251))
        setPadding(24, 24, 24, 18)
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
        textSize = 15f
    }

    private fun input(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        textSize = 17f
        maxLines = 1
        imeOptions = EditorInfo.IME_ACTION_DONE
    }

    private fun showAuth(error: String? = null) {
        stopChatRealtime()
        val root = base().apply { gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(text("NimChat", 34f, true), lp())
        root.addView(text("پیام‌رسان سریع و ساده", 17f), lp(0, 10, 0, 22))
        if (error != null) {
            root.addView(text(error, 14f).apply { setTextColor(Color.rgb(180, 40, 40)) }, lp(0, 0, 0, 14))
        }
        val name = input("نام کاربری (a-z, 0-9, _)")
        root.addView(name, lp(-1, 0, 0, 12))
        val enter = button("ورود به NimChat")
        root.addView(enter, lp(-1, 0, 0, 8))
        val help = text("این نسخه برای ساخت حساب اولیه از ورود مهمان Supabase استفاده می‌کند.\nاگر ورود مهمان غیرفعال باشد، خطای دقیق همین‌جا نمایش داده می‌شود.", 12f)
        help.setTextColor(Color.DKGRAY)
        root.addView(help, lp(0, 10, 0, 0))

        enter.setOnClickListener {
            val username = name.text.toString().trim().lowercase(Locale.ROOT)
            if (!username.matches(Regex("[a-z0-9_]{2,30}"))) {
                name.error = "۲ تا ۳۰ حرف انگلیسی، عدد یا _"
                return@setOnClickListener
            }
            enter.isEnabled = false
            enter.text = "در حال ورود..."
            scope.launch {
                try {
                    val uid = ensureAuthenticated()
                    val existing = withTimeout(10000) {
                        supabase.from("profiles").select { filter { eq("id", uid) } }.decodeSingleOrNull<Profile>()
                    }
                    if (existing == null) {
                        withTimeout(10000) {
                            supabase.from("profiles").insert(Profile(uid, username, username))
                        }
                    } else if (existing.username != username) {
                        throw IllegalStateException("این حساب قبلاً با نام کاربری ${existing.username} ثبت شده است")
                    }
                    currentUser = uid
                    currentUsername = username
                    showHome()
                } catch (e: Exception) {
                    enter.isEnabled = true
                    enter.text = "ورود به NimChat"
                    showAuth("ورود ناموفق: ${friendlyError(e)}")
                }
            }
        }
        setContentView(root)
    }

    private suspend fun ensureAuthenticated(): String {
        val cached = supabase.auth.currentUserOrNull()?.id
        if (cached != null) {
            currentUser = cached
            return cached
        }
        withTimeout(12000) { supabase.auth.signInAnonymously() }
        return supabase.auth.currentUserOrNull()?.id
            ?: throw IllegalStateException("Supabase احراز هویت را انجام نداد")
    }

    private fun loadExistingProfile() {
        scope.launch {
            try {
                val uid = currentUser ?: throw IllegalStateException("جلسه کاربر پیدا نشد")
                val profile = withTimeout(10000) {
                    supabase.from("profiles").select { filter { eq("id", uid) } }.decodeSingleOrNull<Profile>()
                }
                if (profile == null) {
                    currentUser = null
                    showAuth("جلسه قبلی پیدا شد، اما پروفایل کامل نیست. نام کاربری را دوباره انتخاب کن.")
                } else {
                    currentUsername = profile.username
                    showHome()
                }
            } catch (e: Exception) {
                showAuth("بررسی جلسه ناموفق: ${friendlyError(e)}")
            }
        }
    }

    private fun showHome(error: String? = null) {
        stopChatRealtime()
        val root = base()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("NimChat", 29f, true), LinearLayout.LayoutParams(0, -2, 1f))
        val logout = button("خروج")
        logout.textSize = 13f
        header.addView(logout, LinearLayout.LayoutParams(-2, -2))
        logout.setOnClickListener {
            Toast.makeText(this, "حساب مهمان با خروج قابل بازیابی در دستگاه دیگر نیست.", Toast.LENGTH_LONG).show()
            scope.launch {
                try { withTimeout(5000) { supabase.auth.signOut() } } catch (_: Exception) {}
                currentUser = null
                currentUsername = ""
                activeConversation = null
                showAuth()
            }
        }
        root.addView(header, lp(-1, 0, 0, 16))
        root.addView(text("سلام $currentUsername 👋", 20f, true), lp(0, 0, 0, 14))
        if (error != null) root.addView(text(error, 14f).apply { setTextColor(Color.rgb(180, 40, 40)) }, lp(0, 0, 0, 10))

        val search = input("نام کاربری برای شروع گفتگو")
        root.addView(search, lp(-1, 0, 0, 10))
        val start = button("＋ شروع گفتگوی جدید")
        root.addView(start, lp(-1, 0, 0, 18))
        start.setOnClickListener {
            val username = search.text.toString().trim().lowercase(Locale.ROOT)
            if (!username.matches(Regex("[a-z0-9_]{2,30}"))) {
                search.error = "نام کاربری معتبر نیست"
                return@setOnClickListener
            }
            start.isEnabled = false
            scope.launch {
                try {
                    val target = withTimeout(10000) {
                        supabase.from("profiles").select { filter { eq("username", username) } }.decodeSingleOrNull<Profile>()
                    } ?: throw IllegalStateException("کاربری با این نام پیدا نشد")
                    val me = currentUser ?: throw IllegalStateException("جلسه کاربر وجود ندارد")
                    if (target.id == me) throw IllegalStateException("نمی‌توانی با خودت گفتگو بسازی")
                    val id = withTimeout(10000) {
                        supabase.postgrest.rpc("create_direct_conversation", CreateConversationParams(target.id)).decodeSingle<String>()
                    }
                    activeConversation = id
                    chatUser = target.username
                    showChat()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, friendlyError(e), Toast.LENGTH_LONG).show()
                } finally { start.isEnabled = true }
            }
        }

        root.addView(text("گفتگوهای من", 19f, true), lp(0, 0, 0, 8))
        val listScroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listScroll.addView(list)
        root.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        scope.launch { loadConversations(list) }
    }

    private suspend fun loadConversations(list: LinearLayout) {
        try {
            val rows = withTimeout(12000) {
                supabase.postgrest.rpc("list_my_conversations").decodeList<ConversationSummary>()
            }
            list.removeAllViews()
            if (rows.isEmpty()) {
                list.addView(text("هنوز گفتگویی نداری.\nبالا نام کاربری یک نفر را وارد کن.", 15f).apply {
                    setTextColor(Color.GRAY)
                    setPadding(8, 22, 8, 22)
                })
                return
            }
            rows.forEach { row ->
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(14, 14, 14, 14)
                    setBackgroundColor(Color.WHITE)
                    isClickable = true
                }
                item.addView(text(row.other_display_name?.takeIf { it.isNotBlank() } ?: row.other_username, 17f, true))
                val preview = row.last_message?.replace("\n", " ") ?: "هنوز پیامی ارسال نشده"
                item.addView(text(preview.take(80), 14f).apply { setTextColor(Color.DKGRAY) }, lp(-1, 4, 0, 0))
                row.last_message_at?.let {
                    item.addView(text(formatTime(it), 11f).apply { setTextColor(Color.GRAY) }, lp(-1, 4, 0, 0))
                }
                item.setOnClickListener {
                    activeConversation = row.conversation_id
                    chatUser = row.other_username
                    showChat()
                }
                list.addView(item, lp(-1, 0, 8, 0))
            }
        } catch (e: Exception) {
            list.removeAllViews()
            list.addView(text("بارگذاری گفتگوها ناموفق بود:\n${friendlyError(e)}", 14f).apply {
                setTextColor(Color.rgb(180, 40, 40))
            })
        }
    }

    private fun showChat() {
        val conversationId = activeConversation ?: return
        stopChatRealtime()
        val root = base()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = button("‹")
        back.textSize = 28f
        header.addView(back, LinearLayout.LayoutParams(54, 54))
        header.addView(text(chatUser, 22f, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text("گفتگو", 12f).apply { setTextColor(Color.GRAY) })
        back.setOnClickListener { showHome() }
        root.addView(header, lp(-1, 0, 0, 10))

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 8, 4, 8)
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val composer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val messageInput = input("پیام بنویس...").apply { maxLines = 4; minLines = 1 }
        val send = button("ارسال")
        composer.addView(messageInput, LinearLayout.LayoutParams(0, -2, 1f))
        composer.addView(send, LinearLayout.LayoutParams(-2, -2))
        root.addView(composer, lp(-1, 8, 0, 0))
        setContentView(root)

        send.setOnClickListener {
            sendMessage(conversationId, messageInput, send, list, scroll)
        }
        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                send.performClick()
                true
            } else false
        }

        scope.launch { refreshMessages(conversationId, list, scroll) }
        startChatRealtime(conversationId, list, scroll)
    }

    private fun sendMessage(
        conversationId: String,
        input: EditText,
        send: Button,
        list: LinearLayout,
        scroll: ScrollView
    ) {
        val value = input.text.toString().trim()
        if (value.isEmpty()) return
        if (value.length > 4000) {
            input.error = "حداکثر ۴۰۰۰ کاراکتر"
            return
        }
        send.isEnabled = false
        scope.launch {
            try {
                val me = currentUser ?: throw IllegalStateException("جلسه کاربر وجود ندارد")
                withTimeout(10000) {
                    supabase.from("messages").insert(Message(conversation_id = conversationId, sender_id = me, body = value))
                }
                input.text.clear()
                refreshMessages(conversationId, list, scroll)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "ارسال ناموفق: ${friendlyError(e)}", Toast.LENGTH_LONG).show()
            } finally { send.isEnabled = true }
        }
    }

    private fun startChatRealtime(conversationId: String, list: LinearLayout, scroll: ScrollView) {
        realtimeJob = scope.launch {
            try {
                supabase.realtime.connect()
                val channel = supabase.realtime.createChannel("messages-$conversationId")
                val changes = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                    table = "messages"
                    filter = "conversation_id=eq.$conversationId"
                }
                channel.subscribe()
                changes.collect {
                    refreshMessages(conversationId, list, scroll)
                }
            } catch (_: Exception) {
                // Polling fallback below remains active if Realtime is unavailable.
            }
        }
        fallbackRefreshJob = scope.launch {
            while (true) {
                delay(6000)
                refreshMessages(conversationId, list, scroll)
            }
        }
    }

    private fun stopChatRealtime() {
        realtimeJob?.cancel()
        fallbackRefreshJob?.cancel()
        realtimeJob = null
        fallbackRefreshJob = null
        try { supabase.realtime.removeAllChannels() } catch (_: Exception) {}
    }

    private suspend fun refreshMessages(conversationId: String, list: LinearLayout, scroll: ScrollView) {
        try {
            val messages = withTimeout(10000) {
                supabase.from("messages").select {
                    filter { eq("conversation_id", conversationId) }
                    order("created_at", Order.ASCENDING)
                }.decodeList<Message>()
            }
            list.removeAllViews()
            messages.forEach { message -> addBubble(list, message) }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        } catch (_: Exception) {
            // Realtime/polling will retry. Do not destroy the chat UI on transient failure.
        }
    }

    private fun addBubble(parent: LinearLayout, message: Message) {
        val mine = message.sender_id == currentUser
        val row = LinearLayout(this).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(4, 4, 4, 4)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 10, 16, 8)
            setBackgroundColor(if (mine) Color.rgb(220, 235, 255) else Color.WHITE)
        }
        box.addView(text(message.body, 16f))
        val time = text(formatTime(message.created_at), 10f).apply { setTextColor(Color.GRAY); gravity = Gravity.END }
        box.addView(time, lp(-1, 3, 0, 0))
        row.addView(box, LinearLayout.LayoutParams(-2, -2))
        parent.addView(row)
    }

    private fun formatTime(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return try {
            val source = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", Locale.US)
            val date: Date = source.parse(value) ?: return value.take(16)
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        } catch (_: Exception) {
            value.replace("T", " ").take(16)
        }
    }

    private fun friendlyError(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("Anonymous", true) && (raw.contains("disabled", true) || raw.contains("not enabled", true)) ->
                "ورود مهمان Supabase فعال نیست. تا فعال نشود، ورود کار نمی‌کند."
            raw.contains("invalid_target", true) -> "کاربر مقصد معتبر نیست."
            raw.contains("user_not_found", true) -> "این کاربر پیدا نشد."
            raw.contains("not_authenticated", true) -> "جلسه احراز هویت وجود ندارد. دوباره وارد شو."
            raw.contains("duplicate", true) || raw.contains("unique", true) -> "این نام کاربری قبلاً ثبت شده است."
            raw.contains("timeout", true) || raw.contains("timed out", true) -> "اتصال به سرور زمان‌بر شد. دوباره تلاش کن."
            raw.isBlank() -> error.javaClass.simpleName
            else -> raw.replace("\n", " ").take(220)
        }
    }

    private fun lp(w: Int = -1, top: Int = 0, bottom: Int = 0, left: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(left, top, 0, bottom)
        }
}
