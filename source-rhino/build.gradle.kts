plugins { kotlin("jvm"); `java-library` }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { jvmToolchain(21); compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
dependencies {
    implementation("org.mozilla:rhino:1.8.1")
    testImplementation(libs.junit)
    implementation("cn.hutool:hutool-crypto:5.8.22")
    implementation("com.github.liuyueyi.quick-chinese-transfer:quick-transfer-core:0.2.16")
    api(project(":source-rules"))
    implementation("org.jsoup:jsoup:1.16.2")
    implementation(libs.okhttp)
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
    implementation(libs.kotlinx.serialization.json)
}
tasks.test { useJUnit() }
