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

    // compileOnly 인 이유: GlobalExceptionHandler 를 컴파일하려면 스프링 MVC 가 필요한데,
    // api 로 걸면 common 을 쓰는 모듈 전부가 웹 스타터를 끌고 오게 된다.
    // 자동설정에 @ConditionalOnClass 를 걸어둬서 실제로 웹인 서비스에서만 켜진다.
    compileOnly("org.springframework.boot:spring-boot-starter-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // compileOnly 는 테스트 클래스패스로 안 넘어온다. 자동설정이 실제로 켜지는지 보려면 여기서 다시 걸어줘야 한다.
    testImplementation("org.springframework.boot:spring-boot-starter-web")
}
