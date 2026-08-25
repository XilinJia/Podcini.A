
plugins {
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "${project.findProperty("kotlin_version")}" apply false
    kotlin("plugin.serialization") version "${project.findProperty("kotlin_version")}" apply false
    id("io.github.xilinjia.krdb") version "${project.findProperty("krdb_version")}" apply false
//    id("com.google.gms.google-services") version "4.4.4" apply false
}

buildscript {
    dependencies {}
}
