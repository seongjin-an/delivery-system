// 순수 라이브러리 — Spring Boot 플러그인 미적용 (실행 가능 jar 불필요)
// java-library: api() 설정으로 의존성이 소비자(각 서비스)에게 전이됨
plugins {
    id("java-library")
}

dependencies {
    // orderId, riderId, offerId 를 만드는 TSID. 64비트라 DB 에 BIGINT 로 들어간다.
    // (처음엔 uuid-creator 로 UUIDv7 을 썼는데 CHAR(36) 이 인덱스마다 복사돼서 TSID 로 바꿨다)
    api("com.github.f4b6a3:tsid-creator:5.2.6")

    api("org.springframework.boot:spring-boot-starter")
    api("org.springframework.boot:spring-boot-starter-validation")

    api("com.fasterxml.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // 아래 둘은 compileOnly 다. GlobalExceptionHandler 와 컨슈머 에러 핸들러를 컴파일하려면
    // 필요한데, 여기서 api 로 걸면 카프카를 안 쓰는 rider-simulator 까지 spring-kafka 를
    // 끌고 오게 된다. 자동설정에 @ConditionalOnClass 를 걸어둔 이유가 이거다 —
    // 클래스가 실제로 있는 서비스에서만 켜진다.
    compileOnly("org.springframework.boot:spring-boot-starter-web")
    compileOnly("org.springframework.kafka:spring-kafka")
    compileOnly("org.springframework.boot:spring-boot-starter-amqp")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // compileOnly 는 테스트 클래스패스로 안 넘어온다. 자동설정이 실제로 켜지는지 보려면 여기서 다시 걸어줘야 한다.
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.kafka:spring-kafka")
    testImplementation("org.springframework.boot:spring-boot-starter-amqp")
}
