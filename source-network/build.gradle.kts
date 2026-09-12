plugins { kotlin("jvm"); alias(libs.plugins.kotlin.serialization) }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { jvmToolchain(21); compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }

dependencies {
    implementation(project(":source-rules"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
}

tasks.test { useJUnit(); maxHeapSize = "512m" }
