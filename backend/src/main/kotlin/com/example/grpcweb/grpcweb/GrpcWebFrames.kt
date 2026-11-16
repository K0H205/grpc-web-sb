package com.example.grpcweb.grpcweb

import io.grpc.Metadata
import io.grpc.Status
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * gRPC-Web のフレーム形式 (https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-WEB.md)
 *
 *   [flag: 1 byte][length: 4 bytes big-endian][payload]
 *
 * flag = 0x00: data frame, 0x80: trailer frame, bit 0x01: compressed
 */
object GrpcWebFrames {
    const val DATA_FLAG: Int = 0x00
    const val TRAILER_FLAG: Int = 0x80
    const val COMPRESSED_FLAG: Int = 0x01
    private const val HEADER_SIZE = 5

    data class Frame(val flag: Int, val payload: ByteArray) {
        val isTrailer: Boolean get() = flag and TRAILER_FLAG != 0
        val isCompressed: Boolean get() = flag and COMPRESSED_FLAG != 0
    }

    fun encode(flag: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE + payload.size)
            .put(flag.toByte())
            .putInt(payload.size)
            .put(payload)
            .array()

    fun dataFrame(message: ByteArray): ByteArray = encode(DATA_FLAG, message)

    /** trailer frame の payload は HTTP/1 ヘッダ形式 ("key:value\r\n" の連続)。 */
    fun trailerFrame(status: Status, trailers: Metadata?): ByteArray {
        val sb = StringBuilder()
        sb.append("grpc-status:").append(status.code.value()).append("\r\n")
        status.description?.let { sb.append("grpc-message:").append(percentEncode(it)).append("\r\n") }
        trailers?.let { appendMetadata(sb, it) }
        return encode(TRAILER_FLAG, sb.toString().toByteArray(StandardCharsets.US_ASCII))
    }

    fun decode(body: ByteArray): List<Frame> {
        val frames = mutableListOf<Frame>()
        val buf = ByteBuffer.wrap(body)
        while (buf.hasRemaining()) {
            if (buf.remaining() < HEADER_SIZE) throw IllegalArgumentException("truncated frame header")
            val flag = buf.get().toInt() and 0xff
            val length = buf.getInt()
            if (length < 0 || length > buf.remaining()) throw IllegalArgumentException("truncated frame payload")
            val payload = ByteArray(length).also { buf.get(it) }
            frames += Frame(flag, payload)
        }
        return frames
    }

    /**
     * grpc-web-text のボディを base64 デコードする。
     * クライアントによっては base64 チャンクを (パディング付きで) 連結して送るため、チャンク単位で復号する。
     */
    fun decodeBase64Chunks(text: String): ByteArray {
        val compact = text.filterNot { it.isWhitespace() }
        val out = ByteArrayOutputStream()
        var start = 0
        var i = 0
        while (i < compact.length) {
            if (compact[i] == '=') {
                while (i < compact.length && compact[i] == '=') i++
                out.write(Base64.getDecoder().decode(compact.substring(start, i)))
                start = i
            } else {
                i++
            }
        }
        if (start < compact.length) out.write(Base64.getDecoder().decode(compact.substring(start)))
        return out.toByteArray()
    }

    fun appendMetadata(sb: StringBuilder, metadata: Metadata) {
        for (key in metadata.keys()) {
            if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                val k = Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER)
                metadata.getAll(k)?.forEach {
                    sb.append(key).append(':').append(Base64.getEncoder().encodeToString(it)).append("\r\n")
                }
            } else {
                val k = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)
                metadata.getAll(k)?.forEach { sb.append(key).append(':').append(it).append("\r\n") }
            }
        }
    }

    /** grpc-message は gRPC 仕様に従いパーセントエンコードする。 */
    private fun percentEncode(message: String): String =
        URLEncoder.encode(message, StandardCharsets.UTF_8).replace("+", "%20")
}
