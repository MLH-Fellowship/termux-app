package com.termux.terminal

import junit.framework.TestCase

class ByteQueueTest : TestCase() {
    @Throws(Exception::class)
    fun testCompleteWrites() {
        val q = ByteQueue(10)
        assertTrue(q.write(byteArrayOf(1, 2, 3), 0, 3))
        val arr = ByteArray(10)
        assertEquals(3, q.read(arr, true))
        assertArrayEquals(byteArrayOf(1, 2, 3), byteArrayOf(arr[0], arr[1], arr[2]))
        assertTrue(q.write(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), 0, 10))
        assertEquals(10, q.read(arr, true))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), arr)
    }

    @Throws(Exception::class)
    fun testQueueWraparound() {
        val q = ByteQueue(10)
        val origArray = byteArrayOf(1, 2, 3, 4, 5, 6)
        val readArray = ByteArray(origArray.size)
        for (i in 0..19) {
            q.write(origArray, 0, origArray.size)
            assertEquals(origArray.size, q.read(readArray, true))
            assertArrayEquals(origArray, readArray)
        }
    }

    @Throws(Exception::class)
    fun testWriteNotesClosing() {
        val q = ByteQueue(10)
        q.close()
        assertFalse(q.write(byteArrayOf(1, 2, 3), 0, 3))
    }

    @Throws(Exception::class)
    fun testReadNonBlocking() {
        val q = ByteQueue(10)
        assertEquals(0, q.read(ByteArray(128), false))
    }

    companion object {
        private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
            if (expected.size != actual.size) {
                fail("Difference array length")
            }
            for (i in expected.indices) {
                if (expected[i] != actual[i]) {
                    fail("Inequals at index=" + i + ", expected=" + expected[i].toInt() + ", actual=" + actual[i].toInt())
                }
            }
        }
    }
}
