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
    val remote: RemoteSession? = null, val remoteGraph: List<Concept> = emptyList())

class LearnViewModel(app: Application) : AndroidViewModel(app) {
    private val store = LearningStore(app)
    private val secrets = SecretStore(app)
    private val pcSecrets = SecretStore(app, "pc-connection")
    private val client = PcClient(pcSecrets)
    private val tutor = TutorClient()
    private val operation = Mutex()
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    private var active: Job? = null
    private var localStorageHealthy = false
    private val cache = AtomicFile(File(app.filesDir, "pc-session-cache.json"))
    init {
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { store.load() }
                localStorageHealthy = true
                val previous = withContext(Dispatchers.IO) { runCatching { AppJson.decodeFromString<RemoteSession>(cache.openRead().bufferedReader().use { it.readText() }) }.getOrNull() }
                mutable.update { it.copy(loaded=true, data=data, provider=secrets.settings(), hasKey=secrets.hasKey(),
                    pcUrl=if(pcSecrets.hasKey()) pcSecrets.settings().baseUrl else "", paired=pcSecrets.hasKey(), pcMode=pcSecrets.hasKey(),
                    remote=previous, remoteSessions=listOfNotNull(previous)) }
            } catch(error:Exception) { mutable.update { it.copy(loaded=true, error="Could not read your saved library. Existing files were kept. ${error.message}") } }
        }
    }
    private fun runTask(block: suspend () -> Unit) {
        if (state.value.busy) return
        active = viewModelScope.launch {
            operation.withLock {
                mutable.update { it.copy(busy=true, error=null, notice=null) }
                try { block() } catch(cancelled:CancellationException) { throw cancelled }
                catch(error:Exception) { mutable.update { it.copy(error=error.message ?: "Something went wrong") } }
                finally { mutable.update { it.copy(busy=false) } }
            }
        }
    }
    fun dismissError() { mutable.update { it.copy(error=null, notice=null) } }
    fun report(message: String) { mutable.update { it.copy(notice=message) } }
    fun cancel() { active?.cancel(); report("Request stopped on this phone. A PC turn may still finish; refresh before sending again.") }
    private suspend fun save(data: LearningData) {
        check(localStorageHealthy) { "Your saved library could not be read. Restore it before making changes; existing data has not been overwritten." }
        withContext(Dispatchers.IO) { store.save(data) }; mutable.update { it.copy(data=data) }
    }
    fun switchSource(pc: Boolean) { if (!state.value.busy) mutable.update { it.copy(pcMode=pc, selectedId=null, error=null) } }
    fun open(id: String?) {
        mutable.update { it.copy(selectedId=id, error=null) }
        if (id != null && state.value.pcMode) runTask { acceptRemote(client.session(id)) }
    }
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
        } else {
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
        else {
            val session = s.data.sessions.first { it.id == s.selectedId }.copy(messages=s.data.sessions.first { it.id == s.selectedId }.messages + Message(role="user",text=text,thinking=thinking,confidence=confidence))
            save(s.data.copy(sessions=s.data.sessions.map { if(it.id == session.id) session else it })); localTurn(session)
        }
    }
    fun answer(q: RemoteQuestion, values: List<String>, text: String, thinking: String, confidence: Int, dontKnow: Boolean) = runTask {
        acceptRemote(client.send(requireNotNull(state.value.selectedId),buildJsonObject {
            put("type","answer");put("toolCallId",q.id);put("answer",buildJsonObject {
                put("values",JsonArray(values.map(::JsonPrimitive)));put("thinking",thinking);put("confidence",confidence);put("dontKnow",dontKnow)
                if(q.mode == "text") put("text",text) else if(q.kind == "ask") put("other",text)
            })
        }))
    }
    fun retry() = runTask {
        if(state.value.pcMode) acceptRemote(client.send(requireNotNull(state.value.selectedId),buildJsonObject { put("type","retry") }))
        else localTurn(state.value.data.sessions.first { it.id == state.value.selectedId })
    }
    fun pcAction(type: String) = runTask { acceptRemote(client.send(requireNotNull(state.value.selectedId),buildJsonObject { put("type",type) })) }
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
            if(state.value.pcMode && state.value.paired && !state.value.busy && operation.tryLock()) {
                try { refreshNow() } catch(cancel:CancellationException) { throw cancel }
                catch(_:Exception) { mutable.update { it.copy(online=false) } }
                finally { operation.unlock() }
            }
            delay(if(state.value.remote?.busy == true) 2500 else 10000)
        }
    }
    fun export(): String = AppJson.encodeToString(state.value.data)
    fun import(raw: String) = runTask { save(mergeBackup(state.value.data,decodeBackup(raw))); report("Backup imported; existing sessions were kept") }
}
