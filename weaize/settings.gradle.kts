pluginManagement {
  repositories {
    google()
    // GCS mirror of Maven Central (repo.maven.apache.org rate-limits some networks)
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
  }
}

rootProject.name = "weaize"

include(":app")
