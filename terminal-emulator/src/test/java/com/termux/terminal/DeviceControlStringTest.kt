package com.termux.terminal

/**
 * "\033P" is a device control string.
 */
class DeviceControlStringTest : TerminalTestCase() {
    private fun assertCapabilityResponse(cap: String, expectedResponse: String) {
        val input = "\u001bP+q" + hexEncode(cap) + "\u001b\\"
        assertEnteringStringGivesResponse(input, "\u001bP1+r" + hexEncode(cap) + "=" + hexEncode(expectedResponse) + "\u001b\\")
    }

    fun testReportColorsAndName() {
        // Request Termcap/Terminfo String. The string following the "q" is a list of names encoded in
        // hexadecimal (2 digits per character) separated by ; which correspond to termcap or terminfo key
        // names.
        // Two special features are also recognized, which are not key names: Co for termcap colors (or colors
        // for terminfo colors), and TN for termcap name (or name for terminfo name).
        // xterm responds with DCS 1 + r P t ST for valid requests, adding to P t an = , and the value of the
        // corresponding string that xterm would send, or DCS 0 + r P t ST for invalid requests. The strings are
        // encoded in hexadecimal (2 digits per character).
        withTerminalSized(3, 3).enterString("A")
        assertCapabilityResponse("Co", "256")
        assertCapabilityResponse("colors", "256")
        assertCapabilityResponse("TN", "xterm")
        assertCapabilityResponse("name", "xterm")
        enterString("B").assertLinesAre("AB ", "   ", "   ")
    }

    fun testReportKeys() {
        withTerminalSized(3, 3)
        assertCapabilityResponse("kB", "\u001b[Z")
    }

    fun testReallyLongDeviceControlString() {
        withTerminalSized(3, 3).enterString("\u001bP")
        for (i in 0..9999) {
            enterString("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        }
        // The terminal should ignore the overlong DCS sequence and continue printing "aaa." and fill at least the first two lines with
        // them:
        assertLineIs(0, "aaa")
        assertLineIs(1, "aaa")
    }

    companion object {
        private fun hexEncode(s: String): String {
            val result = StringBuilder()
            for (i in 0 until s.length) result.append(String.format("%02X", s[i].toInt()))
            return result.toString()
        }
    }
}
