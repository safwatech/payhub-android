plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group = "ly.payhub"
version = "1.0.0"

android {
    namespace = "ly.payhub"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf("-Xexplicit-api=strict")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.useJUnitPlatform()
            }
        }
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = group.toString()
            artifactId = "payhub"
            version = version

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("PayHub Android SDK")
                description.set("Official PayHub SDK for Android (Kotlin coroutines + OkHttp).")
                url.set("https://payhub.ly")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
}
