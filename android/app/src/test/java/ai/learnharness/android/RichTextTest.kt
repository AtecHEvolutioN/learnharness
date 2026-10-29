package ai.learnharness.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.Spanned
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.noties.markwon.ext.tables.TableRowSpan
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RichTextTest {
    @Test fun markdownTableHasRenderedRowsAndWrappedCells() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val view=TextView(context).apply {textSize=17f;setTextColor(0xFFE1E1DC.toInt());setBackgroundColor(0xFF141414.toInt());setPadding(24,24,24,24)}
        lessonMarkdown(context,view.textSize).setMarkdown(view,"""
            ## Compare the ideas

            | Concept | Meaning | Example |
            | :--- | :--- | :--- |
            | **Fraction** | A part of a whole, with a numerator and denominator. | 1/2 |
            | **Decimal** | A number written using powers of ten. | 0.5 |

            Both can describe the same quantity.
        """.trimIndent())
        val rendered=view.text as Spanned
        assertEquals(3,rendered.getSpans(0,rendered.length,TableRowSpan::class.java).size)
        assertFalse(rendered.toString().contains("| :---"))
        val controller=org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        controller.get().setContentView(view)
        // Table spans learn their column width on first draw, then request a second layout.
        repeat(3) {
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(760,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED))
            view.layout(0,0,view.measuredWidth,view.measuredHeight)
            view.draw(Canvas(Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)))
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertTrue(view.measuredHeight>200)
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/previews").mkdirs()
        File("build/previews/markdown-table.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        controller.pause().stop().destroy()
    }
}
