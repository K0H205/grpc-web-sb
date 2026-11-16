package com.example.grpcweb.grpcweb

import io.grpc.ManagedChannel
import io.grpc.inprocess.InProcessChannelBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration(proxyBeanMethods = false)
class GrpcWebConfig(private val properties: GrpcWebProperties) : WebMvcConfigurer {

    /**
     * ブリッジ専用の in-process チャネル。
     * Spring gRPC が起動する in-process サーバに繋がるので、サービス実装と
     * ServerInterceptor (認証・ログ等) はネイティブ gRPC と共通になる。
     */
    @Bean(destroyMethod = "shutdownNow")
    fun grpcWebChannel(
        @Value("\${spring.grpc.server.inprocess.name}") inProcessName: String,
    ): ManagedChannel = InProcessChannelBuilder.forName(inProcessName).build()

    override fun addCorsMappings(registry: CorsRegistry) {
        if (properties.allowedOrigins.isEmpty()) return
        registry.addMapping("${properties.path}/**")
            .allowedOrigins(*properties.allowedOrigins.toTypedArray())
            .allowedMethods("POST", "OPTIONS")
            // x-grpc-web, x-user-agent, grpc-timeout, authorization, 任意のカスタムメタデータ
            .allowedHeaders("*")
            .exposedHeaders("grpc-status", "grpc-message", "grpc-status-details-bin")
            .maxAge(3600)
    }
}
