package ai.learnharness.android

import android.widget.TextView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.movement.MovementMethodPlugin

internal fun lessonMarkdown(context:android.content.Context,textSize:Float):Markwon {
    val density=context.resources.displayMetrics.density
    return Markwon.builder(context).usePlugin(MarkwonInlineParserPlugin.create())
        .usePlugin(JLatexMathPlugin.create(textSize){it.inlinesEnabled(true)})
        .usePlugin(TablePlugin.create(TableTheme.buildWithDefaults(context)
            .tableCellPadding((8*density).toInt()).tableBorderWidth(density.toInt().coerceAtLeast(1))
            .tableBorderColor(0xFF44443E.toInt()).tableHeaderRowBackgroundColor(0xFF302C20.toInt())
            .tableOddRowBackgroundColor(0xFF222222.toInt()).tableEvenRowBackgroundColor(0xFF181818.toInt()).build()))
        .usePlugin(MovementMethodPlugin.create(TableAwareMovementMethod.create())).build()
}

/** Native TextView rendering; imported Markdown cannot run scripts or load remote images. */
@Composable fun RichText(text:String,modifier:Modifier=Modifier) {
    AndroidView(modifier=modifier,factory={context->TextView(context).apply {
        textSize=17f;setTextColor(0xFFE1E1DC.toInt());setLinkTextColor(0xFFE3CA80.toInt())
        setTextIsSelectable(true);setLineSpacing(5f,1.1f)
        tag=lessonMarkdown(context,textSize)
    }},update={view->
        // Markwon uses $$ for inline math. Convert only single-dollar spans outside code fences.
        val source=text.split("```").mapIndexed {i,part->if(i%2==0)part.replace(Regex("(?<![\\\\$])\\$(?!\\$)([^$\\n]+?)(?<!\\\\)\\$(?!\\$)")){m->"\$\$${m.groupValues[1]}\$\$"}else part}.joinToString("```")
        (view.tag as Markwon).setMarkdown(view,source)
    })
}
