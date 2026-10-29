package ai.learnharness.android.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import ai.learnharness.android.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

/** The packaged, network-disabled JS engine shares desktop grading and transcript contracts.
 * All visible app controls are Compose; no website or remote script runs in this WebView. */
@SuppressLint("SetJavaScriptEnabled")
class PortableEngine(context: Context, private val chatGpt: ChatGpt,
    private val scope: CoroutineScope, private val persist: suspend (JsonObject) -> Unit,
    private val syncOperation: suspend (String,JsonObject)->JsonElement,
    private val onProgress:(String)->Unit = {}) {
    private var ready=CompletableDeferred<Unit>()
    private val pending=ConcurrentHashMap<String,CompletableDeferred<JsonObject>>()
    @Volatile private var active: String?=null
    private var operationScope:CoroutineScope?=null
    private val web=WebView(context).apply {
        settings.javaScriptEnabled=true
        settings.allowFileAccess=false
        settings.allowContentAccess=false
        settings.blockNetworkLoads=true
        settings.domStorageEnabled=false
        addJavascriptInterface(Host(),"HarnessNative")
        webViewClient=object:WebViewClient() {
            override fun onPageFinished(view:WebView,url:String) {
                if(url=="file:///android_asset/engine.html")view.evaluateJavascript("typeof HarnessCore === 'object' && !!crypto.subtle && typeof CompressionStream === 'function'") {available->
                    if(available=="true")ready.complete(Unit)
                    else ready.completeExceptionally(IllegalStateException("Update Android System WebView to run the learning engine on this phone"))
                }
            }
            override fun shouldOverrideUrlLoading(view:WebView,request:android.webkit.WebResourceRequest)=true
        }
        loadUrl("file:///android_asset/engine.html")
    }
    inner class Host {
        @JavascriptInterface fun request(raw:String):String {
            if(raw.length>80_000_000)return "This operation exceeds the phone's memory limit. Your local lesson is safe."
            val generation=active?:return "No lesson request is active"
            val owner=operationScope?:return "The lesson request has stopped"
            owner.launch {
                var id:String?=null
                try {
                    val msg=AppJson.parseToJsonElement(raw).jsonObject
                    id=msg.getValue("id").jsonPrimitive.content
                    check(active==generation) {"No lesson request is active"}
                    val result=when(msg.getValue("op").jsonPrimitive.content) {
                        "infer" -> {onProgress("Your answer is saved. ChatGPT is responding…");chatGpt.infer(msg.getValue("value").jsonObject)}
                        "persist" -> {persist(msg.getValue("value").jsonObject);buildJsonObject {put("ok",true)}}
                        "cloud","sync-state","sync-apply" -> syncOperation(msg.getValue("op").jsonPrimitive.content,msg.getValue("value").jsonObject)
                        else -> error("Unknown engine operation")
                    }
                    reply(id,result,null)
                } catch(e:CancellationException) {throw e}
                catch(e:Exception) {if(active==generation){if(id!=null)reply(id,JsonNull,e.message?:"Lesson request failed")else pending[generation]?.completeExceptionally(e)}}
            }
            return ""
        }
        @JavascriptInterface fun complete(id:String,value:String,error:String) {
            val request=pending.remove(id)?:return
            if(error.isNotBlank())request.completeExceptionally(IllegalStateException(error))
            else runCatching {AppJson.parseToJsonElement(value).jsonObject}.onSuccess(request::complete).onFailure(request::completeExceptionally)
        }
    }
    private fun reply(id:String,value:JsonElement,error:String?) {
        // This engine view is intentionally not attached to a UI. View.post would queue forever
        // waiting for attachment, so deliver bridge replies through the main dispatcher instead.
        scope.launch(Dispatchers.Main.immediate) {web.evaluateJavascript("HarnessCore.resolve(${JsonPrimitive(id)},$value,${error?.let(::JsonPrimitive)?:JsonNull})",null)}
    }
    suspend fun execute(command:JsonObject):JsonObject = withContext(Dispatchers.Main) {
        try {withTimeout(15000){ready.await()}}
        catch(e:TimeoutCancellationException){throw IllegalStateException("The learning engine did not start. Reopen LearnHarness and check Android System WebView is up to date.",e)}
        check(active==null){"A lesson is already running"}
        val id=newId();val answer=CompletableDeferred<JsonObject>();pending[id]=answer;active=id
        operationScope=CoroutineScope(scope.coroutineContext+SupervisorJob())
        val syncing=command["op"]?.jsonPrimitive?.content=="sync"
        try {web.evaluateJavascript("HarnessCore.execute(${JsonPrimitive(id)},$command)",null);withTimeout(if(syncing)120000 else 600000){answer.await()}}
        catch(e:TimeoutCancellationException){
            ready=CompletableDeferred();web.loadUrl("file:///android_asset/engine.html")
            throw IllegalStateException(if(syncing)"Sync took too long. Your local lessons are safe; try Sync now again." else "The tutor took too long. Your saved input is kept; retry when ready.",e)
        }
        catch(e:CancellationException){ready=CompletableDeferred();web.loadUrl("file:///android_asset/engine.html");throw e}
        finally {operationScope?.cancel();operationScope=null;active=null;pending.remove(id)}
    }
    fun close() {pending.values.forEach {it.cancel()};web.destroy()}
}
