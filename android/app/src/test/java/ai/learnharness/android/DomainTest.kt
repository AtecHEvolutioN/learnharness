package ai.learnharness.android

import ai.learnharness.android.domain.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString

class DomainTest {
    @Test fun planRejectsCyclesAndMissingDependencies() {
        val cycle=TutorTurn(text="test",plan=listOf(PlanNode("a","A",prerequisites=listOf("b")),PlanNode("b","B",prerequisites=listOf("a"))))
        assertThrows(IllegalArgumentException::class.java) {validateTurn(cycle)}
        assertThrows(IllegalArgumentException::class.java) {validateTurn(TutorTurn(text="test",plan=listOf(PlanNode("a","A",prerequisites=listOf("missing")))))}
    }
    @Test fun backupRoundTripAndMergePreserveExistingSession() {
        val session=sampleSession()
        val data=LearningData(sessions=listOf(session))
        assertEquals(data,decodeBackup(AppJson.encodeToString(data)))
        assertEquals(data,mergeBackup(data,data.copy(sessions=listOf(session.copy(title="Conflict")))))
        assertEquals(3,data.graph().size)
    }
    @Test fun unknownBackupVersionFailsWithoutMutation() {
        assertThrows(IllegalArgumentException::class.java) {decodeBackup("{\"version\":2,\"sessions\":[]}")}
    }
    @Test fun endpointsRequireTlsAndNoCredentialOrQuery() {
        assertTrue(validateEndpoint("https://my-pc.example.ts.net"))
        assertFalse(validateEndpoint("http://localhost"))
        assertFalse(validateEndpoint("https://secret@example.com"))
        assertFalse(validateEndpoint("https://example.com?key=secret"))
        assertFalse(validateEndpoint("file:///etc/passwd"))
    }
    @Test fun planStatusIsDerivedFromTaggedQuestions() {
        val s=sampleSession()
        assertEquals("Current",s.status("orbit"))
        assertEquals("Upcoming",s.status("inertia"))
        val next=s.copy(messages=s.messages+Message(role="assistant",text="Revisit",turn=TutorTurn(text="Revisit",nodeId="inertia")))
        assertEquals("Visited",next.status("orbit"))
        assertEquals("Current",next.status("inertia"))
    }
    @Test fun parserRejectsMalformedOrEmptyModelResponse() {
        assertThrows(Exception::class.java) {parseTurn("not JSON")}
        assertThrows(IllegalArgumentException::class.java) {parseTurn("{\"text\":\"\"}")}
        assertEquals("Hello",parseTurn("```json\n{\"text\":\"Hello\"}\n```").text)
    }
}
