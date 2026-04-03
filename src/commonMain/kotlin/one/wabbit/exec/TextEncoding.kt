package one.wabbit.exec

sealed interface TextEncoding {
    data object Utf8 : TextEncoding
    data object Ascii : TextEncoding
    data object Latin1 : TextEncoding
    data class Named(val name: String) : TextEncoding
}

internal expect object TextEncodingPlatform {
    fun encode(text: String, encoding: TextEncoding): ByteArray

    fun decode(bytes: ByteArray, encoding: TextEncoding): String
}
