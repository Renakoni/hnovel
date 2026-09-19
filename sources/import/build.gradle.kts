plugins { kotlin("jvm"); alias(libs.plugins.kotlin.serialization) }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { jvmToolchain(21); compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
dependencies {
    implementation(project(":source-network"))
    implementation(libs.kotlinx.serialization.json)
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
}
tasks.test { useJUnit() }
