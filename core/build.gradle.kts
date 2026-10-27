import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

dependencies {
    implementation("com.wolt.osm:parallelpbf:0.3.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    environment("KIESEL_EXTRACT", System.getenv("KIESEL_EXTRACT") ?: "")
}

tasks.register<JavaExec>("buildPacks") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "kiesel.BuilderKt"
    maxHeapSize = "12g"
    workingDir = rootDir
    val regions = findProperty("regions")?.toString() ?: "europe/liechtenstein"
    args(listOf("build/maps", "build/packs") + regions.split(","))
}
