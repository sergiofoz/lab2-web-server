plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.ktlint)
}

group = "es.unizar.webeng"
version = "2026-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

val mockitoAgent =
    configurations.create("mockitoAgent") {
        isCanBeConsumed = false
    }

dependencies {
    val springBootVersion = libs.versions.springBoot.get()
    val springBootBom = platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
    implementation(springBootBom)
    mockitoAgent(springBootBom)

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.thymeleaf)
    implementation(libs.jackson.module.kotlin)
    runtimeOnly(libs.kotlin.reflect)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.restclient)
    testImplementation(libs.spring.boot.resttestclient)
    mockitoAgent("org.mockito:mockito-core") {
        isTransitive = false
    }
}

// Attach Mockito at JVM startup so the inline mock maker does not self-attach.
// See https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html#0.3
abstract class MockitoAgentProvider : CommandLineArgumentProvider {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val agentJar: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> =
        listOf(
            "-javaagent:${agentJar.asPath}",
            // The agent appends to the bootstrap classpath, which disables CDS sharing.
            "-Xshare:off",
        )
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgumentProviders.add(
        objects.newInstance<MockitoAgentProvider>().apply {
            agentJar.from(mockitoAgent)
        },
    )
}

ktlint {
    verbose.set(true)
    outputToConsole.set(true)
    coloredOutput.set(true)
}
