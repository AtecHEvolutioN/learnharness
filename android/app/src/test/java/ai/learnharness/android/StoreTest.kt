package ai.learnharness.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ai.learnharness.android.data.LearningStore
import ai.learnharness.android.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class StoreTest {
    @Test fun reloadRestoresTranscriptAndQuestion() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val expected=LearningData(sessions=listOf(sampleSession()))
        LearningStore(context).save(expected)
        assertEquals(expected,LearningStore(context).load())
        assertTrue(LearningStore(context).load().sessions.first().messages.last().turn!!.question.isNotBlank())
    }
    @Test fun corruptStoreIsNotSilentlyReset() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val file=File(context.filesDir,"learning-v1.json")
        file.writeText("{corrupt")
        assertThrows(Exception::class.java) {LearningStore(context).load()}
        assertEquals("{corrupt",file.readText())
        file.delete()
    }
}
