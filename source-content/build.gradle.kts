plugins { kotlin("jvm"); `java-library`; `java-test-fixtures`; alias(libs.plugins.kotlin.serialization) }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { jvmToolchain(21); compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
dependencies {
    api(project(":source-execution"))
    api(project(":source-import"))
    api(project(":source-network"))
    api(project(":source-rules"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(project(":source-rhino"))
    testFixturesApi("com.squareup.okhttp3:mockwebserver:5.4.0")
    testFixturesImplementation(libs.junit)
    testFixturesImplementation(project(":source-rhino"))
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testFixturesApi(libs.kotlinx.serialization.json)
}
tasks.test { useJUnit() }
