package android.net

data class Uri(private val text: String) {
    override fun toString() = text
    companion object { @JvmStatic fun parse(text: String) = Uri(text) }
}
