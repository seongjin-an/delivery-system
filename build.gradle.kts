plugins {
    java
    id("org.springframework.boot") version "3.5.0" apply false
    id("io.spring.dependency-management") version "1.1.7"  // root 에 적용해야 subprojects {} 람다에서 Kotlin DSL 타입이 해석됨
}

val bootVersion = "3.5.0"

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    group = "com.delivery"
    version = "0.0.1-SNAPSHOT"

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    repositories {
        mavenCentral()
    }

    dependencyManagement {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:$bootVersion")
        }
    }

    dependencies {
        compileOnly("org.projectlombok:lombok:1.18.36")
        annotationProcessor("org.projectlombok:lombok:1.18.36")
        testCompileOnly("org.projectlombok:lombok:1.18.36")
        testAnnotationProcessor("org.projectlombok:lombok:1.18.36")

        // 그래들 8.13 이 들고 있는 junit-platform-launcher 가 부트 3.5 BOM 의 junit-platform-engine
        // 보다 낮아서, 이걸 안 걸면 테스트를 하나도 못 찾고 "OutputDirectoryProvider not available"
        // 로 죽는다. BOM 이 관리하는 버전으로 런처를 맞춰준다.
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
