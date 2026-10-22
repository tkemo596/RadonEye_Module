pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            name = "IA Releases"
            url = uri("https://nexus.inductiveautomation.com/repository/inductiveautomation-releases")
        }
    }
}

rootProject.name = "radoneye-driver"

include(":gateway")
