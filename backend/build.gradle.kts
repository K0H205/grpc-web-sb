import com.google.protobuf.gradle.id

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "4.0.9"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.google.protobuf") version "0.10.1"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val springGrpcVersion = "1.0.3"
// spring-grpc-dependencies が管理するバージョンに合わせる
val grpcVersion = "1.77.1"
val grpcKotlinVersion = "1.5.0"
val protobufVersion = "4.33.4"

dependencyManagement {
    imports {
        mavenBom("org.springframework.grpc:spring-grpc-dependencies:$springGrpcVersion")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.grpc:spring-grpc-spring-boot-starter")
    implementation("io.grpc:grpc-services")
    implementation("io.grpc:grpc-inprocess")
    implementation("io.grpc:grpc-kotlin-stub")
    implementation("com.google.protobuf:protobuf-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.grpc:spring-grpc-test-spring-boot-autoconfigure")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

// 共有契約はリポジトリ直下の proto/ に置き、backend / frontend の両方がそこから生成する
sourceSets {
    main {
        proto {
            srcDir(rootProject.layout.projectDirectory.dir("proto"))
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                id("grpc") {
                    option("@generated=omit")
                }
                id("grpckt")
            }
            it.builtins {
                id("kotlin")
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
