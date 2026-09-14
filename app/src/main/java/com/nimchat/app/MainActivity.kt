package com.nimchat.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.signInAnonymously
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class Profile(val id: String, val username: String, val display_name: String? = null)
@Serializable
data class Message(val id: String? = null, val conversation_id: String, val sender_id: String, val body: String, val created_at: String? = null, val updated_at: String? = null)
@Serializable
data class CreateConversationParams(@SerialName("target_user") val targetUser: String)
@Serializable
data class ConversationSummary(val conversation_id: String, val other_user_id: String, val other_username: String, val other_display_name: String? = null, val last_message: String? = null, val last_message_at: String? = null)

private val supabase = createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
    install(Auth); install(Postgrest); install(Realtime)
}

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentUser: String? = null
    private var currentUsername = ""
    private var activeConversation: String? = null
    private var chatUser = ""
    private var chatList: LinearLayout? = null
    private var chatScroll: ScrollView? = null
    private var realtimeJob: Job? = null
    private var fallbackRefreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentUser = supabase.auth.currentUserOrNull()?.id
        if (currentUser == null) showAuth() else loadExistingProfile()
    }
    override fun onDestroy() {
        stopChatRealtime(); scope.cancel()
        try { supabase.realtime.removeAllChannels() } catch (_: Exception) {}
        super.onDestroy()
    }
    private fun base() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(248,249,251)); setPadding(24,24,24,18) }
    private fun text(v:String,s:Float,b:Boolean=false)=TextView(this).apply{text=v;textSize=s;setTextColor(Color.rgb(25,28,35));if(b)typeface=Typeface.DEFAULT_BOLD}
    private fun button(v:String)=Button(this).apply{text=v;isAllCaps=false;textSize=15f}
    private fun input(h:String)=EditText(this).apply{hint=h;textSize=17f;maxLines=1;imeOptions=EditorInfo.IME_ACTION_DONE}

    private fun showAuth(error:String?=null){
        stopChatRealtime();val root=base().apply{gravity=Gravity.CENTER_HORIZONTAL};root.addView(text("NimChat",34f,true),lp());root.addView(text("پیام‌رسان سریع و ساده",17f),lp(0,10,0,22))
        if(error!=null)root.addView(text(error,14f).apply{setTextColor(Color.rgb(180,40,40))},lp(0,0,0,14));val name=input("نام کاربری (a-z, 0-9, _)");root.addView(name,lp(-1,0,0,12));val enter=button("ورود به NimChat");root.addView(enter,lp(-1,0,0,8));setContentView(root)
        enter.setOnClickListener{val username=name.text.toString().trim().lowercase(Locale.ROOT);if(!username.matches(Regex("[a-z0-9_]{2,30}"))){name.error="۲ تا ۳۰ حرف انگلیسی، عدد یا _";return@setOnClickListener};enter.isEnabled=false;enter.text="در حال ورود...";scope.launch{try{val uid=ensureAuthenticated();val existing=withTimeout(10000){supabase.from("profiles").select{filter{eq("id",uid)}}.decodeSingleOrNull<Profile>()};if(existing==null)withTimeout(10000){supabase.from("profiles").insert(Profile(uid,username,username))}else if(existing.username!=username)throw IllegalStateException("این حساب قبلاً با نام کاربری ${existing.username} ثبت شده است");currentUser=uid;currentUsername=username;showHome()}catch(e:Exception){enter.isEnabled=true;enter.text="ورود به NimChat";showAuth("ورود ناموفق: ${friendlyError(e)}")}}}
    }
    private suspend fun ensureAuthenticated():String{val cached=supabase.auth.currentUserOrNull()?.id;if(cached!=null){currentUser=cached;return cached};withTimeout(12000){supabase.auth.signInAnonymously()};return supabase.auth.currentUserOrNull()?.id?:error("Supabase احراز هویت را انجام نداد")}
    private fun loadExistingProfile()=scope.launch{try{val uid=currentUser?:error("جلسه کاربر پیدا نشد");val p=withTimeout(10000){supabase.from("profiles").select{filter{eq("id",uid)}}.decodeSingleOrNull<Profile>()};if(p==null){currentUser=null;showAuth("پروفایل کامل نیست. نام کاربری را دوباره انتخاب کن.")}else{currentUsername=p.username;showHome()}}catch(e:Exception){showAuth("بررسی جلسه ناموفق: ${friendlyError(e)}")}}

    private fun showHome(error:String?=null){
        stopChatRealtime();val root=base();val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};header.addView(text("NimChat",29f,true),LinearLayout.LayoutParams(0,-2,1f));val logout=button("خروج");logout.textSize=13f;header.addView(logout);logout.setOnClickListener{scope.launch{try{withTimeout(5000){supabase.auth.signOut()}}catch(_:Exception){};currentUser=null;currentUsername="";activeConversation=null;showAuth()}};root.addView(header,lp(-1,0,0,16));root.addView(text("سلام $currentUsername 👋",20f,true),lp(0,0,0,14));if(error!=null)root.addView(text(error,14f).apply{setTextColor(Color.rgb(180,40,40))},lp(0,0,0,10))
        val search=input("نام کاربری برای شروع گفتگو");root.addView(search,lp(-1,0,0,10));val start=button("＋ شروع گفتگوی جدید");root.addView(start,lp(-1,0,0,18));start.setOnClickListener{val username=search.text.toString().trim().lowercase(Locale.ROOT);if(!username.matches(Regex("[a-z0-9_]{2,30}"))){search.error="نام کاربری معتبر نیست";return@setOnClickListener};start.isEnabled=false;scope.launch{try{val target=withTimeout(10000){supabase.from("profiles").select{filter{eq("username",username)}}.decodeSingleOrNull<Profile>()}?:error("کاربری با این نام پیدا نشد");val me=currentUser?:error("جلسه کاربر وجود ندارد");if(target.id==me)error("نمی‌توانی با خودت گفتگو بسازی");val id=withTimeout(10000){supabase.postgrest.rpc("create_direct_conversation",CreateConversationParams(target.id)).decodeAs<String>()};activeConversation=id;chatUser=target.username;showChat()}catch(e:Exception){Toast.makeText(this@MainActivity,friendlyError(e),Toast.LENGTH_LONG).show()}finally{start.isEnabled=true}}}
        root.addView(text("گفتگوهای من",19f,true),lp(0,0,0,8));val scroll=ScrollView(this);val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root);scope.launch{loadConversations(list)}
    }
    private suspend fun loadConversations(list:LinearLayout){try{val rows=withTimeout(12000){supabase.postgrest.rpc("list_my_conversations").decodeList<ConversationSummary>()};list.removeAllViews();if(rows.isEmpty()){list.addView(text("هنوز گفتگویی نداری.\nبالا نام کاربری یک نفر را وارد کن.",15f).apply{setTextColor(Color.GRAY);setPadding(8,22,8,22)});return};rows.forEach{row->val item=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(14,14,14,14);setBackgroundColor(Color.WHITE);isClickable=true};item.addView(text(row.other_display_name?.takeIf{it.isNotBlank()}?:row.other_username,17f,true));item.addView(text(row.last_message?.replace("\n"," ")?.take(80)?:"هنوز پیامی ارسال نشده",14f).apply{setTextColor(Color.DKGRAY)},lp(-1,4,0,0));row.last_message_at?.let{item.addView(text(formatTime(it),11f).apply{setTextColor(Color.GRAY)},lp(-1,4,0,0))};item.setOnClickListener{activeConversation=row.conversation_id;chatUser=row.other_username;showChat()};list.addView(item,lp(-1,0,8,0))}}catch(e:Exception){list.removeAllViews();list.addView(text("بارگذاری گفتگوها ناموفق بود:\n${friendlyError(e)}",14f).apply{setTextColor(Color.rgb(180,40,40))})}}

    private fun showChat(){val cid=activeConversation?:return;stopChatRealtime();val root=base();val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};val back=button("‹");back.textSize=28f;header.addView(back,LinearLayout.LayoutParams(54,54));header.addView(text(chatUser,22f,true),LinearLayout.LayoutParams(0,-2,1f));header.addView(text("گفتگو",12f).apply{setTextColor(Color.GRAY)});back.setOnClickListener{showHome()};root.addView(header,lp(-1,0,0,10));val scroll=ScrollView(this);val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(4,8,4,8)};scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));chatList=list;chatScroll=scroll;val composer=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};val messageInput=input("پیام بنویس...").apply{maxLines=4;minLines=1};val send=button("ارسال");composer.addView(messageInput,LinearLayout.LayoutParams(0,-2,1f));composer.addView(send,LinearLayout.LayoutParams(-2,-2));root.addView(composer,lp(-1,8,0,0));setContentView(root);send.setOnClickListener{sendMessage(cid,messageInput,send)};messageInput.setOnEditorActionListener{_,id,_->if(id==EditorInfo.IME_ACTION_DONE){send.performClick();true}else false};scope.launch{refreshMessages(cid)};startChatRealtime(cid)}
    private fun sendMessage(cid:String,input:EditText,send:Button){val value=input.text.toString().trim();if(value.isEmpty())return;if(value.length>4000){input.error="حداکثر ۴۰۰۰ کاراکتر";return};send.isEnabled=false;scope.launch{try{val me=currentUser?:error("جلسه کاربر وجود ندارد");withTimeout(10000){supabase.from("messages").insert(Message(conversation_id=cid,sender_id=me,body=value))};input.text.clear();refreshMessages(cid)}catch(e:Exception){Toast.makeText(this@MainActivity,"ارسال ناموفق: ${friendlyError(e)}",Toast.LENGTH_LONG).show()}finally{send.isEnabled=true}}}
    private fun startChatRealtime(cid:String){
        realtimeJob=scope.launch{
            try{
                supabase.realtime.connect()
                val ch=supabase.realtime.channel("messages-$cid")
                val inserts=ch.postgresChangeFlow<PostgresAction.Insert>(schema="public"){table="messages"}
                val updates=ch.postgresChangeFlow<PostgresAction.Update>(schema="public"){table="messages"}
                val deletes=ch.postgresChangeFlow<PostgresAction.Delete>(schema="public"){table="messages"}
                ch.subscribe()
                merge(inserts,updates,deletes).collect{refreshMessages(cid)}
            }catch(_:Exception){}
        }
        fallbackRefreshJob=scope.launch{while(true){delay(6000);refreshMessages(cid)}}
    }
    private fun stopChatRealtime(){realtimeJob?.cancel();fallbackRefreshJob?.cancel();realtimeJob=null;fallbackRefreshJob=null;chatList=null;chatScroll=null;try{supabase.realtime.removeAllChannels()}catch(_:Exception){}}
    private suspend fun refreshMessages(cid:String){try{val messages=withTimeout(10000){supabase.from("messages").select{filter{eq("conversation_id",cid)};order("created_at",Order.ASCENDING)}.decodeList<Message>()};val list=chatList?:return;list.removeAllViews();messages.forEach{addBubble(list,it)};chatScroll?.post{chatScroll?.fullScroll(View.FOCUS_DOWN)}}catch(_:Exception){}}

    private fun addBubble(parent:LinearLayout,message:Message){val mine=message.sender_id==currentUser;val row=LinearLayout(this).apply{gravity=if(mine)Gravity.END else Gravity.START;setPadding(4,4,4,4)};val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,10,16,8);setBackgroundColor(if(mine)Color.rgb(220,235,255)else Color.WHITE)};box.addView(text(message.body,16f));val meta=LinearLayout(this).apply{gravity=Gravity.END};meta.addView(text(formatTime(message.created_at),10f).apply{setTextColor(Color.GRAY)});if(mine){if(message.updated_at!=null&&message.created_at!=null&&message.updated_at!=message.created_at)meta.addView(text("  ویرایش‌شده",9f).apply{setTextColor(Color.GRAY)});val actions=button("⋮");actions.textSize=18f;actions.setPadding(2,0,2,0);actions.setOnClickListener{showMessageActions(message)};meta.addView(actions,LinearLayout.LayoutParams(42,40))};box.addView(meta,lp(-1,3,0,0));row.addView(box,LinearLayout.LayoutParams(-2,-2));parent.addView(row)}
    private fun showMessageActions(message:Message){AlertDialog.Builder(this).setItems(arrayOf("ویرایش پیام","حذف پیام","لغو")){_,which->when(which){0->showEditDialog(message);1->confirmDelete(message)}}.show()}
    private fun showEditDialog(message:Message){val field=input("متن پیام").apply{setText(message.body);setSelection(text.length);maxLines=6};AlertDialog.Builder(this).setTitle("ویرایش پیام").setView(field).setNegativeButton("لغو",null).setPositiveButton("ذخیره"){_,_->updateMessage(message.id,field.text.toString().trim())}.show()}
    private fun updateMessage(id:String?,body:String){if(id==null||body.isEmpty())return;if(body.length>4000){Toast.makeText(this@MainActivity,"حداکثر ۴۰۰۰ کاراکتر",Toast.LENGTH_LONG).show();return};scope.launch{try{withTimeout(10000){supabase.from("messages").update({set("body",body)}){filter{eq("id",id)}}};activeConversation?.let{refreshMessages(it)}}catch(e:Exception){Toast.makeText(this@MainActivity,"ویرایش ناموفق: ${friendlyError(e)}",Toast.LENGTH_LONG).show()}}}
    private fun confirmDelete(message:Message){AlertDialog.Builder(this).setTitle("حذف پیام؟").setMessage("این پیام برای همیشه حذف می‌شود.").setNegativeButton("لغو",null).setPositiveButton("حذف"){_,_->deleteMessage(message.id)}.show()}
    private fun deleteMessage(id:String?){if(id==null)return;scope.launch{try{withTimeout(10000){supabase.from("messages").delete{filter{eq("id",id)}}};activeConversation?.let{refreshMessages(it)}}catch(e:Exception){Toast.makeText(this@MainActivity,"حذف ناموفق: ${friendlyError(e)}",Toast.LENGTH_LONG).show()}}}

    private fun formatTime(value:String?):String{if(value.isNullOrBlank())return "";return try{val source=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",Locale.US);val date:Date=source.parse(value)?:return value.take(16);SimpleDateFormat("HH:mm",Locale.getDefault()).format(date)}catch(_:Exception){value.replace("T"," ").take(16)}}
    private fun friendlyError(error:Throwable):String{val raw=error.message.orEmpty();return when{raw.contains("Anonymous",true)&&(raw.contains("disabled",true)||raw.contains("not enabled",true))->"ورود مهمان Supabase فعال نیست.";raw.contains("invalid_target",true)->"کاربر مقصد معتبر نیست.";raw.contains("user_not_found",true)->"این کاربر پیدا نشد.";raw.contains("not_authenticated",true)->"جلسه احراز هویت وجود ندارد.";raw.contains("duplicate",true)||raw.contains("unique",true)->"این نام کاربری قبلاً ثبت شده است.";raw.contains("timeout",true)||raw.contains("timed out",true)->"اتصال به سرور زمان‌بر شد.";raw.isBlank()->error.javaClass.simpleName;else->raw.replace("\n"," ").take(220)}}
    private fun lp(w:Int=-1,top:Int=0,bottom:Int=0,left:Int=0)=LinearLayout.LayoutParams(w,LinearLayout.LayoutParams.WRAP_CONTENT).apply{setMargins(left,top,0,bottom)}
}
