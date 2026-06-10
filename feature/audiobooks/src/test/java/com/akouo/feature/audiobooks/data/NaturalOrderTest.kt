package com.akouo.feature.audiobooks.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {

    @Test
    fun `numbers compare numerically not lexically`() {
        val sorted = listOf("Track 10.mp3", "Track 2.mp3", "Track 1.mp3").sortedWith(NaturalOrder)
        assertEquals(listOf("Track 1.mp3", "Track 2.mp3", "Track 10.mp3"), sorted)
    }

    @Test
    fun `leading zeros do not change order`() {
        val sorted = listOf("007.mp3", "8.mp3", "06.mp3").sortedWith(NaturalOrder)
        assertEquals(listOf("06.mp3", "007.mp3", "8.mp3"), sorted)
    }

    @Test
    fun `comparison is case-insensitive`() {
        val sorted = listOf("chapter 2", "Chapter 1").sortedWith(NaturalOrder)
        assertEquals(listOf("Chapter 1", "chapter 2"), sorted)
    }

    @Test
    fun `plain strings still sort`() {
        val sorted = listOf("b", "a", "ab").sortedWith(NaturalOrder)
        assertEquals(listOf("a", "ab", "b"), sorted)
    }
}
