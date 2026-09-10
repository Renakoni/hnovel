plugins { kotlin("jvm"); alias(libs.plugins.kotlin.serialization) }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { jvmToolchain(21); compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":source-rhino"))
    implementation("org.mozilla:rhino:1.8.1")
    implementation(project(":source-network"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp)
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
}
tasks.test { useJUnit() }
