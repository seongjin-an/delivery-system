plugins {
    id("org.springframework.boot") version "3.5.0"
}

// 무상태 프로듀서. DB/레디스 의존이 하나도 없어서 인스턴스를 그냥 늘리면 늘어난다(시나리오 A).
dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
