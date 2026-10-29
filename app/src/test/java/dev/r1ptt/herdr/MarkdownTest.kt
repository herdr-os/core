package dev.r1ptt.herdr

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import dev.r1ptt.App
import dev.r1ptt.herdr.ui.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = App::class)
class MarkdownTest {
    private val reply = """
        ## Done
        The **route** now returns `201`, see [the PR](https://example.com/1).

        - fixed *users.ts*
        - added a test
        1. run it

        | File | Lines |
        |------|-------|
        | users.ts | 12 |

        ```
        npm test **not bold**
        ```
    """.trimIndent()

    @Test fun `agent markdown reads without its marks`() {
        assertEquals(
            "Done\nThe route now returns 201, see the PR.\n\n•  fixed users.ts\n•  added a test\n1.  run it\n\nFile  ·  Lines\nusers.ts  ·  12\n\nnpm test **not bold**",
            Markdown.plain(reply),
        )
    }

    @Test fun `bold, italic and code become spans, and code blocks stay literal`() {
        val text = Markdown.render(reply, 0xFF000000.toInt()) as Spanned
        fun styled(word: String, style: Int) = text.getSpans(0, text.length, StyleSpan::class.java)
            .any { it.style == style && text.substring(text.getSpanStart(it), text.getSpanEnd(it)) == word }
        assertTrue("bold", styled("route", Typeface.BOLD))
        assertTrue("italic", styled("users.ts", Typeface.ITALIC))
        assertTrue("heading", styled("Done", Typeface.BOLD))
        val mono = text.getSpans(0, text.length, TypefaceSpan::class.java).map { text.substring(text.getSpanStart(it), text.getSpanEnd(it)) }
        assertTrue(mono.toString(), "201" in mono && "npm test **not bold**" in mono)
    }
}
