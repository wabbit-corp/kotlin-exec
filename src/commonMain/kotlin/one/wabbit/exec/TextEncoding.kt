package one.wabbit.exec

/**
 * Portable text encoding descriptor used by common stdin and capture APIs.
 */
sealed interface TextEncoding {
    /**
     * UTF-8 text encoding.
     */
    data object Utf8 : TextEncoding

    /**
     * US-ASCII text encoding.
     */
    data object Ascii : TextEncoding

    /**
     * ISO-8859-1 text encoding.
     */
    data object Latin1 : TextEncoding

    /**
     * Platform charset by name.
     *
     * The name is resolved by the platform implementation when encoding or decoding. Invalid or
     * unsupported names fail at use time.
     *
     * @property name charset name, such as `"UTF-8"` or `"windows-1252"`.
     */
    data class Named(val name: String) : TextEncoding
}

internal expect object TextEncodingPlatform {
    fun encode(text: String, encoding: TextEncoding): ByteArray

    fun decode(bytes: ByteArray, encoding: TextEncoding): String
}
