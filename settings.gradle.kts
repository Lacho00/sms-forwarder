pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "sms-forwarder"

include(":core")
val androidSdkPresent = System.getenv("ANDROID_HOME")?.let { file(it).isDirectory } == true ||
    file("local.properties").exists()
if (file("app").isDirectory && androidSdkPresent) {
    include(":app")
}
