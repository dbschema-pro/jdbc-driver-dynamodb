plugins {
    alias(libs.plugins.wisecoders.commonGradle.jdbcDriver)
}

group = "com.wisecoders.jdbc-drivers"

jdbcDriver {
    dbId = "DynamoDB"
}

dependencies {
    implementation(libs.wisecoders.commonJdbc.commonJdbcJvm)
    implementation(platform(libs.awsSdk2.bom))
    implementation(libs.awsSdk2.dynamodb)
    implementation(libs.awsSdk2.secretsManager)
    implementation(libs.awsSdk2.auth)
    implementation(libs.awsSdk2.regions)
    implementation(libs.graal.polyglot.polyglot)
    implementation(libs.graal.jsLanguage)
    implementation(libs.graal.truffle.runtime)

    runtimeOnly(libs.logback.classic)

    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junitJupiter)
}

// The convention plugin excludes group="junit" from testImplementation (and by inheritance from
// testCompileClasspath). Create an isolated configuration that has no parent and therefore no
// exclude rules, resolve junit:junit from it, and inject the JAR only into the compile tasks.
// At runtime the class is never loaded, so we don't need it on the runtime classpath.
val junitCompileStubs by configurations.creating {
    isTransitive = false
}
dependencies {
    junitCompileStubs("junit:junit:4.13.2")
}
tasks.withType<JavaCompile>().matching { it.name == "compileTestJava" }.configureEach {
    classpath += junitCompileStubs
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>()
    .matching { it.name == "compileTestKotlin" }
    .configureEach {
        libraries.from(junitCompileStubs)
    }
tasks.withType<Test>().configureEach {
    classpath += junitCompileStubs
}

tasks.test {
    val includeTagsProp = providers.systemProperty("includeTags")
    val excludeTagsProp = providers.systemProperty("excludeTags")
    useJUnitPlatform {
        includeTagsProp.orNull?.let { includeTags(it) }
        excludeTagsProp.orNull?.let { excludeTags(it) }
    }
}
