package io.github.kwebmn.rozklad.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SiteCodecTest {
    @Test
    fun `form values are encoded in windows-1251`() {
        assertEquals("%B2%CF%C7%F1-24-1", SiteCodec.formEncode("ІПЗс-24-1"))
    }

    @Test
    fun `suggest query is encoded in utf-8`() {
        assertEquals("%D0%86%D0%9F%D0%97", SiteCodec.queryEncode("ІПЗ"))
    }

    @Test
    fun `characters outside windows-1251 do not crash form encoding`() {
        val encoded = SiteCodec.formEncode("Група 😀")
        assertTrue(encoded.startsWith("%C3%F0%F3%EF%E0+"))
        assertFalse(encoded.contains("😀"))
    }

    @Test
    fun `decodes windows-1251 bytes`() {
        assertEquals("ІПЗ", SiteCodec.decode(byteArrayOf(0xB2.toByte(), 0xCF.toByte(), 0xC7.toByte())))
    }

    @Test
    fun `formats dates like the site`() {
        assertEquals("05.10.2026", SiteCodec.formatDate(LocalDate.of(2026, 10, 5)))
    }
}
