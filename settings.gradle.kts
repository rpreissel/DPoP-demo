pluginManagement {
    repositories {
        if (java.net.Socket().use {
                try {
                    it.connect(java.net.InetSocketAddress("nxrm.dst.tk-inline.net", 443), 800)
                    true
                } catch (e: Exception) {
                    false
                }
            }) {
            maven { url = uri("https://nxrm.dst.tk-inline.net/repository/maven-public/") }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

val tkNexusReachable = java.net.Socket().use {
    try {
        it.connect(java.net.InetSocketAddress("nxrm.dst.tk-inline.net", 443), 800)
        true
    } catch (e: Exception) {
        false
    }
}
gradle.extra["tkNexusReachable"] = tkNexusReachable

rootProject.name = "dpop-demo"