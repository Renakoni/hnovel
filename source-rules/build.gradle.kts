plugins { kotlin("jvm"); alias(libs.plugins.kotlin.serialization) }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation("org.jsoup:jsoup:1.16.2")
    implementation("com.jayway.jsonpath:json-path:2.10.0")
    implementation("cn.wanghaomiao:JsoupXpath:2.5.3")
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation(libs.junit)
}

tasks.test { useJUnit(); maxHeapSize = "512m" }
