plugins {
    id("org.springframework.boot") version "3.5.0"
}

// 무상태 프로듀서. DB/레디스 의존이 하나도 없어서 인스턴스를 그냥 늘리면 늘어난다(시나리오 A).
dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.kafka:spring-kafka")
    // 이동거리 필터가 라이더별 직전 좌표를 메모리에 들고 있는다. 크기 상한과 만료가 둘 다 필요해서
    // ConcurrentHashMap 대신 이걸 쓴다. 버전은 부트 BOM 이 잡아준다.
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
