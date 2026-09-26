plugins {
    id("org.springframework.boot") version "3.5.0"
}

// 카프카(주문) → 레디스(후보 검색·락) → 래빗엠큐(제안). 세 기술이 한 요청에서 다 만나는 지점.
dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.apache.commons:commons-pool2")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    // 2단계 실험: 후보 검색을 MySQL 공간 인덱스로 해본다 (GEO_STORE=mysql)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("com.mysql:mysql-connector-j")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.amqp:spring-rabbit-test")
}
