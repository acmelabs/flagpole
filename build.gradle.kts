plugins {
    id("io.micronaut.application") version "4.6.2"
    // Groovy is only used for Spock tests; production sources stay in src/main/java.
    groovy
}

version = "0.1.0"
group = "app.acmelabs"

repositories {
    mavenCentral()
}

dependencies {
    annotationProcessor("io.micronaut:micronaut-http-validation")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    // OpenAPI spec and Swagger UI are generated at compile time (see openapi.properties).
    annotationProcessor("io.micronaut.openapi:micronaut-openapi")
    compileOnly("io.micronaut.openapi:micronaut-openapi-annotations")

    implementation("io.micronaut:micronaut-management")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("io.micronaut.views:micronaut-views-thymeleaf")
    implementation("org.yaml:snakeyaml")

    runtimeOnly("ch.qos.logback:logback-classic")

    testImplementation("io.micronaut:micronaut-http-client")
    testImplementation("org.apache.groovy:groovy-nio")
}

application {
    mainClass = "app.acmelabs.flagpole.Application"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    // Placeholder used in the OpenAPI @Info version.
    options.compilerArgs.add("-Amicronaut.openapi.expand.api.version=${project.version}")
}

// Keep production code Java-only: the groovy plugin would otherwise also compile src/main/groovy.
sourceSets {
    main {
        groovy.setSrcDirs(emptyList<String>())
    }
}

graalvmNative {
    toolchainDetection = false
    binaries {
        named("main") {
            imageName = "flagpole"
            // Link everything but glibc statically (incl. zlib) so the binary runs on distroless/base.
            // Linux only, so it is opt-in: the Dockerfile passes -PmostlyStatic.
            if (project.hasProperty("mostlyStatic")) {
                buildArgs.add("-H:+StaticExecutableWithDynamicLibC")
            }
        }
    }
}

micronaut {
    runtime("netty")
    testRuntime("spock2")
    processing {
        incremental(true)
        annotations("app.acmelabs.flagpole.*")
    }
}
