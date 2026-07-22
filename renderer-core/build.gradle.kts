plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":crystal-analysis"))
    implementation(project(":crystal-data"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.1")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
