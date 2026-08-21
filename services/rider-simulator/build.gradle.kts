plugins {
    id("org.springframework.boot") version "3.5.0"
}

// 프론트가 없으니 이게 손잡이다. 라이더 N명 + 주문 M건/초를 만들어 부하를 건다.
dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
