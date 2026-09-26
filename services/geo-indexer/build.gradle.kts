plugins {
    id("org.springframework.boot") version "3.5.0"
}

// 카프카 컨슈머 → 레디스 GEO 반영. 확장 천장이 파티션 수라서 시나리오 B 의 주인공.
dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-web")           // actuator 노출용
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // lettuce 커넥션 풀(spring.data.redis.lettuce.pool)을 켜면 이게 있어야 한다.
    // 스타터에 안 딸려와서, 풀만 켜두면 기동 때 GenericObjectPoolConfig NoClassDefFoundError 로 죽는다.
    implementation("org.apache.commons:commons-pool2")
    implementation("org.springframework.kafka:spring-kafka")
    // 2단계 실험: 좌표를 MySQL 공간 인덱스에도 써본다 (GEO_STORE=mysql)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("com.mysql:mysql-connector-j")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
}
