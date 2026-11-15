package android.util
object Log {
    val messages = mutableListOf<String>()
    @JvmStatic fun i(tag: String, message: String): Int { messages.add(message); return 0 }
}
