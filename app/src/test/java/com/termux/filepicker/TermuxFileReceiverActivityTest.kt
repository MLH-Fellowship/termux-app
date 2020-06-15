package com.termux.filepicker

import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.*

@RunWith(RobolectricTestRunner::class)
class TermuxFileReceiverActivityTest {
    @Test
    fun testIsSharedTextAnUrl() {
        val validUrls: MutableList<String> = ArrayList()
        validUrls.add("http://example.com")
        validUrls.add("https://example.com")
        validUrls.add("https://example.com/path/parameter=foo")
        validUrls.add("magnet:?xt=urn:btih:d540fc48eb12f2833163eed6421d449dd8f1ce1f&dn=Ubuntu+desktop+19.04+%2864bit%29&tr=udp%3A%2F%2Ftracker.openbittorrent.com%3A80&tr=udp%3A%2F%2Ftracker.publicbt.com%3A80&tr=udp%3A%2F%2Ftracker.ccc.de%3A80")
        for (url in validUrls) {
            Assert.assertTrue(TermuxFileReceiverActivity.isSharedTextAnUrl(url))
        }
        val invalidUrls: MutableList<String> = ArrayList()
        invalidUrls.add("a test with example.com")
        for (url in invalidUrls) {
            Assert.assertFalse(TermuxFileReceiverActivity.isSharedTextAnUrl(url))
        }
    }
}
