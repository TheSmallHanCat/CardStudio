plugins { id("com.android.application") }

android {
    namespace = "io.github.thesmallhancat.cardstudio"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.thesmallhancat.cardstudio"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.0-alpha02"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    buildFeatures { buildConfig = true }
}

dependencies {
    compileOnly("io.github.libxposed:api:101.0.1")
    implementation("io.github.libxposed:service:101.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
