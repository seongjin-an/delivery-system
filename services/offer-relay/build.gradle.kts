plugins {
    id("org.springframework.boot") version "3.5.0"
}

// 래빗엠큐 토폴로지(TTL + DLX) 소유자. 만료된 제안을 다음 후보에게 넘긴다.
dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.kafka:spring-kafka")                     // 후보 소진 시 dispatch.failed 발행
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.amqp:spring-rabbit-test")
}
