plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
    sourceSets.test {
        kotlin.srcDir("reference/kotlin")
    }
}

dependencies {
    testImplementation(project(":source-import"))
    testImplementation(project(":source-rules"))
    testImplementation(project(":source-network"))
    testImplementation(libs.junit)
    // Keep oracle dependencies at the reference revision's versions, not the app's.
    testImplementation("org.jsoup:jsoup:1.16.2")
    testImplementation("com.jayway.jsonpath:json-path:2.10.0")
    testImplementation("cn.wanghaomiao:JsoupXpath:2.5.3")
    testImplementation("com.google.code.gson:gson:2.13.2")
    testImplementation("org.mozilla:rhino:1.8.1")
}

tasks.test {
    useJUnit()
    maxHeapSize = "512m"
    systemProperty("compatibility.reportDir", layout.buildDirectory.dir("reports/source-compatibility").get().asFile.absolutePath)
    systemProperty("compatibility.projectDir", projectDir.absolutePath)
}
