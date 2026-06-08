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
}
