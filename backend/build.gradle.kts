plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.10.4"
    id("org.openapi.generator") version "7.14.0"
}

group = "app.tendereval"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.nats:jnats:2.26.4")

    runtimeOnly("org.postgresql:r2dbc-postgresql")
    // JDBC driver: Flyway cannot run over R2DBC. Used for migrations only.
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("com.networknt:json-schema-validator:3.0.8")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Server interfaces and models generated from api/openapi.yaml. Never committed: the build
// regenerates them, so the contract and the code cannot drift (implementation guide 4.1).
val openApiOutput = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
    generatorName = "spring"
    inputSpec = rootProject.file("../api/openapi.yaml").absolutePath
    outputDir = openApiOutput.get().asFile.absolutePath
    apiPackage = "app.tendereval.backend.api"
    modelPackage = "app.tendereval.backend.api.model"
    invokerPackage = "app.tendereval.backend.api.invoker"
    // Replaces the generator's default method body (which returns example data with 501).
    templateDir.set(layout.projectDirectory.dir("openapi-templates").asFile.absolutePath)
    globalProperties = mapOf("apis" to "", "models" to "", "supportingFiles" to "false")
    configOptions = mapOf(
        "interfaceOnly" to "true",
        "reactive" to "true",
        "useSpringBoot3" to "true",
        "useTags" to "true",
        "skipDefaultInterface" to "false",
        "documentationProvider" to "none",
        "openApiNullable" to "false",
        "useBeanValidation" to "true",
        "performBeanValidation" to "false",
        "dateLibrary" to "java8",
        "sourceFolder" to "src/main/java",
    )
}

sourceSets.main {
    java.srcDir(openApiOutput.map { it.dir("src/main/java") })
}

tasks.compileJava {
    dependsOn(tasks.openApiGenerate)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Unit tests need no Docker; integration tests are tagged and run by integrationTest.
tasks.test {
    useJUnitPlatform { excludeTags("integration") }
}

tasks.register<Test>("integrationTest") {
    description = "Integration tests against real containers (Docker required)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
}

spotless {
    java {
        googleJavaFormat()
        // Generated code is excluded; it lives under build/.
        target("src/**/*.java")
    }
}
