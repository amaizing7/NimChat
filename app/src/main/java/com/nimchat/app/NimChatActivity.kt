package com.nimchat.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.signInAnonymously
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Serializable
data class NCProfile(val id: String, val username: String, val display_name: String? = null)

@Serializable
data class NCMessage(
    val id: String? = null,
    val conversation_id: String,
    val sender_id: String,
    val body: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null,
    val read_at: String? = null,
    val attachment_path: String? = null,
    val attachment_name: String? = null,
    val attachment_mime: String? = null,
    val attachment_size: Long? = null
)

@Serializable
data class NCConversation(
    val conversation_id: String,
    val other_user_id: String,
    val other_username: String,
    val other_display_name: String? = null,
    val last_message: String? = null,
    val last_message_at: String? = null,
    val unread_count: Long = 0
)

@Serializable
data class CreateConversationParams(@SerialName("target_user") val targetUser: String)
@Serializable
data class MarkConversationReadParams(@SerialName("target_conversation") val targetConversation: String)

private val ncSupabase = createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
    install(Auth); install(Postgrest); install(Realtime); install(Storage)
}

class NimChatActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val outbox by lazy { NCOutbox(getSharedPreferences("nimchat_outbox", MODE_PRIVATE)) }
    private var uid: String? = null
    private var username = ""
    private var activeCid: String? = null
    private var chatUser = ""
    private var list: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var rt: Job? = null
    private var homeRt: Job? = null
    private var fallback: Job? = null
    private var pendingFileCid: String? = null
    private var cachedConversations: List<NCConversation> = emptyList()
    private val failedOutgoing = linkedMapOf<String, NCFailedMessage>()
    private var refreshQueued = false
    private val pickFile = 1001
    private val channelId = "nimchat_messages"

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); createNotificationChannel(); uid=ncSupabase.auth.currentUserOrNull()?.id; if(uid==null)showAuth() else loadProfile() }
    override fun onDestroy(){stopAll();scope.cancel();super.onDestroy()}
    private fun base()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,20,20,16);setBackgroundColor(Color.rgb(248,249,251))}
    private fun t(s:String,z:Float,bold:Boolean=false)=TextView(this).apply{text=s;textSize=z;setTextColor(Color.rgb(25,28,35));if(bold)typeface=Typeface.DEFAULT_BOLD}
    private fun btn(s:String)=Button(this).apply{text=s;isAllCaps=false}
    private fun inp(h:String)=EditText(this).apply{hint=h;textSize=16f;maxLines=1;imeOptions=EditorInfo.IME_ACTION_DONE}
    private fun lp(w:Int=-1,top:Int=0,bottom:Int=0,left:Int=0)=LinearLayout.LayoutParams(w,LinearLayout.LayoutParams.WRAP_CONTENT).apply{setMargins(left,top,0,bottom)}

    private fun showAuth(error:String?=null){
        stopAll();val root=base().apply{gravity=Gravity.CENTER_HORIZONTAL};root.addView(t("NimChat",34f,true),lp());root.addView(t("پیام‌رسان سریع و ساده",17f),lp(-1,8,20));if(error!=null)root.addView(t(error,14f).apply{setTextColor(Color.rgb(180,40,40))},lp(-1,0,10));val name=inp("نام کاربری (a-z, 0-9, _)");root.addView(name,lp(-1,0,10));val enter=btn("ورود به NimChat");root.addView(enter,lp(-1));setContentView(root)
        enter.setOnClickListener{val u=name.text.toString().trim().lowercase(Locale.ROOT);if(!u.matches(Regex("[a-z0-9_]{2,30}"))){name.error="۲ تا ۳۰ کاراکتر";return@setOnClickListener};enter.isEnabled=false;scope.launch{try{val id=ensureAuthenticated();val profile=withTimeout(10000){ncSupabase.from("profiles").select{filter{eq("id",id)}}.decodeSingleOrNull<NCProfile>()};if(profile==null)withTimeout(10000){ncSupabase.from("profiles").insert(NCProfile(id,u,u))}else if(profile.username!=u)error("این حساب قبلاً با نام کاربری ${profile.username} ثبت شده است");uid=id;username=u;showHome()}catch(e:Exception){enter.isEnabled=true;showAuth("ورود ناموفق: ${friendly(e)}")}}}
    }
    private suspend fun ensureAuthenticated():String{val cached=ncSupabase.auth.currentUserOrNull()?.id;if(cached!=null)return cached;withTimeout(12000){ncSupabase.auth.signInAnonymously()};return ncSupabase.auth.currentUserOrNull()?.id?:error("احراز هویت ناموفق")}
    private fun loadProfile()=scope.launch{try{val id=uid?:return@launch;val profile=withTimeout(10000){ncSupabase.from("profiles").select{filter{eq("id",id)}}.decodeSingleOrNull<NCProfile>()};if(profile==null)showAuth("پروفایل پیدا نشد")else{username=profile.username;showHome()}}catch(e:Exception){showAuth(friendly(e))}}

    private fun showHome(){
        stopChatOnly();val root=base();val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};header.addView(t("NimChat",28f,true),LinearLayout.LayoutParams(0,-2,1f));val profile=btn("پروفایل");profile.setOnClickListener{showProfileDialog()};header.addView(profile);val logout=btn("خروج");logout.setOnClickListener{scope.launch{try{withTimeout(5000){ncSupabase.auth.signOut()}}catch(_:Exception){};uid=null;username="";cachedConversations=emptyList();showAuth()}};header.addView(logout);root.addView(header,lp(-1,0,10));root.addView(t("سلام $username 👋",19f,true),lp(-1,0,10));val search=inp("جستجوی گفتگو یا نام کاربری");root.addView(search,lp(-1,0,8));val start=btn("＋ شروع گفتگوی جدید");root.addView(start,lp(-1,0,14));start.setOnClickListener{startChatByUsername(search,start)};root.addView(t("گفتگوهای من",19f,true),lp(-1,0,8));val sv=ScrollView(this);val conversationList=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};sv.addView(conversationList);root.addView(sv,LinearLayout.LayoutParams(-1,0,1f));list=conversationList;setContentView(root)
        search.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit;override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){renderConversations(conversationList,s?.toString().orEmpty())};override fun afterTextChanged(s:Editable?)=Unit});scope.launch{loadConversations(conversationList)};startHomeRealtime()
    }
    private fun startChatByUsername(input:EditText,button:Button){val u=input.text.toString().trim().lowercase(Locale.ROOT);if(!u.matches(Regex("[a-z0-9_]{2,30}"))){input.error="نام کاربری معتبر نیست";return};button.isEnabled=false;scope.launch{try{val target=withTimeout(10000){ncSupabase.from("profiles").select{filter{eq("username",u)}}.decodeSingleOrNull<NCProfile>()}?:error("کاربر پیدا نشد");val me=uid?:error("جلسه وجود ندارد");if(target.id==me)error("گفتگو با خودت ممکن نیست");val cid=withTimeout(10000){ncSupabase.postgrest.rpc("create_direct_conversation",CreateConversationParams(target.id)).decodeAs<String>()};activeCid=cid;chatUser=target.username;showChat()}catch(e:Exception){Toast.makeText(this@NimChatActivity,friendly(e),Toast.LENGTH_LONG).show()}finally{button.isEnabled=true}}}
    private suspend fun loadConversations(container:LinearLayout){try{cachedConversations=withTimeout(12000){ncSupabase.postgrest.rpc("list_my_conversations").decodeList<NCConversation>()};renderConversations(container,"")}catch(e:Exception){container.removeAllViews();container.addView(t("بارگذاری ناموفق: ${friendly(e)}",14f).apply{setTextColor(Color.RED)})}}
    private fun renderConversations(container:LinearLayout,rawQuery:String){val query=rawQuery.trim().lowercase(Locale.ROOT);val rows=cachedConversations.filter{row->query.isBlank()||row.other_username.lowercase(Locale.ROOT).contains(query)||row.other_display_name.orEmpty().lowercase(Locale.ROOT).contains(query)||row.last_message.orEmpty().lowercase(Locale.ROOT).contains(query)};container.removeAllViews();if(rows.isEmpty()){container.addView(t(if(query.isBlank())"هنوز گفتگویی نداری." else "نتیجه‌ای پیدا نشد",15f).apply{setTextColor(Color.GRAY)},lp(-1,10,0));return};rows.forEach{row->val item=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(14,12,14,12);setBackgroundColor(Color.WHITE);isClickable=true};val top=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};top.addView(t(row.other_display_name?.takeIf{it.isNotBlank()}?:row.other_username,17f,true),LinearLayout.LayoutParams(0,-2,1f));if(row.unread_count>0)top.addView(t(" ${row.unread_count} ",13f,true).apply{setTextColor(Color.WHITE);setBackgroundColor(Color.rgb(35,110,210));setPadding(8,3,8,3)});item.addView(top);item.addView(t(row.last_message?.replace("\n"," ")?.take(80)?:"هنوز پیامی ارسال نشده",14f).apply{setTextColor(Color.DKGRAY)},lp(-1,4,0));row.last_message_at?.let{item.addView(t(formatTime(it),11f).apply{setTextColor(Color.GRAY)},lp(-1,4,0))};item.setOnClickListener{activeCid=row.conversation_id;chatUser=row.other_username;showChat()};container.addView(item,lp(-1,0,8))}}

    private fun showChat(){val cid=activeCid?:return;stopChatOnly();val root=base();val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};val back=btn("‹").apply{textSize=28f};header.addView(back,LinearLayout.LayoutParams(54,54));header.addView(t(chatUser,22f,true),LinearLayout.LayoutParams(0,-2,1f));val attach=btn("📎");header.addView(attach);back.setOnClickListener{showHome()};attach.setOnClickListener{pickAttachment(cid)};root.addView(header,lp(-1,0,6));val sv=ScrollView(this);val messageList=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(4,8,4,8)};sv.addView(messageList);root.addView(sv,LinearLayout.LayoutParams(-1,0,1f));list=messageList;scroll=sv;val composer=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};val input=inp("پیام بنویس...").apply{maxLines=4;minLines=1};val send=btn("ارسال");composer.addView(input,LinearLayout.LayoutParams(0,-2,1f));composer.addView(send);root.addView(composer,lp(-1,8,0));setContentView(root);send.setOnClickListener{sendText(cid,input,send)};input.setOnEditorActionListener{_,action,_->if(action==EditorInfo.IME_ACTION_DONE){send.performClick();true}else false};scope.launch{refreshMessages(cid)};startChatRealtime(cid)}

    private fun sendText(cid:String,input:EditText,button:Button){val body=input.text.toString().trim();if(body.isEmpty())return;if(body.length>4000){input.error="حداکثر ۴۰۰۰ کاراکتر";return};button.isEnabled=false;scope.launch{val me=uid;if(me==null){button.isEnabled=true;Toast.makeText(this@NimChatActivity,"جلسه وجود ندارد",Toast.LENGTH_LONG).show();return@launch};val id=UUID.randomUUID().toString();val message=NCMessage(id=id,conversation_id=cid,sender_id=me,body=body);try{sendOutgoing(message);input.text.clear();failedOutgoing.remove(id);outbox.remove(id);refreshMessages(cid)}catch(e:Exception){val failed=NCFailedMessage(id,cid,me,body,friendly(e));failedOutgoing[id]=failed;outbox.save(failed);refreshMessages(cid);Toast.makeText(this@NimChatActivity,"ارسال ناموفق؛ پیام ذخیره شد",Toast.LENGTH_LONG).show()}finally{button.isEnabled=true}}}
    private suspend fun sendOutgoing(message:NCMessage){try{withTimeout(10000){ncSupabase.from("messages").insert(message)}}catch(first:Exception){val existing=try{withTimeout(5000){ncSupabase.from("messages").select{filter{eq("id",message.id?:"")}}.decodeSingleOrNull<NCMessage>()}}catch(_:Exception){null};if(existing?.id==message.id)return;throw first}}
    private fun retryFailedMessage(failed:NCFailedMessage){if(!NimChatRetry.shouldRetry(failed,activeCid?:return))return;val current=failedOutgoing[failed.id]?:return;scope.launch{val button=list?.findViewWithTag<Button>("retry-${failed.id}");button?.isEnabled=false;try{val me=uid?:error("جلسه وجود ندارد");sendOutgoing(NCMessage(id=current.id,conversation_id=current.conversation_id,sender_id=me,body=current.body));failedOutgoing.remove(current.id);outbox.remove(current.id);refreshMessages(current.conversation_id);Toast.makeText(this@NimChatActivity,"پیام با موفقیت ارسال شد",Toast.LENGTH_SHORT).show()}catch(e:Exception){val next=current.copy(error=friendly(e));failedOutgoing[current.id]=next;outbox.save(next);refreshMessages(current.conversation_id);Toast.makeText(this@NimChatActivity,"تلاش مجدد ناموفق بود",Toast.LENGTH_LONG).show()}}}

    private fun startChatRealtime(cid:String){rt=scope.launch{try{ncSupabase.realtime.connect();val channel=ncSupabase.realtime.channel("chat-$cid");val inserts=channel.postgresChangeFlow<PostgresAction.Insert>(schema="public"){table="messages";filter{eq("conversation_id",cid)}};val updates=channel.postgresChangeFlow<PostgresAction.Update>(schema="public"){table="messages";filter{eq("conversation_id",cid)}};val deletes=channel.postgresChangeFlow<PostgresAction.Delete>(schema="public"){table="messages"};channel.subscribe();merge(inserts,updates,deletes).collect{queueRefresh(cid)}}catch(_:Exception){}};fallback=scope.launch{while(isActive){delay(6000);refreshMessages(cid)}}}
    private fun queueRefresh(cid:String){if(refreshQueued)return;refreshQueued=true;scope.launch{delay(150);refreshQueued=false;if(activeCid==cid)refreshMessages(cid)}}
    private suspend fun refreshMessages(cid:String){try{var messages=withTimeout(10000){ncSupabase.from("messages").select{filter{eq("conversation_id",cid)};order("created_at",Order.ASCENDING)}.decodeList<NCMessage>()};if(messages.any{it.sender_id!=uid&&it.read_at==null}){withTimeout(10000){ncSupabase.postgrest.rpc("mark_conversation_read",MarkConversationReadParams(cid))};messages=withTimeout(10000){ncSupabase.from("messages").select{filter{eq("conversation_id",cid)};order("created_at",Order.ASCENDING)}.decodeList<NCMessage>()}};val container=list?:return;failedOutgoing.clear();outbox.loadForConversation(cid).forEach{failedOutgoing[it.id]=it};container.removeAllViews();messages.forEach{addMessage(container,it)};failedOutgoing.values.filter{NimChatRetry.shouldRetry(it,cid)}.forEach{addFailedMessage(container,it)};scroll?.post{scroll?.fullScroll(View.FOCUS_DOWN)}}catch(_:Exception){}}
    private fun addFailedMessage(parent:LinearLayout,failed:NCFailedMessage){val row=LinearLayout(this).apply{gravity=Gravity.END;setPadding(4,4,4,4)};val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(15,9,15,8);setBackgroundColor(Color.rgb(255,235,235))};box.addView(t(failed.body,16f));box.addView(t("ارسال نشد: ${failed.error}",11f).apply{setTextColor(Color.rgb(170,40,40))},lp(-1,3,0));val retry=btn("تلاش مجدد");retry.tag="retry-${failed.id}";retry.setOnClickListener{retryFailedMessage(failed)};box.addView(retry,lp(-1,5,0));row.addView(box,LinearLayout.LayoutParams(-2,-2));parent.addView(row)}

    private fun addMessage(parent:LinearLayout,message:NCMessage){val mine=message.sender_id==uid;val row=LinearLayout(this).apply{gravity=if(mine)Gravity.END else Gravity.START;setPadding(4,4,4,4)};val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(15,9,15,8);setBackgroundColor(if(mine)Color.rgb(220,235,255)else Color.WHITE)};if(!message.body.isNullOrBlank())box.addView(t(message.body.orEmpty(),16f));val path=message.attachment_path;if(path!=null){if(message.attachment_mime.orEmpty().startsWith("image/")){val preview=ImageView(this).apply{adjustViewBounds=true;maxHeight=420;setBackgroundColor(Color.LTGRAY);contentDescription=message.attachment_name};box.addView(preview,LinearLayout.LayoutParams(420,WRAP_CONTENT).apply{setMargins(0,5,0,0)});loadImagePreview(preview,path)};val fileButton=btn("📎 ${message.attachment_name?:"فایل"}");fileButton.setOnClickListener{openAttachment(message)};box.addView(fileButton,lp(-1,5,0))};val meta=LinearLayout(this).apply{gravity=Gravity.END};meta.addView(t(formatTime(message.created_at),10f).apply{setTextColor(Color.GRAY)});if(mine){if(message.updated_at!=null&&message.created_at!=null&&message.updated_at!=message.created_at)meta.addView(t(" ویرایش‌شده",9f).apply{setTextColor(Color.GRAY)});meta.addView(t(if(message.read_at!=null)" ✓✓ خوانده شد" else " ✓ ارسال شد",9f).apply{setTextColor(if(message.read_at!=null)Color.rgb(35,110,210)else Color.GRAY)});val actions=btn("⋮");actions.setOnClickListener{messageActions(message)};meta.addView(actions,LinearLayout.LayoutParams(42,40))};box.addView(meta,lp(-1,3,0));row.addView(box,LinearLayout.LayoutParams(-2,-2));parent.addView(row)}
    private fun loadImagePreview(view:ImageView,path:String){scope.launch{try{val bytes=withContext(Dispatchers.IO){ncSupabase.storage.from("chat-files").downloadAuthenticated(path)};val bitmap=withContext(Dispatchers.Default){BitmapFactory.decodeByteArray(bytes,0,bytes.size)};if(bitmap!=null)view.setImageBitmap(bitmap)}catch(_:Exception){view.setImageResource(android.R.drawable.ic_menu_report_image)}}}
    private fun messageActions(message:NCMessage){val options=if(message.body.isNullOrBlank())arrayOf("حذف پیام","لغو")else arrayOf("ویرایش پیام","حذف پیام","لغو");AlertDialog.Builder(this).setItems(options){_,which->if(message.body.isNullOrBlank()){if(which==0)confirmDelete(message)}else when(which){0->showEditDialog(message);1->confirmDelete(message)}}.show()}
    private fun showEditDialog(message:NCMessage){val field=inp("متن پیام").apply{setText(message.body.orEmpty());setSelection(text.length);maxLines=6};AlertDialog.Builder(this).setTitle("ویرایش پیام").setView(field).setNegativeButton("لغو",null).setPositiveButton("ذخیره"){_,_->val value=field.text.toString().trim();if(value.isNotEmpty()&&value.length<=4000)scope.launch{try{ncSupabase.from("messages").update({set("body",value)}){filter{eq("id",message.id?:"")}};activeCid?.let{refreshMessages(it)}}catch(e:Exception){Toast.makeText(this@NimChatActivity,friendly(e),Toast.LENGTH_LONG).show()}}}.show()}
    private fun confirmDelete(message:NCMessage){AlertDialog.Builder(this).setTitle("حذف پیام").setMessage("این پیام حذف شود؟").setNegativeButton("لغو",null).setPositiveButton("حذف"){_,_->scope.launch{try{ncSupabase.from("messages").delete{filter{eq("id",message.id?:"")}};activeCid?.let{refreshMessages(it)}}catch(e:Exception){Toast.makeText(this@NimChatActivity,friendly(e),Toast.LENGTH_LONG).show()}}}.show()}

    private fun pickAttachment(cid:String){pendingFileCid=cid;startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="*/*"},pickFile)}
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode!=pickFile||resultCode!=RESULT_OK)return;val uri=data?.data?:return;val cid=pendingFileCid?:return;scope.launch{uploadAttachment(cid,uri)}}
    private suspend fun uploadAttachment(cid:String,uri:Uri){var uploadedPath:String?=null;try{val resolver=contentResolver;val size=resolver.query(uri,null,null,null,null)?.use{cursor->val index=cursor.getColumnIndex(OpenableColumns.SIZE);if(cursor.moveToFirst()&&index>=0)cursor.getLong(index)else-1L}?:-1L;if(size>10L*1024L*1024L)error("حداکثر حجم فایل ۱۰ مگابایت است");val bytes=resolver.openInputStream(uri)?.use{it.readBytes()}?:error("خواندن فایل ناموفق بود");if(bytes.size>10*1024*1024)error("حداکثر حجم فایل ۱۰ مگابایت است");val name=queryDisplayName(uri)?:"file";val mime=resolver.getType(uri)?:"application/octet-stream";val me=uid?:error("جلسه وجود ندارد");val path="$me/${UUID.randomUUID()}-${name.replace(Regex("[^A-Za-z0-9._-]"),"_")}";withContext(Dispatchers.IO){ncSupabase.storage.from("chat-files").upload(path,bytes)};uploadedPath=path;withTimeout(10000){ncSupabase.from("messages").insert(NCMessage(conversation_id=cid,sender_id=me,body=null,attachment_path=path,attachment_name=name,attachment_mime=mime,attachment_size=bytes.size.toLong()))};refreshMessages(cid)}catch(e:Exception){uploadedPath?.let{try{withContext(Dispatchers.IO){ncSupabase.storage.from("chat-files").delete(it)}}catch(_:Exception){}};Toast.makeText(this@NimChatActivity,"ارسال فایل ناموفق: ${friendly(e)}",Toast.LENGTH_LONG).show()}}
    private fun queryDisplayName(uri:Uri):String?=contentResolver.query(uri,null,null,null,null)?.use{cursor->val index=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(cursor.moveToFirst()&&index>=0)cursor.getString(index)else null}
    private fun openAttachment(message:NCMessage){val path=message.attachment_path?:return;scope.launch{try{val bytes=withContext(Dispatchers.IO){ncSupabase.storage.from("chat-files").downloadAuthenticated(path)};val file=File(cacheDir,message.attachment_name?:"attachment");file.writeBytes(bytes);val uri=FileProvider.getUriForFile(this@NimChatActivity,"${BuildConfig.APPLICATION_ID}.fileprovider",file);startActivity(Intent(Intent.ACTION_VIEW).apply{setDataAndType(uri,message.attachment_mime?:"application/octet-stream");addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)})}catch(e:Exception){Toast.makeText(this@NimChatActivity,"باز کردن فایل ناموفق: ${friendly(e)}",Toast.LENGTH_LONG).show()}}}

    private fun showProfileDialog(){scope.launch{try{val id=uid?:return@launch;val current=withTimeout(10000){ncSupabase.from("profiles").select{filter{eq("id",id)}}.decodeSingleOrNull<NCProfile>()};val field=inp("نام نمایشی").apply{setText(current?.display_name?:username)};AlertDialog.Builder(this@NimChatActivity).setTitle("پروفایل").setView(field).setNegativeButton("لغو",null).setPositiveButton("ذخیره"){_,_->val value=field.text.toString().trim();scope.launch{try{ncSupabase.from("profiles").update({set("display_name",value.ifBlank{null})}){filter{eq("id",id)}};showHome()}catch(e:Exception){Toast.makeText(this@NimChatActivity,friendly(e),Toast.LENGTH_LONG).show()}}}.show()}catch(e:Exception){Toast.makeText(this@NimChatActivity,friendly(e),Toast.LENGTH_LONG).show()}}}
    private fun startHomeRealtime(){homeRt?.cancel();homeRt=scope.launch{try{ncSupabase.realtime.connect();val channel=ncSupabase.realtime.channel("home-${uid?:"user"}");val inserts=channel.postgresChangeFlow<PostgresAction.Insert>(schema="public"){table="messages"};val updates=channel.postgresChangeFlow<PostgresAction.Update>(schema="public"){table="messages"};val deletes=channel.postgresChangeFlow<PostgresAction.Delete>(schema="public"){table="messages"};channel.subscribe();merge(inserts,updates,deletes).collect{loadConversations(list?:return@collect);if(it is PostgresAction.Insert)notifyIncoming(it.record)}}}catch(_:Exception){}}
    private fun notifyIncoming(record:Map<String,Any?>){val sender=record["sender_id"]?.toString()?:return;if(sender==uid)return;val conversation=record["conversation_id"]?.toString()?:return;if(conversation==activeCid)return;val body=record["body"]?.toString()?.takeIf{it.isNotBlank()}?:"فایل جدید";if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.POST_NOTIFICATIONS),2001);return};val notification=NotificationCompat.Builder(this,channelId).setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("NimChat").setContentText(body).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT).build();try{NotificationManagerCompat.from(this).notify(conversation.hashCode(),notification)}catch(_:SecurityException){}}
    private fun createNotificationChannel(){if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){val channel=NotificationChannel(channelId,"پیام‌های NimChat",NotificationManager.IMPORTANCE_DEFAULT);getSystemService(NotificationManager::class.java).createNotificationChannel(channel)}}
    private fun stopChatOnly(){rt?.cancel();fallback?.cancel();rt=null;fallback=null;refreshQueued=false;try{ncSupabase.realtime.removeAllChannels()}catch(_:Exception){}}
    private fun stopAll(){stopChatOnly();homeRt?.cancel();homeRt=null;try{ncSupabase.realtime.removeAllChannels()}catch(_:Exception){}}
    private fun friendly(e:Exception):String=e.message?.replace(Regex("\\s+")," ")?.take(220)?:"خطای نامشخص"
    private fun formatTime(value:String?):String{if(value.isNullOrBlank())return "";return try{val input=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",Locale.US);val date=input.parse(value)?:return value.take(16);SimpleDateFormat("HH:mm",Locale.getDefault()).format(date)}catch(_:Exception){try{val input=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",Locale.US);val date=input.parse(value)?:return value.take(16);SimpleDateFormat("HH:mm",Locale.getDefault()).format(date)}catch(_:Exception){value.take(16)}}}
    companion object{private const val WRAP_CONTENT=-2}
}
