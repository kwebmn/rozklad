package io.github.kwebmn.rozklad.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {
    @Test
    fun parses() {
        assertEquals(SemVer(1, 2, 3), SemVer.parse("v1.2.3"))
        assertEquals(SemVer(1, 2, 3), SemVer.parse("1.2.3"))
        assertNull(SemVer.parse("v1.2"))
        assertNull(SemVer.parse("nightly"))
    }

    @Test
    fun `compares numerically`() {
        assertTrue(isNewer("v1.10.0", "1.9.9"))
        assertTrue(isNewer("v0.2.0", "0.1.5"))
        assertFalse(isNewer("v1.0.0", "1.0.0"))
        assertFalse(isNewer("v0.9.0", "1.0.0"))
    }

    @Test
    fun `malformed tags are never newer`() {
        assertFalse(isNewer("nightly", "0.1.0"))
        assertFalse(isNewer("v2.0.0", "dev"))
    }
}
