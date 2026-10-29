package ai.learnharness.android.data

import android.content.Context
import android.util.AtomicFile
import ai.learnharness.android.domain.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

@Serializable data class SharedEntry(val record:JsonObject,val view:RemoteSession)
@Serializable data class SharedData(val version:Int=1,val entries:List<SharedEntry> = emptyList(),val graph:List<JsonObject> = emptyList(),val ledgers:Map<String,JsonArray> = emptyMap(),val migratedLegacyIds:List<String> = emptyList())
/** Keep a learner on their own branch when concurrent PC edits win the original session ID. */
internal fun selectionAfterSync(previous:SharedData,next:SharedData,selected:String?):String? {
    if(selected==null)return null
    val before=previous.entries.find {it.view.id==selected}?:return selected
    val history=before.record["messages"]?.jsonArray.orEmpty()
    fun preserves(entry:SharedEntry):Boolean {
        val candidate=entry.record["messages"]?.jsonArray.orEmpty()
        return candidate.size>=history.size && history.indices.all {history[it]==candidate[it]}
    }
    val original=next.entries.find {it.view.id==selected}
    if(history.isEmpty()||original?.let(::preserves)==true)return original?.view?.id
    return next.entries.sortedBy {it.view.id}.firstOrNull {it.view.id.startsWith(selected.take(80)+"_conflict_")&&preserves(it)}?.view?.id?:original?.view?.id
}
fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
fun atomicSave(file:File,value:String) {
    file.parentFile?.mkdirs();val atomic=AtomicFile(file);val out=atomic.startWrite()
    try {out.write(value.toByteArray());atomic.finishWrite(out)}catch(e:Exception){atomic.failWrite(out);throw e}
}
class SharedLibrary(context:Context) {
    private val file=File(context.filesDir,"shared-library-v1.json")
    fun load():SharedData {
        if(!file.exists()&&!File(file.path+".bak").exists())return SharedData()
        val text=AtomicFile(file).openRead().bufferedReader().use {it.readText()}
        require(text.length<=80_000_000){"Shared library exceeds the size limit"}
        return AppJson.decodeFromString<SharedData>(text).also {require(it.version==1);require(it.entries.map {e->e.view.id}.distinct().size==it.entries.size)}
    }
    fun save(data:SharedData) {atomicSave(file,AppJson.encodeToString(data))}
    fun backup() {if(file.exists()) file.copyTo(File(file.parentFile,"shared-library-before-sync-${System.currentTimeMillis()}.json"))}
}
