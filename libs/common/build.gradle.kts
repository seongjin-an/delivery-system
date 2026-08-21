// 순수 라이브러리 — Spring Boot 플러그인 미적용 (실행 가능 jar 불필요)
// java-library: api() 설정으로 의존성이 소비자(각 서비스)에게 전이됨
plugins {
    id("java-library")
}

dependencies {
    api("com.github.f4b6a3:uuid-creator:6.0.0")

    api("org.springframework.boot:spring-boot-starter")
    api("org.springframework.boot:spring-boot-starter-validation")

    api("com.fasterxml.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
}
