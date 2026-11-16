package com.example.grpcweb.grpcweb

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("grpc-web")
data class GrpcWebProperties(
    /** gRPC-Web エンドポイントのパスプレフィックス。 */
    val path: String = "/grpc-web",
    /** CORS で許可するオリジン。空なら CORS 設定を行わない (同一オリジンのみ)。 */
    val allowedOrigins: List<String> = emptyList(),
)
