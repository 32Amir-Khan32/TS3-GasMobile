plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.github.Manevolent:ts3j:db57d60c989e399626aa16d921390f5033e6cdeb") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    }
}
