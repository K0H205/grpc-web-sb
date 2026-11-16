package com.example.grpcweb.grpcweb

import io.grpc.MethodDescriptor
import org.springframework.grpc.server.service.GrpcServiceDiscoverer
import org.springframework.stereotype.Component

/** Spring gRPC に登録されたサービスから、フルメソッド名 → メソッド種別を引く。 */
@Component
class GrpcWebMethodRegistry(private val discoverer: GrpcServiceDiscoverer) {

    private val methodTypes: Map<String, MethodDescriptor.MethodType> by lazy {
        discoverer.findServices()
            .flatMap { it.service().bindService().methods }
            .associate { it.methodDescriptor.fullMethodName to it.methodDescriptor.type }
    }

    fun methodType(fullMethodName: String): MethodDescriptor.MethodType? = methodTypes[fullMethodName]
}
