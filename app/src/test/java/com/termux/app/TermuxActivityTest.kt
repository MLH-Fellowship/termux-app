package com.termux.app

import com.termux.app.TermuxActivity.Companion.extractUrls
import org.junit.Assert
import org.junit.Test
import java.util.*

class TermuxActivityTest {
    private fun assertUrlsAre(text: String, vararg urls: String) {
        val expected = LinkedHashSet<String>()
        Collections.addAll(expected, *urls)
        Assert.assertEquals(expected, extractUrls(text))
    }

    @Test
    fun testExtractUrls() {
        assertUrlsAre("hello http://example.com world", "http://example.com")
        assertUrlsAre("http://example.com\nhttp://another.com", "http://example.com", "http://another.com")
        assertUrlsAre("hello http://example.com world and http://more.example.com with secure https://more.example.com",
            "http://example.com", "http://more.example.com", "https://more.example.com")
        assertUrlsAre("hello https://example.com/#bar https://example.com/foo#bar",
            "https://example.com/#bar", "https://example.com/foo#bar")
    }
}
