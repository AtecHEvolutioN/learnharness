package ai.learnharness.android.data

import android.content.Context
import android.util.AtomicFile
import ai.learnharness.android.domain.*
import java.io.File
import kotlinx.serialization.encodeToString

class LearningStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "learning-v1.json"))
    fun load(): LearningData = if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) LearningData()
        else decodeBackup(file.openRead().bufferedReader().use { it.readText() })
    fun save(data: LearningData) {
        val encoded = AppJson.encodeToString(data)
        require(encoded.length <= 5_000_000) { "Local library is full. Export a backup and remove older sessions." }
        decodeBackup(encoded) // Never write a library that the next app launch cannot read.
        val output = file.startWrite()
        try { output.write(encoded.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
}
