package com.example.grpcweb.grpcweb

import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.MethodDescriptor.MethodType
import io.grpc.Status
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * gRPC-Web → gRPC ブリッジ。
 *
 * ブラウザからの `POST {grpc-web.path}/{package.Service}/{Method}` を受け、
 * リクエストボディ (protobuf バイト列) をそのまま in-process チャネル経由で gRPC サービスに転送する。
 * メッセージはデシリアライズしないため、proto ごとのコードは不要。
 * gRPC-Web の仕様上、対応するのは unary と server streaming のみ。
 */
@RestController
class GrpcWebController(
    private val channel: ManagedChannel,
    private val methodRegistry: GrpcWebMethodRegistry,
) {

    @PostMapping(
        "\${grpc-web.path:/grpc-web}/{service}/{method}",
        consumes = [GRPC_WEB, GRPC_WEB_PROTO, GRPC_WEB_TEXT, GRPC_WEB_TEXT_PROTO],
    )
    fun handle(
        @PathVariable service: String,
        @PathVariable method: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val text = request.contentType.startsWith(GRPC_WEB_TEXT)
        val writer = FrameWriter(response, text)
        response.status = HttpServletResponse.SC_OK
        response.contentType = if (text) GRPC_WEB_TEXT_PROTO else GRPC_WEB_PROTO

        val fullMethodName = "$service/$method"
        val type = methodRegistry.methodType(fullMethodName)
        if (type == null) {
            writer.close(Status.UNIMPLEMENTED.withDescription("Method not found: $fullMethodName"), null)
            return
        }
        if (type != MethodType.UNARY && type != MethodType.SERVER_STREAMING) {
            writer.close(Status.UNIMPLEMENTED.withDescription("gRPC-Web does not support $type"), null)
            return
        }

        val message = try {
            readRequestMessage(request, text)
        } catch (e: IllegalArgumentException) {
            writer.close(Status.INVALID_ARGUMENT.withDescription("Malformed gRPC-Web request: ${e.message}"), null)
            return
        } catch (e: UnsupportedOperationException) {
            writer.close(Status.UNIMPLEMENTED.withDescription(e.message), null)
            return
        }

        val callOptions = parseTimeout(request.getHeader("grpc-timeout"))
            ?.let { CallOptions.DEFAULT.withDeadlineAfter(it, TimeUnit.NANOSECONDS) }
            ?: CallOptions.DEFAULT
        val call = channel.newCall(bytesMethod(fullMethodName, type), callOptions)
        val events = LinkedBlockingQueue<Event>()

        call.start(
            object : ClientCall.Listener<ByteArray>() {
                override fun onHeaders(headers: Metadata) {
                    events.put(Event.Headers(headers))
                }

                override fun onMessage(message: ByteArray) {
                    events.put(Event.Message(message))
                }

                override fun onClose(status: Status, trailers: Metadata) {
                    events.put(Event.Close(status, trailers))
                }
            },
            toMetadata(request),
        )
        call.request(1)
        call.sendMessage(message)
        call.halfClose()

        try {
            while (true) {
                when (val event = events.take()) {
                    is Event.Headers -> writer.headers(event.headers)
                    is Event.Message -> {
                        writer.message(event.bytes)
                        call.request(1)
                    }
                    is Event.Close -> {
                        writer.close(event.status, event.trailers)
                        return
                    }
                }
            }
        } catch (e: IOException) {
            // ブラウザ側が切断した
            log.debug("Client disconnected during {}", fullMethodName, e)
            call.cancel("client disconnected", e)
        } catch (e: InterruptedException) {
            call.cancel("interrupted", e)
            Thread.currentThread().interrupt()
        }
    }

    private fun readRequestMessage(request: HttpServletRequest, text: Boolean): ByteArray {
        val raw = request.inputStream.readAllBytes()
        val body = if (text) GrpcWebFrames.decodeBase64Chunks(String(raw, Charsets.US_ASCII)) else raw
        val frames = GrpcWebFrames.decode(body).filterNot { it.isTrailer }
        require(frames.size == 1) { "expected exactly one request message but got ${frames.size}" }
        val frame = frames.single()
        if (frame.isCompressed) throw UnsupportedOperationException("compressed requests are not supported")
        return frame.payload
    }

    private fun toMetadata(request: HttpServletRequest): Metadata {
        val metadata = Metadata()
        for (name in request.headerNames) {
            val key = name.lowercase()
            if (key in EXCLUDED_REQUEST_HEADERS || key.startsWith("sec-") || key.startsWith("grpc-")) continue
            for (value in request.getHeaders(name)) {
                if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                    val bytes = runCatching { Base64.getDecoder().decode(value) }.getOrNull() ?: continue
                    metadata.put(Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER), bytes)
                } else {
                    metadata.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value)
                }
            }
        }
        return metadata
    }

    /** レスポンスにフレームを書き込み、都度 flush する (server streaming を逐次ブラウザへ届けるため)。 */
    private class FrameWriter(private val response: HttpServletResponse, private val text: Boolean) {
        fun headers(headers: Metadata) {
            if (response.isCommitted) return
            for (key in headers.keys()) {
                if (key in EXCLUDED_RESPONSE_HEADERS) continue
                if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                    headers.getAll(Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER))
                        ?.forEach { response.addHeader(key, Base64.getEncoder().encodeToString(it)) }
                } else {
                    headers.getAll(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER))
                        ?.forEach { response.addHeader(key, it) }
                }
            }
        }

        fun message(bytes: ByteArray) = write(GrpcWebFrames.dataFrame(bytes))

        fun close(status: Status, trailers: Metadata?) = write(GrpcWebFrames.trailerFrame(status, trailers))

        private fun write(frame: ByteArray) {
            val out = response.outputStream
            out.write(if (text) Base64.getEncoder().encode(frame) else frame)
            out.flush()
        }
    }

    private sealed interface Event {
        data class Headers(val headers: Metadata) : Event
        class Message(val bytes: ByteArray) : Event
        data class Close(val status: Status, val trailers: Metadata) : Event
    }

    companion object {
        const val GRPC_WEB = "application/grpc-web"
        const val GRPC_WEB_PROTO = "application/grpc-web+proto"
        const val GRPC_WEB_TEXT = "application/grpc-web-text"
        const val GRPC_WEB_TEXT_PROTO = "application/grpc-web-text+proto"

        private val log = LoggerFactory.getLogger(GrpcWebController::class.java)

        /** gRPC メタデータとして転送しない HTTP ヘッダ。 */
        private val EXCLUDED_REQUEST_HEADERS = setOf(
            "host", "connection", "keep-alive", "upgrade", "te", "transfer-encoding", "proxy-connection",
            "content-type", "content-length", "content-encoding",
            "accept", "accept-encoding", "accept-language", "user-agent", "origin", "referer",
            "cache-control", "pragma", "priority", "dnt",
            "x-grpc-web", "x-user-agent",
        )

        private val EXCLUDED_RESPONSE_HEADERS = setOf("content-type", "grpc-status", "grpc-message")

        private val BYTES_MARSHALLER = object : MethodDescriptor.Marshaller<ByteArray> {
            override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)
            override fun parse(stream: InputStream): ByteArray = stream.readAllBytes()
        }

        private fun bytesMethod(fullMethodName: String, type: MethodType): MethodDescriptor<ByteArray, ByteArray> =
            MethodDescriptor.newBuilder(BYTES_MARSHALLER, BYTES_MARSHALLER)
                .setFullMethodName(fullMethodName)
                .setType(type)
                .build()

        /** grpc-timeout ヘッダ ("<数値><単位 H/M/S/m/u/n>") をナノ秒に変換する。 */
        internal fun parseTimeout(value: String?): Long? {
            if (value.isNullOrBlank() || value.length < 2) return null
            val amount = value.dropLast(1).toLongOrNull() ?: return null
            val unit = when (value.last()) {
                'H' -> TimeUnit.HOURS
                'M' -> TimeUnit.MINUTES
                'S' -> TimeUnit.SECONDS
                'm' -> TimeUnit.MILLISECONDS
                'u' -> TimeUnit.MICROSECONDS
                'n' -> TimeUnit.NANOSECONDS
                else -> return null
            }
            return unit.toNanos(amount)
        }
    }
}
