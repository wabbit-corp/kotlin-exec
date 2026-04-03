package one.wabbit.exec

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

internal actual object TextEncodingPlatform {
    actual fun encode(text: String, encoding: TextEncoding): ByteArray =
        text.toByteArray(encoding.toCharset())

    actual fun decode(bytes: ByteArray, encoding: TextEncoding): String =
        bytes.toString(encoding.toCharset())
}

internal fun TextEncoding.toCharset(): Charset =
    when (this) {
        TextEncoding.Utf8 -> StandardCharsets.UTF_8
        TextEncoding.Ascii -> StandardCharsets.US_ASCII
        TextEncoding.Latin1 -> StandardCharsets.ISO_8859_1
        is TextEncoding.Named -> Charset.forName(name)
    }
