import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeBum"
    versionCode = 23
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "es"
        baseUrl = "https://animebum.org"
    }
}

dependencies {
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:luluextractor"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:universalextractor"))
    implementation(project(":lib:uqloadextractor"))
    implementation(project(":lib:vidhideextractor"))
    implementation(project(":lib:voeextractor"))
}
