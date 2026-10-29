package ai.learnharness.android.data

import android.content.Context
import android.util.AtomicFile
import ai.learnharness.android.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.util.Base64

@Serializable data class SyncConfig(val version:Int=1,val url:String,val token:String,val key:String)
class CloudSync(private val context:Context) {
    private val secrets=SecretStore(context,"cloud-sync-v1")
    private val stateFile=File(context.filesDir,"sync-state-v1.json")
    private val network=Network()
    fun configured()=secrets.hasKey()
    fun config():SyncConfig=AppJson.decodeFromString(secrets.readKey())
    fun setup(raw:String) {
        val decoded=if(raw.trim().startsWith("lh-sync-v1:"))String(Base64.getDecoder().decode(raw.trim().removePrefix("lh-sync-v1:")))else raw
        val next=AppJson.decodeFromString<SyncConfig>(decoded)
        require(next.version==1 && validateEndpoint(next.url) && next.token.matches(Regex("[a-f0-9]{64}")) && Base64.getDecoder().decode(next.key).size==32){"Invalid LearnHarness sync setup code"}
        if(configured())require(config()==next){"This phone already has a different sync vault. Export your library before changing it."}
        secrets.save(ProviderSettings(next.url,"sync"),AppJson.encodeToString(next))
    }
    fun bootstrap() {
        val file=File(context.filesDir,"sync-bootstrap.json")
        if(file.exists()){setup(file.readText());check(file.delete())}
    }
    fun state():JsonElement = if(stateFile.exists()) AppJson.parseToJsonElement(AtomicFile(stateFile).openRead().bufferedReader().use {it.readText()}) else JsonNull
    suspend fun saveState(value:JsonObject)=withContext(Dispatchers.IO){atomicSave(stateFile,value.toString())}
    suspend fun request(value:JsonObject):JsonObject {
        val config=config()
        return AppJson.parseToJsonElement(network.request(config.url.trimEnd('/')+"/api/sync",config.token,value.toString())).jsonObject
    }
}
