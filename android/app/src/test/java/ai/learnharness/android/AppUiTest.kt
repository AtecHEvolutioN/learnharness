package ai.learnharness.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.robolectric.annotation.GraphicsMode
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun sampleOpensAndKnowledgeIsAvailableWithoutApiKey() {
        compose.waitUntil(10000) {compose.onAllNodesWithTag("library").fetchSemanticsNodes().isNotEmpty()}
        File("build/previews").mkdirs()
        compose.runOnIdle {
            val root=compose.activity.window.decorView
            val bitmap=android.graphics.Bitmap.createBitmap(root.width,root.height,android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            File("build/previews/home.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        }
        compose.onNodeWithTag("library").performScrollToNode(hasText("Explore the orbit sample"))
        compose.onNodeWithText("Explore the orbit sample").performScrollTo().performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("Learning plan (3)").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Learning plan (3)").performClick()
        compose.onNodeWithText("Falling around Earth").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Knowledge",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("knowledge").performScrollToNode(hasText("Find an idea"))
        compose.onNodeWithText("Find an idea").performTextInput("Inertia")
        compose.onNodeWithTag("knowledge").performScrollToNode(hasText("Inertia") and !hasSetTextAction())
        compose.onNode(hasText("Inertia") and !hasSetTextAction()).assertIsDisplayed()
    }
}
