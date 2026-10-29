package ai.learnharness.android

import ai.learnharness.android.data.*
import ai.learnharness.android.domain.RemoteSession
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SyncSelectionTest {
    private fun entry(id:String,vararg messages:String)=SharedEntry(buildJsonObject {put("messages",JsonArray(messages.map(::JsonPrimitive)))},RemoteSession(id=id,title="Lesson"))
    @Test fun keepPhoneAnswerVisibleWhenPcBranchWinsTheOriginalId() {
        val previous=SharedData(entries=listOf(entry("lesson","start","phone answer")))
        val next=SharedData(entries=listOf(entry("lesson","start","PC answer"),entry("lesson_conflict_abc","start","phone answer","phone feedback")))
        assertEquals("lesson_conflict_abc",selectionAfterSync(previous,next,"lesson"))
    }
    @Test fun ordinaryContinuationKeepsSelectionAndDeletionClosesTheSession() {
        val previous=SharedData(entries=listOf(entry("lesson","start")))
        assertEquals("lesson",selectionAfterSync(previous,SharedData(entries=listOf(entry("lesson","start","next"))),"lesson"))
        assertNull(selectionAfterSync(previous,SharedData(),"lesson"))
    }
}
