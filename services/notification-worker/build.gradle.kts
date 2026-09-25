plugins {
    id("org.springframework.boot") version "3.5.0"
}

// 외부 푸시 API 가 병목. 워커를 늘리면 429 를 맞는다 → 레디스 토큰버킷(시나리오 C).
dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    // 2단계 실험: 제안 알림을 카프카로 받아본다 (delivery.offer.transport=kafka)
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
