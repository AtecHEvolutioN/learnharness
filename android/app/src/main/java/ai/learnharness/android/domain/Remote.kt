package ai.learnharness.android.domain

import kotlinx.serialization.Serializable

@Serializable data class Choice(val label: String, val value: String, val description: String = "")
@Serializable data class RemoteQuestion(val id: String, val kind: String, val question: String, val context: String? = null, val mode: String, val options: List<Choice> = emptyList())
@Serializable data class RemoteProblem(val id: String, val statement: String)
@Serializable data class RemoteSession(val id: String, val title: String, val mode: String = "learn", val createdAt: Long = 0,
    val messages: List<Message> = emptyList(), val plan: List<PlanNode> = emptyList(), val busy: Boolean = false,
    val error: String? = null, val needsContinuation: Boolean = false,
    val question: RemoteQuestion? = null, val problem: RemoteProblem? = null)
@Serializable data class RemoteSessions(val sessions: List<RemoteSession> = emptyList())
@Serializable data class RemoteGraph(val nodes: List<Concept> = emptyList())
