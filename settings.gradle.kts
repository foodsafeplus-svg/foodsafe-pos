// Miroir Maven optionnel (ex. proxy d'entreprise) : -PmavenMirror=http://hôte/maven2 ou dans ~/.gradle/gradle.properties
val mavenMirror: String? = providers.gradleProperty("mavenMirror").orNull

pluginManagement {
    val mirror: String? = providers.gradleProperty("mavenMirror").orNull
    repositories {
        google()
        if (mirror != null) maven(mirror) { isAllowInsecureProtocol = mirror.startsWith("http://127.0.0.1") }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        if (mavenMirror != null) maven(mavenMirror) { isAllowInsecureProtocol = mavenMirror.startsWith("http://127.0.0.1") }
        mavenCentral()
    }
}
rootProject.name = "foodsafe-pos"

include(":core")

// Le module Android n'est inclus que si un SDK Android est disponible,
// pour que `./gradlew :core:test` fonctionne sur n'importe quelle machine/CI JVM.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").takeIf { it.exists() }?.readText()?.contains("sdk.dir") == true
if (hasAndroidSdk) include(":app") else logger.warn("SDK Android introuvable : module :app ignoré")
