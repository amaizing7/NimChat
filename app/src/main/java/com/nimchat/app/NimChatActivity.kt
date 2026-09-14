package com.nimchat.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.signInAnonymously
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Serializable
data class NCProfile(val id:String,val username:String,val display_name:String?=null)

@Serializable
data class NCMessage(
    val id:String?=null,
    val conversation_id:String,
    val sender_id:String,
    val body:String?=null,
    val created_at:String?=null,
    val updated_at:String?=null,
    val read_at:String?=null,
    val attachment_path:String?=null,
    val attachment_name:String?=null,
    val attachment_mime:String?=null,
    val attachment_size:Long?=null
)

@Serializable
data class NCConversation(
    val conversation_id:String,
    val other_user_id:String,
    val other_username:String,
    val other_display_name:String?=null,
    val last_message:String?=null,
    val last_message_at:String?=null,
    val unread_count:Long=0
)

@Serializable
data class NCConversationParams(@SerialName("target_user") val targetUser:String)
@Serializable
data class NCReadParams(@SerialName("target_conversation") val targetConversation:String)

private val ncSupabase=createSupabaseClient(BuildConfig.SUPABASE_URL,BuildConfig.SUPABASE_KEY){
    install(Auth)
    install(Postgrest)
    install(Realtime)
    install(Storage)
}

class NimChatActivity:Activity(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var uid:String?=null
    private var username=""
    private var activeCid:String?=null
    private var chatUser=""
    private var list:LinearLayout?=null
    private var scroll:ScrollView?=null
    private var rt:Job?=null
    private var homeRt:Job?=null
    private var fallback:Job?=null
    private var pendingFileCid:String?=null
    private val pickFile=1001
    private val channelId="nimchat_messages"

    override fun onCreate(b:Bundle?){
        super.onCreate(b)
        createNotificationChannel()
        uid=ncSupabase.auth.currentUserOrNull()?.id
        if(uid==null)showAuth() else loadProfile()
    }

    override fun onDestroy(){
        stopAll()
        scope.cancel()
        super.onDestroy()
    }

    private fun base()=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL
        setPadding(20,20,20,16)
        setBackgroundColor(Color.rgb(248,249,251))
    }
    private fun t(s:String,z:Float,b:Boolean=false)=TextView(this).apply{
        text=s;textSize=z;setTextColor(Color.rgb(25,28,35));if(b)typeface=Typeface.DEFAULT_BOLD
    }
    private fun btn(s:String)=Button(this).apply{text=s;isAllCaps=false}
    private fun inp(h:String)=EditText(this).apply{hint=h;textSize=16f;maxLines=1;imeOptions=EditorInfo.IME_ACTION_DONE}
    private fun lp(w:Int=-1,top:Int=0,bottom:Int=0,left:Int=0)=LinearLayout.LayoutParams(w,LinearLayout.LayoutParams.WRAP_CONTENT).apply{setMargins(left,top,0,bottom)}

    private fun showAuth(error:String?=null){
        stopAll()
        val r=base().apply{gravity=Gravity.CENTER_HORIZONTAL}
        r.addView(t("NimChat",34f,true),lp())
        r.addView(t("پیام‌رسان کامل و ساده",17f),lp(-1,8,20))
        if(error!=null)r.addView(t(error,14f).apply{setTextColor(Color.rgb(180,40,40))},lp(-1,0,10))
        val n=inp("نام کاربری (a-z, 0-9, _)")
        r.addView(n,lp(-1,0,10))
        val go=btn("ورود به NimChat")
        r.addView(go,lp(-1))
        setContentView(r)
        go.setOnClickListener{
            val u=n.text.toString().trim().lowercase(Locale.ROOT)
            if(!u.matches(Regex("[a-z0-9_]{2,30}"))){n.error="۲ تا ۳۰ کاراکتر";return@setOnClickListener}
            go.isEnabled=false
            scope.launch{
                try{
                    val id=auth()
                    val p=withTimeout(10000){ncSupabase.from("profiles").select{filter{eq("id",id)}}.decodeSingleOrNull<NCProfile>()}
                    if(p==null)withTimeout(10000){ncSupabase.from("profiles").insert(NCProfile(id,u,u))}
                    else if(p.username!=u)error("این حساب با ${p.username} ثبت شده است")
                    uid=id;username=u;showHome()
                }catch(e:Exception){go.isEnabled=true;showAuth("ورود ناموفق: ${friendly(e)}")}
            }
        }
    }

    private suspend fun auth():String{
        val c=ncSupabase.auth.currentUserOrNull()?.id
        if(c!=null)return c
        withTimeout(12000){ncSupabase.auth.signInAnonymously()}
        return ncSupabase.auth.currentUserOrNull()?.id?:error("احراز هویت ناموفق")
    }

    private fun loadProfile()=scope.launch{
        try{
            val id=uid?:return@launch
            val p=withTimeout(10000){ncSupabase.from("profiles").select{filter{eq("id",id)}}.decodeSingleOrNull<NCProfile>()}
            if(p==null)showAuth("پروفایل پیدا نشد") else {username=p.username;showHome()}
        }catch(e:Exception){showAuth(friendly(e))}
    }

    private fun showHome(){
        rt?.cancel()
        val r=base()
        val h=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        h.addView(t("NimChat",28f,true),LinearLayout.LayoutParams(0,-2,1f))
        val profile=btn("پروفایل");profile.setOnClickListener{showProfileDialog()};h.addView(profile)
        val out=btn("خروج");out.setOnClickListener{scope.launch{try{withTimeout(5000){ncSupabase.auth.signOut()}}catch(_:Exception){};uid=null;username="";showAuth()}};h.addView(out)
        r.addView(h,lp(-1,0,10))
        r.addView(t("سلام $username 👋",19f,true),lp(-1,0,10))
        val q=inp("نام کاربری برای شروع گفتگو");r.addView(q,lp(-1,0,8))
        val start=btn("＋ شروع گفتگوی جدید");r.addView(start,lp(-1,0,14));start.setOnClickListener{startChatByUsername(q,start)}
        r.addView(t("گفتگوهای من",19f,true),lp(-1,0,8))
        val s=ScrollView(this);val l=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};s.addView(l);r.addView(s,LinearLayout.LayoutParams(-1,0,1f));setContentView(r)
        list=l
        scope.launch{loadConversations(l)}
        startHomeRealtime()
    }

    private fun startChatByUsername(q:EditText,b:Button){
        val u=q.text.toString().trim().lowercase(Locale.ROOT)
        if(!u.matches(Regex("[a-z0-9_]{2,30}"))){q.error="نام کاربری معتبر نیست";return}
        b.isEnabled=false
        scope.launch{
            try{
                val target=withTimeout(10000){ncSupabase.from("profiles").select{filter{eq("username",u)}}.decodeSingleOrNull<NCProfile>()}?:error("کاربر پیدا نشد")
                val me=uid?:error("جلسه وجود ندارد")
                if(target.id==me)error("گفتگو با خودت ممکن نیست")
                val cid=withTimeout(10000){ncSupabase.postgrest.rpc("create_direct_conversation",NCConversationParams(target.id)).decodeAs<String>()}
                activeCid=cid;chatUser=target.username;showChat()
            }catch(e:Exception){Toast.makeText(this@NimChatActivity,friendly(e),Toast.LENGTH_LONG).show()}
            finally{b.isEnabled=true}
        }
    }

    private suspend fun loadConversations(l:LinearLayout){
        try{
            val rows=withTimeout(12000){ncSupabase.postgrest.rpc("list_my_conversations").decodeList<NCConversation>()}
            l.removeAllViews()
            if(rows.isEmpty()){l.addView(t("هنوز گفتگویی نداری.",15f).apply{setTextColor(Color.GRAY)},lp(-1,10,0));return}
            rows.forEach{row->
                val item=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(14,12,14,12);setBackgroundColor(Color.WHITE);isClickable=true}
                val top=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
                top.addView(t(row.other_display_name?.takeIf{it.isNotBlank()}?:row.other_username,17f,true),LinearLayout.LayoutParams(0,-2,1f))
                if(row.unread_count>0)top.addView(t(" ${row.unread_count} ",13f,true).apply{setTextColor(Color.WHITE);setBackgroundColor(Color.rgb(35,110,210));setPadding(8,3,8,3)})
                item.addView(top)
                item.addView(t(row.last_message?.replace("\n"," ")?.take(80)?:"هنوز پیامی ارسال نشده",14f).apply{setTextColor(Color.DKGRAY)},lp(-1,4,0))
                row.last_message_at?.let{item.addView(t(formatTime(it),11f).apply{setTextColor(Color.GRAY)},lp(-1,4,0))}
                item.setOnClickListener{activeCid=row.conversation_id;chatUser=row.other_username;showChat()}
                l.addView(item,lp(-1,0,8))
            }
        }catch(e:Exception){l.removeAllViews();l.addView(t("بارگذاری ناموفق: ${friendly(e)}",14f).apply{setTextColor(Color.RED)})}
    }

    private fun showChat(){
        val cid=activeCid?:return
        homeRt?.cancel();rt?.cancel();fallback?.cancel()
        val r=base()
        val h=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        val back=btn("‹").apply{textSize=28f};h.addView(back,LinearLayout.LayoutParams(54,54));h.addView(t(chatUser,22f,true),LinearLayout.LayoutParams(0,-2,1f))
        val attach=btn("📎");h.addView(attach);back.setOnClickListener{showHome()};attach.setOnClickListener{pickAttachment(cid)};r.addView(h,lp(-1,0,6))
        val sv=ScrollView(this);val l=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(4,8,4,8)};sv.addView(l);r.addView(sv,LinearLayout.LayoutParams(-1,0,1f));list=l;scroll=sv
        val bar=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};val input=inp("پیام بنویس...").apply{maxLines=4;minLines=1};val send=btn("ارسال");bar.addView(input,LinearLayout.LayoutParams(0,-2,1f));bar.addView(send);r.addView(bar,lp(-1,8,0));setContentView(r)
        send.setOnClickListener{sendText(cid,input,send)}
        scope.launch{refreshMessages(cid)}
        startChatRealtime(cid)
    }

    private fun sendText(cid:String,input:EditText,b:Button){
        val body=input.text.toString().trim();if(body.isEmpty())return
        if(body.length>4000){input.error="حداکثر ۴۰۰۰ کاراکتر";return}
        b.isEnabled=false
        scope.launch{try{val me=uid?:error("جلسه وجود ندارد");withTimeout(10000){ncSupabase.from("messages").insert(NCMessage(conversation_id=cid,sender_id=me,body=body))};input.text.clear();refreshMessages(cid)}catch(e:Exception){Toast.makeText(this@NimChatActivity,"ارسال ناموفق: ${friendly(e)}",Toast.LENGTH_LONG).show()}finally{b.isEnabled=true}}
    }

    private fun startChatRealtime(cid:String){
        rt=scope.launch{try{ncSupabase.realtime.connect();val c=ncSupabase.realtime.channel("chat-$cid");val a=c.postgresChangeFlow<PostgresAction.Insert>(schema="public"){table="messages"};val u=c.postgresChangeFlow<PostgresAction.Update>(schema="public"){table="messages"};val d=c.postgresChangeFlow<PostgresAction.Delete>(schema="public"){table="messages"};c.subscribe();merge(a,u,d).collect{refreshMessages(cid)}}catch(_:Exception){}}
        fallback=scope.launch{while(true){delay(6000);refreshMessages(cid)}}
    }

    private suspend fun refreshMessages(cid:String){
        try{
            var ms=withTimeout(10000){ncSupabase.from("messages").select{filter{eq("conversation_id",cid)};order("created_at",Order.ASCENDING)}.decodeList<NCMessage>()}
            if(ms.any{it.sender_id!=uid&&it.read_at==null}){
                withTimeout(10000){ncSupabase.postgrest.rpc("mark_conversation_read",NCReadParams(cid))}
                ms=withTimeout(10000){ncSupabase.from("messages").select{filter{eq("conversation_id",cid)};order("created_at",Order.ASCENDING)}.decodeList<NCMessage>()}
            }
            val l=list?:return
            l.removeAllViews();ms.forEach{addMessage(l,it)};scroll?.post{scroll?.fullScroll(View.FOCUS_DOWN)}
        }catch(_:Exception){}
    }

    private fun addMessage(p:LinearLayout,m:NCMessage){
        val mine=m.sender_id==uid
        val row=LinearLayout(this).apply{gravity=if(mine)Gravity.END else Gravity.START;setPadding(4,4,4,4)}
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(15,9,15,8);setBackgroundColor(if(mine)Color.rgb(220,235,255)else Color.WHITE)}
        if(!m.body.isNullOrBlank())box.addView(t(m.body!!,16f))
        if(m.attachment_path!=null){val f=btn("📎 ${m.attachment_name?:"فایل"}");f.setOnClickListener{openAttachment(m)};box.addView(f,lp(-1,5,0))}
        val meta=LinearLayout(this).apply{gravity=Gravity.END};meta.addView(t(formatTime(m.created_at),10f).apply{setTextColor(Color.GRAY)})
        if(mine){if(m.updated_at!=null&&m.created_at!=null&&m.updated_at!=m.created_at)meta.addView(t(" ویرایش‌شده",9f));meta.addView(t(if(m.read_at!=null)" ✓✓ خوانده شد" else " ✓ ارسال شد",9f).apply{setTextColor(if(m.read_at!=null)Color.rgb(35,110,210)else Color.GRAY)});val act=btn("⋮");act.setOnClickListener{messageActions(m)};meta.addView(act,LinearLayout.LayoutParams(42,40))}
        box.addView(meta,lp(-1,3,0));row.addView(box,LinearLayout.LayoutParams(-2,-2));p.addView(row)
    }

    private fun messageActions(m:NCMessage){AlertDialog.Builder(this).setItems(arrayOf("ویرایش","حذف","لغو")){_,w->when(w){0->editMessage(m);1->deleteMessage(m)}}.show()}
    private fun editMessage(m:NCMessage){
        val f=inp("متن پیام").apply{setText(m.body?:"");maxLines=6}
        AlertDialog.Builder(this).setTitle("ویرایش پیام").setView(f).setNegativeButton("لغو",null).setPositiveButton("ذخیره"){_,_->scope.launch{try{val body=f.text.toString().trim();if(body.isEmpty())return@launch;withTimeout(10000){ncSupabase.from("messages").update({set("body",body)}){filter{eq("id",m.id)}}};activeCid?.let{refreshMessages(it)}}catch(e:Exception){Toast.makeText(this@NimChatActivity,friendly(e),Toast.LENGTH_LONG).show()}}}.show()
    }
    private fun deleteMessage(m:NCMessage){AlertDialog.Builder(this).setTitle("حذف پیام؟").setNegativeButton("لغو",null).setPositiveButton("حذف"){_,_->scope.launch{try{withTimeout(10000){ncSupabase.from("messages").delete{filter{eq("id",m.id)}};activeCid?.let{refreshMessages(it)}}catch(e:Exception){Toast.makeText(this@NimChatActivity,friendly(e),Toast.LENGTH_LONG).show()}}}}.show()}

    private fun pickAttachment(cid:String){pendingFileCid=cid;startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="*/*";addCategory(Intent.CATEGORY_OPENABLE)},pickFile)}
    override fun onActivityResult(req:Int,res:Int,data:Intent?){super.onActivityResult(req,res,data);if(req!=pickFile||res!=RESULT_OK||data?.data==null)return;val cid=pendingFileCid?:return;val uri=data.data!!;scope.launch{try{uploadAttachment(cid,uri)}catch(e:Exception){Toast.makeText(this@NimChatActivity,"فایل ارسال نشد: ${friendly(e)}",Toast.LENGTH_LONG).show()}}}
    private suspend fun uploadAttachment(cid:String,uri:Uri){
        val me=uid?:error("جلسه وجود ندارد")
        val name=queryName(uri)?.take(120)?:"file-${System.currentTimeMillis()}"
        val mime=contentResolver.getType(uri)?:"application/octet-stream"
        val size=querySize(uri)
        if(size>10L*1024*1024)error("حداکثر اندازه فایل ۱۰ مگابایت است")
        val bytes=withContext(Dispatchers.IO){contentResolver.openInputStream(uri)?.use{it.readBytes()}?:error("خواندن فایل ناموفق")}
        if(bytes.size>10*1024*1024)error("حداکثر اندازه فایل ۱۰ مگابایت است")
        val path="$me/$cid/${System.currentTimeMillis()}-$name"
        withTimeout(30000){ncSupabase.storage.from("chat-files").upload(path,bytes)}
        withTimeout(10000){ncSupabase.from("messages").insert(NCMessage(conversation_id=cid,sender_id=me,body=null,attachment_path=path,attachment_name=name,attachment_mime=mime,attachment_size=bytes.size.toLong()))}
        refreshMessages(cid)
    }
    private fun queryName(u:Uri):String?=contentResolver.query(u,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}
    private fun querySize(u:Uri):Long{val x=contentResolver.query(u,arrayOf(OpenableColumns.SIZE),null,null,null)?.use{if(it.moveToFirst()&&!it.isNull(0))it.getLong(0)else -1L}?:-1L;return if(x<0)0 else x}
    private fun openAttachment(m:NCMessage){
        scope.launch{try{val bytes=withTimeout(30000){ncSupabase.storage.from("chat-files").downloadAuthenticated(m.attachment_path!!)};val file=File(cacheDir,m.attachment_name?:"download");withContext(Dispatchers.IO){file.writeBytes(bytes)};val uri=FileProvider.getUriForFile(this@NimChatActivity,"$packageName.fileprovider",file);startActivity(Intent(Intent.ACTION_VIEW).apply{setDataAndType(uri,m.attachment_mime?:"application/octet-stream");addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)})}catch(e:Exception){Toast.makeText(this@NimChatActivity,"بازکردن فایل ناموفق: ${friendly(e)}",Toast.LENGTH_LONG).show()}}
    }

    private fun showProfileDialog(){
        val name=inp("نام نمایشی")
        scope.launch{try{val p=withTimeout(8000){ncSupabase.from("profiles").select{filter{eq("id",uid)}}.decodeSingleOrNull<NCProfile>()};name.setText(p?.display_name?:username)}catch(_:Exception){name.setText(username)}}
        AlertDialog.Builder(this).setTitle("پروفایل").setMessage("@$username").setView(name).setNegativeButton("لغو",null).setPositiveButton("ذخیره"){_,_->scope.launch{try{val v=name.text.toString().trim().take(60);withTimeout(10000){ncSupabase.from("profiles").update({set("display_name",if(v.isBlank())null else v)}){filter{eq("id",uid)}};showHome()}catch(e:Exception){Toast.makeText(this@NimChatActivity,friendly(e),Toast.LENGTH_LONG).show()}}}}.show()
    }

    private fun startHomeRealtime(){
        homeRt?.cancel()
        homeRt=scope.launch{try{ncSupabase.realtime.connect();val c=ncSupabase.realtime.channel("home-$uid");val ins=c.postgresChangeFlow<PostgresAction.Insert>(schema="public"){table="messages"};val upd=c.postgresChangeFlow<PostgresAction.Update>(schema="public"){table="messages"};c.subscribe();merge(ins,upd).collect{loadConversations(list?:return@collect);notifyIncoming()}}catch(_:Exception){}}
    }
    private fun notifyIncoming(){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.POST_NOTIFICATIONS),77);return}
        try{val n=NotificationCompat.Builder(this,channelId).setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("پیام جدید در NimChat").setContentText("یک پیام جدید دریافت شد").setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT).build();NotificationManagerCompat.from(this).notify((System.currentTimeMillis()%100000).toInt(),n)}catch(_:Exception){}
    }
    private fun createNotificationChannel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channelId,"NimChat",NotificationManager.IMPORTANCE_DEFAULT))}

    private fun stopAll(){rt?.cancel();homeRt?.cancel();fallback?.cancel();rt=null;homeRt=null;fallback=null;list=null;scroll=null;try{ncSupabase.realtime.removeAllChannels()}catch(_:Exception){}}
    private fun formatTime(v:String?):String{if(v.isNullOrBlank())return "";return try{val d=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",Locale.US).parse(v)?:return v.take(16);SimpleDateFormat("HH:mm",Locale.getDefault()).format(d)}catch(_:Exception){v.replace("T"," ").take(16)}}
    private fun friendly(e:Throwable):String{val x=e.message.orEmpty();return when{ x.contains("not_authenticated",true)->"جلسه احراز هویت وجود ندارد.";x.contains("user_not_found",true)->"کاربر پیدا نشد.";x.contains("invalid_target",true)->"کاربر مقصد نامعتبر است.";x.contains("timeout",true)||x.contains("timed out",true)->"اتصال به سرور زمان‌بر شد.";x.isBlank()->e.javaClass.simpleName;else->x.replace("\n"," ").take(220)}}
}
