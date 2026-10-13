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
    implementation(platform("software.amazon.awssdk:bom:2.55.15"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:netty-nio-client")

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

// Two operations are multipart uploads that must stream into object storage. The generated
// binding (`@RequestPart`) reads the whole body first, so they are implemented by hand with
// `Flux<PartEvent>` (docs/decisions.md). The generator reads a build-time copy of the contract in
// which they are marked x-internal; api/openapi.yaml itself is untouched and still defines them.
val streamingOperations = listOf("uploadDocument", "uploadDocumentVersion")
val generatorSpec = layout.buildDirectory.file("openapi-for-generator/openapi.yaml")

val prepareGeneratorSpec = tasks.register("prepareGeneratorSpec") {
    val source = rootProject.file("../api/openapi.yaml")
    inputs.file(source)
    outputs.file(generatorSpec)
    doLast {
        var text = source.readText()
        streamingOperations.forEach { op ->
            val marker = "operationId: $op\n"
            check(text.contains(marker)) { "operation $op not found in the contract" }
            val indent = Regex("( +)" + Regex.escape("operationId: $op") + "\n").find(text)!!.groupValues[1]
            text = text.replace(marker, marker + indent + "x-internal: true\n")
        }
        generatorSpec.get().asFile.parentFile.mkdirs()
        generatorSpec.get().asFile.writeText(text)
    }
}

openApiGenerate {
    generatorName = "spring"
    inputSpec = generatorSpec.get().asFile.absolutePath
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

tasks.openApiGenerate {
    dependsOn(prepareGeneratorSpec)
}

tasks.compileJava {
    dependsOn(tasks.openApiGenerate)
}

// The contract travels inside the jar: the role guard reads x-roles from it at start.
tasks.processResources {
    from(rootProject.file("../api")) {
        include("openapi.yaml")
        into("openapi")
    }
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
