package com.example.grpcweb.greeter

import com.example.grpcweb.proto.hello.v1.GreeterServiceGrpcKt
import com.example.grpcweb.proto.hello.v1.SayHelloRequest
import com.example.grpcweb.proto.hello.v1.SayHelloResponse
import com.example.grpcweb.proto.hello.v1.SayHelloStreamRequest
import com.example.grpcweb.proto.hello.v1.SayHelloStreamResponse
import com.example.grpcweb.proto.hello.v1.sayHelloResponse
import com.example.grpcweb.proto.hello.v1.sayHelloStreamResponse
import io.grpc.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.springframework.grpc.server.service.GrpcService

@GrpcService
class GreeterService : GreeterServiceGrpcKt.GreeterServiceCoroutineImplBase() {

    override suspend fun sayHello(request: SayHelloRequest): SayHelloResponse {
        val name = requireName(request.name)
        return sayHelloResponse { message = "Hello, $name!" }
    }

    override fun sayHelloStream(request: SayHelloStreamRequest): Flow<SayHelloStreamResponse> {
        val name = requireName(request.name)
        val count = if (request.count > 0) request.count.coerceAtMost(MAX_COUNT) else DEFAULT_COUNT
        return flow {
            repeat(count) { i ->
                emit(
                    sayHelloStreamResponse {
                        index = i + 1
                        message = "Hello, $name! (${i + 1}/$count)"
                    },
                )
                if (i < count - 1) delay(STREAM_INTERVAL_MS)
            }
        }
    }

    private fun requireName(name: String): String {
        if (name.isBlank()) {
            throw Status.INVALID_ARGUMENT.withDescription("name is required").asException()
        }
        return name
    }

    companion object {
        private const val DEFAULT_COUNT = 3
        private const val MAX_COUNT = 100
        private const val STREAM_INTERVAL_MS = 300L
    }
}
