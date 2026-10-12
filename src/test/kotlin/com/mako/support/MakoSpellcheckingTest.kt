package com.mako.support

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.spellchecker.SpellCheckerSeveritiesProvider
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.mako.support.lang.MakoLanguage
import com.mako.support.spellcheck.MakoSpellcheckingStrategy

class MakoSpellcheckingTest : MakoPlatformTestCase() {
    fun testTokenizerSkipsCodeAndSpellsText() {
        val word = "zzqxwronk"
        val text = """
            $word
            ## $word
            <%doc>$word</%doc>
            <%page title="$word" args="${"$"}{$word}"/>
            ${"$"}{$word}
            ${"$"}{name | $word}
            <% $word %>
            % $word
        """.trimIndent()
        myFixture.configureByText("page.mako", text)
        val strategy = MakoSpellcheckingStrategy()
        assertSame(SpellcheckingStrategy.TEXT_TOKENIZER, tokenizer(strategy, text, word, occurrence = 0))
        assertSame(SpellcheckingStrategy.TEXT_TOKENIZER, tokenizer(strategy, text, word, occurrence = 1))
        assertSame(SpellcheckingStrategy.TEXT_TOKENIZER, tokenizer(strategy, text, word, occurrence = 2))
        assertSame(SpellcheckingStrategy.TEXT_TOKENIZER, tokenizer(strategy, text, word, occurrence = 3))
        assertSame(SpellcheckingStrategy.EMPTY_TOKENIZER, tokenizer(strategy, text, word, occurrence = 4))
        assertSame(SpellcheckingStrategy.EMPTY_TOKENIZER, tokenizer(strategy, text, word, occurrence = 5))
        assertSame(SpellcheckingStrategy.EMPTY_TOKENIZER, tokenizer(strategy, text, word, occurrence = 6))
        assertSame(SpellcheckingStrategy.EMPTY_TOKENIZER, tokenizer(strategy, text, word, occurrence = 7))
        assertSame(SpellcheckingStrategy.EMPTY_TOKENIZER, tokenizer(strategy, text, word, occurrence = 8))
    }

    fun testMisspelledWordsAreFlaggedOnlyInTextAndComments() {
        val word = "zzqxwronk"
        val text = "$word\n## $word\n\${$word}\n<% $word %>"
        @Suppress("UNCHECKED_CAST")
        val inspection = Class.forName("com.intellij.grazie.spellcheck.GrazieSpellCheckingInspection") as Class<out LocalInspectionTool>
        myFixture.enableInspections(inspection)
        myFixture.configureByText("page.mako", text)
        val typos = myFixture.doHighlighting().filter { it.severity == SpellCheckerSeveritiesProvider.TYPO }
        val starts = occurrences(text, word)
        val described = typos.joinToString("\n") { "${it.startOffset}: ${it.description}" }
        assertTrue(described, flagged(typos, starts[0], word))
        assertTrue(described, flagged(typos, starts[1], word))
        assertFalse(described, flagged(typos, starts[2], word))
        assertFalse(described, flagged(typos, starts[3], word))
    }

    private fun tokenizer(
        strategy: MakoSpellcheckingStrategy,
        text: String,
        word: String,
        occurrence: Int,
    ): com.intellij.spellchecker.tokenizer.Tokenizer<*> {
        val offset = occurrences(text, word)[occurrence]
        val element = myFixture.file.viewProvider.findElementAt(offset, MakoLanguage::class.java)
        assertNotNull(element)
        return strategy.getTokenizer(element!!)
    }

    private fun occurrences(text: String, word: String): List<Int> {
        val found = mutableListOf<Int>()
        var from = 0
        while (true) {
            val index = text.indexOf(word, from)
            if (index < 0) break
            found += index
            from = index + word.length
        }
        return found
    }

    private fun flagged(typos: List<com.intellij.codeInsight.daemon.impl.HighlightInfo>, start: Int, word: String): Boolean {
        val end = start + word.length
        return typos.any { it.startOffset < end && it.endOffset > start }
    }
}
