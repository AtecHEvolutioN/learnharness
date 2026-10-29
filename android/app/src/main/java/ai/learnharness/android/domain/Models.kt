package ai.learnharness.android.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.UUID

val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
fun newId(): String = UUID.randomUUID().toString()

@Serializable data class PlanNode(val id: String, val title: String, val summary: String = "", val prerequisites: List<String> = emptyList())
@Serializable data class Concept(val id: String, val title: String, val statement: String, val prerequisites: List<String> = emptyList())
@Serializable data class TutorTurn(
    val phase: String = "measure", val text: String, val question: String = "",
    val nodeId: String? = null, val plan: List<PlanNode> = emptyList(), val concepts: List<Concept> = emptyList()
)
@Serializable data class Message(val id: String = newId(), val role: String, val text: String,
    val thinking: String = "", val confidence: Int? = null, val turn: TutorTurn? = null)
@Serializable data class Session(val id: String = newId(), val title: String,
    val mode: String = "learn", val createdAt: Long = System.currentTimeMillis(),
    val messages: List<Message> = emptyList(), val sample: Boolean = false) {
    val plan: List<PlanNode> get() = messages.mapNotNull { it.turn }.lastOrNull { it.plan.isNotEmpty() }?.plan.orEmpty()
    val phase: String get() = messages.lastOrNull { it.turn != null }?.turn?.phase ?: "measure"
    fun status(nodeId: String): String {
        val tags = messages.mapNotNull { it.turn?.nodeId }.filter { id -> plan.any { it.id == id } }
        return when { tags.lastOrNull() == nodeId -> "Current"; nodeId in tags -> "Visited"; else -> "Upcoming" }
    }
}
@Serializable data class LearningData(val version: Int = 1, val sessions: List<Session> = emptyList())
@Serializable data class ProviderSettings(val baseUrl: String = "https://openrouter.ai/api/v1", val model: String = "")
data class GraphEntry(val key: String, val sessionId: String, val sessionTitle: String, val concept: Concept)

fun LearningData.graph(): List<GraphEntry> = sessions.flatMap { session ->
    session.messages.flatMap { it.turn?.concepts.orEmpty() }.distinctBy { it.id }
        .map { GraphEntry("${session.id}:${it.id}", session.id, session.title, it) }
}

fun validateEndpoint(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null
}.getOrDefault(false)

fun validateTurn(turn: TutorTurn): TutorTurn {
    require(turn.phase in setOf("measure", "plan", "teach", "practice", "reflect")) { "Unknown lesson phase" }
    require(turn.text.isNotBlank() && turn.text.length <= 40000) { "The provider returned an empty or oversized lesson" }
    require(turn.question.length <= 12000 && turn.plan.size <= 40 && turn.concepts.size <= 40) { "Lesson is too large" }
    val ids = turn.plan.map { it.id }
    require(ids.distinct().size == ids.size && ids.none { it.isBlank() }) { "Duplicate or empty plan IDs" }
    turn.plan.forEach { node ->
        require(node.title.isNotBlank() && node.prerequisites.all { it in ids && it != node.id }) { "Invalid prerequisite" }
    }
    val done = mutableSetOf<String>()
    fun visit(id: String, path: Set<String>) {
        require(id !in path) { "Cyclic lesson plan" }
        if (id in done) return
        turn.plan.first { it.id == id }.prerequisites.forEach { visit(it, path + id) }
        done += id
    }
    ids.forEach { visit(it, emptySet()) }
    require(turn.concepts.all { it.id.isNotBlank() && it.title.isNotBlank() && it.statement.isNotBlank() }) { "Invalid knowledge entry" }
    return turn
}

fun parseTurn(raw: String): TutorTurn {
    val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    return validateTurn(AppJson.decodeFromString<TutorTurn>(clean))
}

fun decodeBackup(raw: String): LearningData {
    require(raw.length <= 5_000_000) { "Backup exceeds 5 MB" }
    val data = AppJson.decodeFromString<LearningData>(raw)
    require(data.version == 1) { "Unsupported backup version" }
    require(data.sessions.size <= 1000 && data.sessions.map { it.id }.distinct().size == data.sessions.size) { "Invalid session list" }
    data.sessions.forEach { session ->
        require(session.id.isNotBlank() && session.title.isNotBlank() && session.title.length <= 500 && session.mode in setOf("learn", "practice"))
        require(session.messages.size <= 2000 && session.messages.map { it.id }.distinct().size == session.messages.size)
        session.messages.forEach {
            require(it.role in setOf("user", "assistant") && it.text.length <= 40000 && it.thinking.length <= 12000)
            require(it.confidence == null || it.confidence in 1..5)
            it.turn?.let(::validateTurn)
        }
    }
    return data
}

fun mergeBackup(current: LearningData, incoming: LearningData): LearningData = current.copy(
    sessions = current.sessions + incoming.sessions.filter { incomingSession -> current.sessions.none { it.id == incomingSession.id } }
)
