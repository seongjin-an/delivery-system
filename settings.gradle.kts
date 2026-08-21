pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "delivery-system"

// ── 라이브러리 모듈 (libs/) ────────────────────────────────────────────────
include("common")
project(":common").projectDir = file("libs/common")

// ── 서비스 모듈 (services/) ────────────────────────────────────────────────
// 물리적으로 services/ 하위에 있으므로 projectDir 을 명시적으로 매핑한다.
val services = listOf(
    "order-api",
    "location-ingest",
    "geo-indexer",
    "dispatch-engine",
    "offer-relay",
    "notification-worker",
    "settlement-service",
    "rider-simulator"
)

services.forEach { name ->
    include(name)
    project(":$name").projectDir = file("services/$name")
}
