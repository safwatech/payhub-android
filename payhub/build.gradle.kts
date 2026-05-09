plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    // Vanniktech's gradle-maven-publish handles bundle assembly + GPG
    // signing + Sonatype Central Portal upload. As of 0.30 it targets
    // the new Central Portal (https://central.sonatype.com), not the
    // legacy oss.sonatype.org / nexus-staging flow.
    id("com.vanniktech.maven.publish") version "0.30.0"
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

    // The vanniktech-maven-publish plugin (mavenPublishing { … } block
    // below) configures singleVariant("release") with sources + javadoc
    // jars itself. Re-declaring it here triggers AGP's "Using
    // singleVariant publishing DSL multiple times" error.

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

mavenPublishing {
    // Central Portal target. automaticRelease=true means the staged
    // bundle auto-publishes once Sonatype validation passes; flip to
    // false if you want manual Publish click in the Portal UI.
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    coordinates("ly.payhub", "payhub-android", project.version.toString())

    pom {
        name.set("PayHub Android SDK")
        description.set("Official PayHub SDK for Android — Sadad, Moamalat, Mobicash, T-Lync, Adfali behind one API. Kotlin coroutines + OkHttp.")
        inceptionYear.set("2026")
        url.set("https://payhub.ly")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("safwatech")
                name.set("Safwa Tech")
                email.set("info@payhub.ly")
                organization.set("Safwa Tech")
                organizationUrl.set("https://payhub.ly")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/safwatech/payhub-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/safwatech/payhub-android.git")
            url.set("https://github.com/safwatech/payhub-android")
        }
        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/safwatech/payhub-android/issues")
        }
    }
}
