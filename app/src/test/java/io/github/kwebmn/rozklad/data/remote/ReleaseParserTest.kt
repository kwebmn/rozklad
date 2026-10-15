package io.github.kwebmn.rozklad.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseParserTest {
    @Test
    fun `picks the apk asset`() {
        val json = """
            {"tag_name":"v0.2.0","body":"Що нового","draft":false,"extra":1,
             "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt"},
                       {"name":"rozklad-v0.2.0.apk","browser_download_url":"https://x/rozklad-v0.2.0.apk"}]}
        """.trimIndent()
        assertEquals(
            ReleaseInfo("v0.2.0", "Що нового", "https://x/rozklad-v0.2.0.apk"),
            ReleaseParser.parse(json),
        )
    }

    @Test
    fun `release without apk is ignored`() {
        assertNull(ReleaseParser.parse("""{"tag_name":"v0.2.0","body":null,"assets":[]}"""))
    }
}
