import java.util.Properties

plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val localProps = rootProject.file("local.properties")
val sdkDir: String = when {
    localProps.isFile -> {
        val props = Properties()
        localProps.inputStream().use { props.load(it) }
        props.getProperty("sdk.dir")?.replace("\\\\", "\\")
            ?: error("sdk.dir missing in local.properties")
    }
    System.getenv("ANDROID_HOME") != null -> System.getenv("ANDROID_HOME")!!
    else -> error("Set sdk.dir in local.properties or ANDROID_HOME")
}

dependencies {
    compileOnly(files("$sdkDir/platforms/android-34/android.jar"))
    compileOnly("androidx.annotation:annotation:1.6.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}
