plugins { alias(libs.plugins.kotlinJvm); application }
kotlin {
    jvmToolchain(27)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26) }
}
dependencies {
    implementation(project(":protocol"))
    testImplementation(libs.junitJupiter)
    testImplementation(libs.mockWebServer)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutinesTest)
    testRuntimeOnly(libs.junitPlatformLauncher)
}
tasks.test { useJUnitPlatform() }

java {
    sourceCompatibility = JavaVersion.VERSION_26
    targetCompatibility = JavaVersion.VERSION_26
}
tasks.withType<JavaCompile>().configureEach { options.release.set(26) }

application { mainClass.set("staff.android.network.server.ServerMainKt") }
