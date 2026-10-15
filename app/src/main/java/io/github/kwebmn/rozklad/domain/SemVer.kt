package io.github.kwebmn.rozklad.domain

data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""")

        fun parse(text: String): SemVer? = PATTERN.find(text.trim())?.destructured?.let { (a, b, c) ->
            SemVer(a.toInt(), b.toInt(), c.toInt())
        }
    }
}

fun isNewer(tag: String, current: String): Boolean {
    val candidate = SemVer.parse(tag) ?: return false
    val installed = SemVer.parse(current) ?: return false
    return candidate > installed
}
