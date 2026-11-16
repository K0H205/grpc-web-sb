package com.example.grpcweb

import com.example.grpcweb.grpcweb.GrpcWebFrames
import com.example.grpcweb.proto.hello.v1.GreeterServiceGrpc
import com.example.grpcweb.proto.hello.v1.SayHelloResponse
import com.example.grpcweb.proto.hello.v1.SayHelloStreamResponse
import com.example.grpcweb.proto.hello.v1.sayHelloRequest
import com.example.grpcweb.proto.hello.v1.sayHelloStreamRequest
import io.grpc.ManagedChannelBuilder
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.grpc.server.port=0"],
)
class GrpcWebBridgeTest {

    @LocalServerPort
    var port: Int = 0

    @LocalGrpcPort
    var grpcPort: Int = 0

    @Value("\${grpc-web.path}")
    lateinit var path: String

    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    private fun post(
        method: String,
        body: ByteArray,
        contentType: String = "application/grpc-web+proto",
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path/$method"))
            .header("content-type", contentType)
            .header("x-grpc-web", "1")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun trailer(frames: List<GrpcWebFrames.Frame>): Map<String, String> =
        String(frames.last { it.isTrailer }.payload, Charsets.US_ASCII)
            .split("\r\n").filter { it.isNotEmpty() }
            .associate { it.substringBefore(':') to it.substringAfter(':') }

    @Test
    fun `unary call over binary grpc-web`() {
        val req = sayHelloRequest { name = "Spring" }.toByteArray()
        val res = post("hello.v1.GreeterService/SayHello", GrpcWebFrames.dataFrame(req))

        assertEquals(200, res.statusCode())
        assertEquals("application/grpc-web+proto", res.headers().firstValue("content-type").get())
        val frames = GrpcWebFrames.decode(res.body())
        assertEquals(2, frames.size)
        assertEquals("Hello, Spring!", SayHelloResponse.parseFrom(frames[0].payload).message)
        assertEquals("0", trailer(frames)["grpc-status"])
    }

    @Test
    fun `unary call over grpc-web-text`() {
        val req = sayHelloRequest { name = "Text" }.toByteArray()
        val body = Base64.getEncoder().encode(GrpcWebFrames.dataFrame(req))
        val res = post("hello.v1.GreeterService/SayHello", body, contentType = "application/grpc-web-text")

        assertEquals("application/grpc-web-text+proto", res.headers().firstValue("content-type").get())
        val frames = GrpcWebFrames.decode(GrpcWebFrames.decodeBase64Chunks(String(res.body())))
        assertEquals("Hello, Text!", SayHelloResponse.parseFrom(frames[0].payload).message)
        assertEquals("0", trailer(frames)["grpc-status"])
    }

    @Test
    fun `server streaming call`() {
        val req = sayHelloStreamRequest {
            name = "Stream"
            count = 3
        }.toByteArray()
        val res = post("hello.v1.GreeterService/SayHelloStream", GrpcWebFrames.dataFrame(req))

        val frames = GrpcWebFrames.decode(res.body())
        val messages = frames.filterNot { it.isTrailer }.map { SayHelloStreamResponse.parseFrom(it.payload) }
        assertEquals(listOf(1, 2, 3), messages.map { it.index })
        assertEquals("0", trailer(frames)["grpc-status"])
    }

    @Test
    fun `grpc error is returned in trailers`() {
        val req = sayHelloRequest { name = "" }.toByteArray()
        val res = post("hello.v1.GreeterService/SayHello", GrpcWebFrames.dataFrame(req))

        assertEquals(200, res.statusCode())
        val t = trailer(GrpcWebFrames.decode(res.body()))
        assertEquals("3", t["grpc-status"])
        assertEquals("name%20is%20required", t["grpc-message"])
    }

    @Test
    fun `unknown method returns UNIMPLEMENTED`() {
        val res = post("hello.v1.GreeterService/Nope", GrpcWebFrames.dataFrame(ByteArray(0)))
        assertEquals("12", trailer(GrpcWebFrames.decode(res.body()))["grpc-status"])
    }

    @Test
    fun `cors preflight is allowed for configured origin`() {
        val req = HttpRequest.newBuilder(URI("http://localhost:$port$path/hello.v1.GreeterService/SayHello"))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", "http://localhost:5173")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "content-type,x-grpc-web,x-user-agent")
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.discarding())

        assertEquals(200, res.statusCode())
        assertEquals("http://localhost:5173", res.headers().firstValue("access-control-allow-origin").get())
        assertTrue(res.headers().firstValue("access-control-allow-headers").get().contains("x-grpc-web"))
    }

    @Test
    fun `native grpc still works`() {
        val channel = ManagedChannelBuilder.forAddress("localhost", grpcPort).usePlaintext().build()
        try {
            val reply = GreeterServiceGrpc.newBlockingStub(channel).sayHello(sayHelloRequest { name = "Native" })
            assertEquals("Hello, Native!", reply.message)
        } finally {
            channel.shutdownNow()
        }
    }
}
