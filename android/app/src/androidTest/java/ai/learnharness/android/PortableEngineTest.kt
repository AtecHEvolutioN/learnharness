package ai.learnharness.android

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.learnharness.android.data.*
import ai.learnharness.android.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import java.security.SecureRandom

/** Uses the real device WebView and fake in-memory storage, never the user's library or account. */
@RunWith(AndroidJUnit4::class)
class PortableEngineTest {
    @Test fun largeDesktopHistoryCanSubmitThroughTheNativeBridge()=runBlocking {
        withTimeout(30000) {
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
            var reachedPersistence=false
            val engine=withContext(Dispatchers.Main){PortableEngine(context,ChatGpt(context),scope,{
                reachedPersistence=true;throw IllegalStateException("Fixture stopped before AI or disk write")
            },{_,_->error("Unexpected sync operation")})}
            try {
                val created=engine.execute(buildJsonObject{put("op","new");put("title","Large history fixture")}).getValue("record").jsonObject
                val history=JsonArray((0..110).map {index->buildJsonObject {put("id","message-$index");put("role","user");put("content","Saved lesson text ".repeat(600));put("timestamp",1)}})
                val record=JsonObject(created+mapOf("messages" to history))
                val failure=runCatching {engine.execute(buildJsonObject {put("op","turn");put("record",record);put("input",buildJsonObject {put("type","text");put("text","Continue")})})}.exceptionOrNull()
                assertTrue(reachedPersistence)
                assertTrue(failure?.message.orEmpty().contains("Fixture stopped"))
            } finally {withContext(Dispatchers.Main){engine.close()};scope.cancel()}
        }
    }
    @Test fun bundledEngineAndEncryptedSyncRunWithoutPcOrNetwork()=runBlocking {
        withTimeout(60000) {
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
            val blobs=mutableMapOf<String,JsonElement>()
            var applied:JsonObject?=null
            val engine=withContext(Dispatchers.Main){PortableEngine(context,ChatGpt(context),scope,{}, {op,value->
                android.util.Log.i("HarnessEngineTest","Host operation: $op")
                when(op) {
                    "sync-state"->JsonNull
                    "sync-apply"->{applied=value;JsonNull}
                    "cloud"->when(value.getValue("op").jsonPrimitive.content) {
                        "put"->{blobs[value.getValue("id").jsonPrimitive.content]=value.getValue("blob");buildJsonObject{put("ok",true)}}
                        "list"->buildJsonObject{put("ids",JsonArray(blobs.keys.map(::JsonPrimitive)))}
                        "get"->buildJsonObject{put("blob",blobs.getValue(value.getValue("id").jsonPrimitive.content))}
                        else->error("Unexpected cloud operation")
                    }
                    else->error("Unexpected engine operation")
                }
            })}
            try {
                android.util.Log.i("HarnessEngineTest","Creating native session")
                val created=engine.execute(buildJsonObject{put("op","new");put("title","Runtime fixture")})
                android.util.Log.i("HarnessEngineTest","Session created; testing encrypted upload")
                val record=created.getValue("record").jsonObject
                val id=record.getValue("id").jsonPrimitive.content
                val key=Base64.getEncoder().encodeToString(ByteArray(32).also {SecureRandom().nextBytes(it)})
                engine.execute(buildJsonObject{put("op","sync");put("key",key);put("state",JsonNull);put("documents",buildJsonObject{put("chat:$id",record)})})
                assertEquals(1,blobs.size)
                assertFalse(blobs.values.first().toString().contains("Runtime fixture"))
                android.util.Log.i("HarnessEngineTest","Upload complete; testing fresh-device download")
                // A fresh second device decrypts the same cloud revision without any local record.
                applied=null
                engine.execute(buildJsonObject{put("op","sync");put("key",key);put("state",JsonNull);put("documents",buildJsonObject{})})
                assertEquals("Runtime fixture",applied!!.getValue("entries").jsonArray.single().jsonObject.getValue("view").jsonObject.getValue("title").jsonPrimitive.content)
            } finally {withContext(Dispatchers.Main){engine.close()};scope.cancel()}
        }
    }
}
