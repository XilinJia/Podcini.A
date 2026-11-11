
plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "${project.findProperty("kotlin_version")}" apply false
    kotlin("plugin.serialization") version "${project.findProperty("kotlin_version")}" apply false
    id("io.github.xilinjia.krdb") version "${project.findProperty("krdb_version")}" apply false
}

buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}
