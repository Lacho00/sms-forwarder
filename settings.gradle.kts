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
if (file("app").isDirectory &&
    (System.getenv("ANDROID_HOME") != null || file("local.properties").exists())
) {
    include(":app")
}
