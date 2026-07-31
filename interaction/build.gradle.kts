plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":renderer-core"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.1")
    testImplementation("org.junit.platform:junit-platform-launcher:1.13.1")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
