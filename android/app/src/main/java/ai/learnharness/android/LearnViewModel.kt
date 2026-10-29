package ai.learnharness.android

import android.app.Application
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.learnharness.android.data.*
import ai.learnharness.android.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

data class AppState(val loaded: Boolean = false, val data: LearningData = LearningData(), val busy: Boolean = false,
    val error: String? = null, val notice: String? = null, val selectedId: String? = null, val pcMode: Boolean = false,
    val provider: ProviderSettings = ProviderSettings(), val hasKey: Boolean = false, val pcUrl: String = "",
    val paired: Boolean = false, val online: Boolean = false, val remoteSessions: List<RemoteSession> = emptyList(),
    val remote: RemoteSession? = null, val remoteGraph: List<Concept> = emptyList(),
    val shared: SharedData = SharedData(), val chatGptConnected:Boolean=false, val chatGptEmail:String="",
    val chatGptModels:List<ChatGptModel> = emptyList(), val chatGptModel:String="",
    val syncConfigured:Boolean=false, val syncStatus:String="Cloud sync is not connected") {
    val native:RemoteSession? get()=shared.entries.find {it.view.id==selectedId}?.view
    val sharedGraph:List<Concept> get()=shared.graph.distinctBy {it["id"]}.mapNotNull {n->
        val id=n["id"]?.jsonPrimitive?.contentOrNull?:return@mapNotNull null
        val prerequisites=shared.ledgers["ledger:chains"].orEmpty().filter {it.jsonObject["conclusion"]?.jsonPrimitive?.contentOrNull==id}.flatMap {it.jsonObject["premises"]?.jsonArray.orEmpty().map {p->p.jsonPrimitive.content}}
        Concept(id,n["name"]?.jsonPrimitive?.contentOrNull?:id,n["statement"]?.jsonPrimitive?.contentOrNull.orEmpty(),prerequisites.distinct())
    }
}

class LearnViewModel(app: Application) : AndroidViewModel(app) {
    private val store = LearningStore(app)
    private val secrets = SecretStore(app)
    private val pcSecrets = SecretStore(app, "pc-connection")
    private val client = PcClient(pcSecrets)
    private val tutor = TutorClient()
    private val chatGpt = ChatGpt(app)
    private val library = SharedLibrary(app)
    private val cloud = CloudSync(app)
    private var sharedStorageHealthy=false
    private var lastSyncAttempt=0L
    private val engineDelegate=lazy { PortableEngine(app,chatGpt,viewModelScope,::acceptNative,::syncOperation){message->mutable.update {it.copy(notice=message)}} }
    private val engine by engineDelegate
    private val operation = Mutex()
    private val mutable = MutableStateFlow(AppState())
    private val foreground = MutableStateFlow(false)
    fun setForeground(visible:Boolean) {foreground.value=visible}
    val state = mutable.asStateFlow()
    private var active: Job? = null
    private var localStorageHealthy = false
    private val cache = AtomicFile(File(app.filesDir, "pc-session-cache.json"))
    init {
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { store.load() }
                localStorageHealthy = true
                val shared=withContext(Dispatchers.IO){library.load()};sharedStorageHealthy=true
                withContext(Dispatchers.IO){cloud.bootstrap()}
                val account=chatGpt.account()
                val previous = withContext(Dispatchers.IO) { runCatching { AppJson.decodeFromString<RemoteSession>(cache.openRead().bufferedReader().use { it.readText() }) }.getOrNull() }
                mutable.update { it.copy(loaded=false,busy=true, data=data, provider=secrets.settings(), hasKey=secrets.hasKey(),
                    pcUrl=if(pcSecrets.hasKey()) pcSecrets.settings().baseUrl else "", paired=pcSecrets.hasKey(), pcMode=false,
                    shared=shared,chatGptConnected=chatGpt.connected(),chatGptEmail=account.email,chatGptModel=account.selectedModel,
                    syncConfigured=cloud.configured(),syncStatus=if(cloud.configured())"Ready to sync" else "Cloud sync is not connected",
                    remote=previous, remoteSessions=listOfNotNull(previous)) }
                migrateEarlierLessons()
                mutable.update {it.copy(loaded=true,busy=false)}
            } catch(error:Exception) { mutable.update { it.copy(loaded=true,busy=false, error="Could not read your saved library. Existing files were kept. ${error.message}") } }
        }
    }
    private fun runTask(block: suspend () -> Unit) {
        if (state.value.busy) return
        active = viewModelScope.launch {
            operation.withLock {
                mutable.update { it.copy(busy=true, error=null, notice=null) }
                try { block(); if(cloud.configured())try {syncNow()}catch(e:CancellationException){throw e}catch(e:Exception){mutable.update {it.copy(syncStatus="Saved locally · sync will retry: ${e.message}")}} } catch(cancelled:CancellationException) { throw cancelled }
                catch(error:Exception) { mutable.update { it.copy(error=error.message ?: "Something went wrong",notice=null) } }
                finally { mutable.update { it.copy(busy=false) } }
            }
        }
    }
    fun dismissError() { mutable.update { it.copy(error=null, notice=null) } }
    fun report(message: String) { mutable.update { it.copy(notice=message) } }
    fun cancel() { chatGpt.cancelSignIn();active?.cancel();report("Stopped. Your saved progress is kept.") }
    private suspend fun save(data: LearningData) {
        check(localStorageHealthy) { "Your saved library could not be read. Restore it before making changes; existing data has not been overwritten." }
        withContext(Dispatchers.IO) { store.save(data) }; mutable.update { it.copy(data=data) }
    }
    fun switchSource(pc: Boolean) { if (!state.value.busy) mutable.update { it.copy(pcMode=pc, selectedId=null, error=null) } }
    fun open(id: String?) {
        mutable.update { it.copy(selectedId=id, error=null) }
        if (id != null && state.value.pcMode) runTask { acceptRemote(client.session(id)) }
    }
    private suspend fun saveShared(data:SharedData) {
        check(sharedStorageHealthy){"Shared library could not be read. Existing files were kept."}
        withContext(Dispatchers.IO){library.save(data)}
        mutable.update {it.copy(shared=data)}
    }
    private suspend fun migrateEarlierLessons() {
        for(session in state.value.data.sessions.filterNot {it.id in state.value.shared.migratedLegacyIds}) {
            if(state.value.shared.entries.none {it.view.id=="phone_"+session.id}) {
                val result=engine.execute(buildJsonObject {put("op","migrate");put("session",AppJson.encodeToJsonElement(session))})
                acceptNative(result)
            }
            saveShared(state.value.shared.copy(migratedLegacyIds=(state.value.shared.migratedLegacyIds+session.id).distinct()))
        }
    }
    private suspend fun acceptNative(value:JsonObject) {
        val entry=SharedEntry(value.getValue("record").jsonObject,AppJson.decodeFromJsonElement(value.getValue("view")))
        val previous=state.value.shared
        val nodes=entry.record["mobileConcepts"]?.jsonArray.orEmpty().map {it.jsonObject}
        // Ledger rows with a repeated ID can carry additional provenance; retain those assertions.
        val graph=(previous.graph+nodes).distinct()
        val chains=(previous.ledgers["ledger:chains"].orEmpty()+entry.record["mobileChains"]?.jsonArray.orEmpty()).distinct()
        saveShared(previous.copy(entries=previous.entries.filterNot {it.view.id==entry.view.id}+entry,graph=graph,
            ledgers=previous.ledgers+mapOf("ledger:nodes" to JsonArray(graph),"ledger:chains" to JsonArray(chains))))
    }
    private suspend fun nativeTurn(input:JsonObject) {
        val entry=state.value.shared.entries.first {it.view.id==state.value.selectedId}
        mutable.update {it.copy(notice="Preparing your answer…")}
        engine.execute(buildJsonObject {put("op","turn");put("model",state.value.chatGptModel);put("record",entry.record);put("input",input);put("knowledge",JsonArray(state.value.shared.graph))})
        mutable.update {it.copy(notice="Lesson updated")}
    }
    private suspend fun syncOperation(op:String,value:JsonObject):JsonElement {
        when(op) {
            "cloud" -> return cloud.request(value)
            "sync-state" -> cloud.saveState(value)
            "sync-apply" -> {
                val previous=state.value
                val next=AppJson.decodeFromJsonElement<SharedData>(value).copy(migratedLegacyIds=previous.shared.migratedLegacyIds)
                val selected=if(previous.pcMode)previous.selectedId else selectionAfterSync(previous.shared,next,previous.selectedId)
                if(next!=previous.shared)withContext(Dispatchers.IO){library.backup()}
                saveShared(next)
                mutable.update {it.copy(selectedId=if(it.selectedId==previous.selectedId)selected else it.selectedId,notice=if(selected!=null&&selected!=previous.selectedId)"Both devices changed this lesson. Your phone version is open as a conflict copy." else it.notice)}
            }
            else -> error("Unknown sync operation")
        }
        return buildJsonObject {put("ok",true)}
    }
    private suspend fun syncNow() {
        if(!cloud.configured() || !sharedStorageHealthy)return
        lastSyncAttempt=System.currentTimeMillis()
        mutable.update {it.copy(syncStatus="Syncing…")}
        val data=state.value.shared
        val documents=buildJsonObject {
            data.entries.forEach {put("chat:${it.view.id}",it.record)}
            data.ledgers.forEach {(key,value)->put(key,value)}
            put("ledger:nodes",JsonArray(data.graph))
        }
        val result=engine.execute(buildJsonObject {put("op","sync");put("documents",documents);put("state",cloud.state());put("key",cloud.config().key)})
        val conflicts=result["conflicts"]?.jsonPrimitive?.intOrNull?:0
        mutable.update {it.copy(syncStatus=if(conflicts>0)"Synced · $conflicts conflict copies kept" else "All changes synced",syncConfigured=true)}
    }
    fun sync()=runTask { /* runTask syncs after local work */ }
    fun setupSync(code:String)=runTask {withContext(Dispatchers.IO){cloud.setup(code)};mutable.update {it.copy(syncConfigured=true)}}
    fun signInChatGpt()=runTask {
        val email=chatGpt.signIn(onProgress={stage->mutable.update {it.copy(notice=stage)}},awaitForeground={foreground.first {it}});val account=chatGpt.account()
        mutable.update {it.copy(chatGptConnected=true,chatGptEmail=email,chatGptModel=account.selectedModel,notice="ChatGPT connected. Choose a model below.")}
        val models=chatGpt.models();mutable.update {it.copy(chatGptModels=models)}
    }
    fun refreshModels()=runTask {val models=chatGpt.models();mutable.update {it.copy(chatGptModels=models)}}
    fun chooseModel(slug:String)=runTask {chatGpt.selectModel(slug);mutable.update {it.copy(chatGptModel=slug,notice="ChatGPT model saved")}}
    fun signOutChatGpt()=runTask {chatGpt.signOut();mutable.update {it.copy(chatGptConnected=false,chatGptEmail="",chatGptModel="",chatGptModels=emptyList())}}
    private suspend fun acceptRemote(session: RemoteSession) {
        withContext(Dispatchers.IO) {
            val out = cache.startWrite()
            try { out.write(AppJson.encodeToString(session).toByteArray()); cache.finishWrite(out) }
            catch(e:Exception) { cache.failWrite(out); throw e }
        }
        mutable.update { it.copy(remote=session, online=true, remoteSessions=(it.remoteSessions.filterNot { s -> s.id == session.id } + session).sortedByDescending { s -> s.createdAt }) }
    }
    fun start(title: String, practice: Boolean) = runTask {
        require(title.trim().isNotBlank() && title.length <= 500) { "Enter a learning goal up to 500 characters" }
        val mode = if(practice) "practice" else "learn"
        if (state.value.pcMode) {
            val id = client.create(title.trim(),mode)
            mutable.update { it.copy(selectedId=id) }
            acceptRemote(client.session(id))
            acceptRemote(client.send(id, buildJsonObject { put("type","text"); put("text",title.trim()) }))
        } else if(state.value.chatGptConnected) {
            require(state.value.chatGptModel.isNotBlank()){ "Choose your ChatGPT model in Settings" }
            val created=engine.execute(buildJsonObject {put("op","new");put("title",title.trim());put("kind",if(practice)"practice" else "teach")})
            val record=created.getValue("record").jsonObject
            acceptNative(engine.execute(buildJsonObject {put("op","project");put("record",record)}))
            mutable.update {it.copy(selectedId=record.getValue("id").jsonPrimitive.content)}
            nativeTurn(buildJsonObject {put("type","text");put("text",title.trim())})
        } else {
            require(state.value.hasKey){"Continue with ChatGPT in Settings to start an independent lesson"}
            val session = Session(title=title.trim(),mode=mode,messages=listOf(Message(role="user",text=title.trim())))
            save(state.value.data.copy(sessions=state.value.data.sessions + session))
            mutable.update { it.copy(selectedId=session.id) }; localTurn(session)
        }
    }
    fun sample() = runTask {
        val session = sampleSession(); save(state.value.data.copy(sessions=state.value.data.sessions + session))
        mutable.update { it.copy(selectedId=session.id, pcMode=false) }
    }
    private suspend fun localTurn(session: Session) {
        val turn = tutor.turn(secrets.settings(),secrets.readKey(),session,state.value.data.graph())
        val updated = session.copy(messages=session.messages + Message(role="assistant",text=turn.text,turn=turn))
        save(state.value.data.copy(sessions=state.value.data.sessions.map { if(it.id == updated.id) updated else it }))
    }
    fun send(text: String, thinking: String, confidence: Int) = runTask {
        require(text.isNotBlank() && text.length <= 12000 && thinking.length <= 12000) { "Enter an answer up to 12000 characters" }
        val s = state.value
        if (s.pcMode) acceptRemote(client.send(requireNotNull(s.selectedId), buildJsonObject { put("type","text");put("text",text + if(thinking.isNotBlank()) "\nMy thinking: $thinking" else "") }))
        else if(s.native!=null)nativeTurn(buildJsonObject {put("type","text");put("text",text+if(thinking.isNotBlank())"\nMy thinking: $thinking" else "")})
        else {
            val session = s.data.sessions.first { it.id == s.selectedId }.copy(messages=s.data.sessions.first { it.id == s.selectedId }.messages + Message(role="user",text=text,thinking=thinking,confidence=confidence))
            save(s.data.copy(sessions=s.data.sessions.map { if(it.id == session.id) session else it })); localTurn(session)
        }
    }
    fun answer(q: RemoteQuestion, values: List<String>, text: String, thinking: String, confidence: Int, dontKnow: Boolean) = runTask {
        val input=buildJsonObject {
            put("type","answer");put("toolCallId",q.id);put("answer",buildJsonObject {
                put("values",JsonArray(values.map(::JsonPrimitive)));put("thinking",thinking);put("confidence",confidence);put("dontKnow",dontKnow)
                if(q.mode == "text") put("text",text) else if(q.kind == "ask") put("other",text)
            })
        }
        if(!state.value.pcMode && state.value.native!=null)nativeTurn(input)
        else acceptRemote(client.send(requireNotNull(state.value.selectedId),input))
    }
    fun retry() = runTask {
        if(state.value.pcMode) acceptRemote(client.send(requireNotNull(state.value.selectedId),buildJsonObject { put("type","retry") }))
        else if(state.value.native!=null)nativeTurn(buildJsonObject {put("type","retry")})
        else localTurn(state.value.data.sessions.first { it.id == state.value.selectedId })
    }
    fun pcAction(type: String) = runTask {
        val input=buildJsonObject {put("type",type)}
        if(!state.value.pcMode && state.value.native!=null)nativeTurn(input)
        else acceptRemote(client.send(requireNotNull(state.value.selectedId),input))
    }
    fun delete(id: String) = runTask { save(state.value.data.copy(sessions=state.value.data.sessions.filterNot { it.id == id })); open(null) }
    fun saveProvider(url: String, model: String, key: String) = runTask {
        withContext(Dispatchers.IO) { secrets.save(ProviderSettings(url.trim(),model.trim()),key) }
        mutable.update { it.copy(provider=secrets.settings(),hasKey=secrets.hasKey(),notice="Provider saved") }
    }
    fun clearKey() = runTask { secrets.clearKey(); mutable.update { it.copy(hasKey=false,notice="API key removed") } }
    fun pair(url: String, token: String) = runTask {
        require(token.isBlank() || token.trim().matches(Regex("[a-fA-F0-9]{64}"))) { "Paste the 64-character token from your PC" }
        withContext(Dispatchers.IO) { pcSecrets.save(ProviderSettings(url.trim(),"pc"),token.trim()) }
        mutable.update { it.copy(pcUrl=url.trim(),paired=pcSecrets.hasKey()) }
        client.status(); mutable.update { it.copy(pcMode=true,online=true,selectedId=null,notice="Connected to your PC") }; refreshNow()
    }
    fun disconnect() = runTask {
        pcSecrets.clearKey(); withContext(Dispatchers.IO) { cache.delete() }
        mutable.update { it.copy(paired=false,pcMode=false,online=false,pcUrl="",selectedId=null,remote=null,remoteSessions=emptyList(),remoteGraph=emptyList()) }
    }
    private suspend fun refreshNow() {
        val list = client.sessions(); val graph = client.graph()
        mutable.update { it.copy(remoteSessions=list,remoteGraph=graph,online=true) }
        state.value.selectedId?.let { acceptRemote(client.session(it)) }
    }
    fun refresh() = runTask { refreshNow() }
    fun poll(): Job = viewModelScope.launch {
        while(isActive) {
            if(state.value.loaded&&cloud.configured()&&!state.value.busy&&System.currentTimeMillis()-lastSyncAttempt>300000&&operation.tryLock()) {
                mutable.update {it.copy(busy=true)}
                try {syncNow()}catch(e:CancellationException){throw e}catch(e:Exception){mutable.update {it.copy(syncStatus="Saved locally · waiting to sync")}}
                finally {mutable.update {it.copy(busy=false)};operation.unlock()}
            }
            if(state.value.pcMode && state.value.paired && !state.value.busy && operation.tryLock()) {
                try { refreshNow() } catch(cancel:CancellationException) { throw cancel }
                catch(_:Exception) { mutable.update { it.copy(online=false) } }
                finally { operation.unlock() }
            }
            delay(if(state.value.remote?.busy == true) 2500 else 10000)
        }
    }
    fun export(): String = buildJsonObject {
        put("format","learnharness-phone-backup");put("version",2)
        put("legacy",AppJson.encodeToJsonElement(state.value.data));put("shared",AppJson.encodeToJsonElement(state.value.shared))
    }.toString()
    fun import(raw: String) = runTask {
        require(raw.length<=80_000_000){"Backup exceeds 80 MB"}
        val obj=AppJson.parseToJsonElement(raw).jsonObject
        if(obj["format"]?.jsonPrimitive?.contentOrNull=="learnharness-phone-backup") {
            require(obj["version"]?.jsonPrimitive?.int==2){"Unsupported backup version"}
            val legacy=decodeBackup(obj.getValue("legacy").toString())
            val incoming=AppJson.decodeFromJsonElement<SharedData>(obj.getValue("shared"))
            require(incoming.version==1){"Unsupported shared library"}
            val additions=mutableListOf<SharedEntry>()
            for(entry in incoming.entries) {
                val existing=state.value.shared.entries.find {it.view.id==entry.view.id}
                if(existing?.record==entry.record)continue
                val record=if(existing==null)entry.record else JsonObject(entry.record+mapOf("id" to JsonPrimitive(newId()),"title" to JsonPrimitive((entry.view.title+" (imported copy)").take(500))))
                val projected=engine.execute(buildJsonObject {put("op","project");put("record",record)})
                additions+=SharedEntry(projected.getValue("record").jsonObject,AppJson.decodeFromJsonElement(projected.getValue("view")))
            }
            withContext(Dispatchers.IO){library.backup()}
            val current=state.value.shared
            val ledgers=(current.ledgers.keys+incoming.ledgers.keys).associateWith {key->JsonArray((current.ledgers[key].orEmpty()+incoming.ledgers[key].orEmpty()).distinct())}
            saveShared(current.copy(entries=current.entries+additions,graph=(current.graph+incoming.graph).distinct(),ledgers=ledgers,migratedLegacyIds=(current.migratedLegacyIds+incoming.migratedLegacyIds).distinct()))
            save(mergeBackup(state.value.data,legacy))
        } else save(mergeBackup(state.value.data,decodeBackup(raw)))
        migrateEarlierLessons();report("Backup imported; existing sessions were kept")
    }
    override fun onCleared() {chatGpt.cancelSignIn();if(engineDelegate.isInitialized())engine.close();super.onCleared()}
}
