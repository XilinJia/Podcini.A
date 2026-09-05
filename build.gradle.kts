
plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "${project.findProperty("kotlin_version")}" apply false
    kotlin("plugin.serialization") version "${project.findProperty("kotlin_version")}" apply false
    id("io.github.xilinjia.krdb") version "${project.findProperty("krdb_version")}" apply false
}

buildscript {
    dependencies {
        classpath("com.android.tools.build:gradle:9.4.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}
