plugins { kotlin("jvm") }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { jvmToolchain(21); compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
dependencies {
    implementation("org.mozilla:rhino:1.8.1")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}
tasks.test { useJUnit() }
