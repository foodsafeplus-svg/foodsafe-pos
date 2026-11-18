# FOODSAFE+ POS — caisse Android pour la restauration

Logiciel d'encaissement Android (Kotlin, Jetpack Compose, Room) conçu pour viser les exigences françaises
(art. 286-I-3° bis du CGI, référentiels NF525 / LNE). **Non certifié à ce jour** : la certification relève d'un organisme accrédité.

## Documents

| | |
|---|---|
| [A. Besoins et ambiguïtés](docs/A-besoins-et-ambiguites.md) | [B. Architecture et flux](docs/B-architecture.md) |
| [C. Modèle de données](docs/C-modele-de-donnees.md) | [D. Parcours UX et écrans](docs/D-parcours-ux-et-ecrans.md) |
| [E. Roadmap et critères d'acceptation](docs/E-roadmap.md) | [G. État de l'incrément 1](docs/G-etat-increment-1.md) |
| [G. État de l'incrément 2 (ticket fiscal, Z, PIN)](docs/G-etat-increment-2.md) | [Exemples de tickets](docs/exemples/) |

**Démo :** au premier lancement, deux comptes fictifs sont créés : Marie (serveuse) PIN `1111`, Paul (responsable) PIN `9999`.

## Structure

```
core/   Kotlin/JVM pur : argent, TVA, remises, commandes, niveaux, envois cuisine, divisions,
        paiements, attribution espèces, journal chaîné, documents fiscaux signés, X/Z,
        PIN et droits (aucune dépendance Android)
app/    Application Android : Compose, Room (triggers append-only), dépôt transactionnel
scripts/ build-apk.sh, create-release-keystore.sh
```

## Prérequis

- JDK 17 ou 21
- SDK Android (plateforme 35, build-tools 35.0.0), référencé par `ANDROID_HOME` ou `local.properties` (`sdk.dir=...`).
  Sans SDK, seul le module `core` est construit.

## Commandes

```bash
./gradlew :core:test                 # tests du cœur métier (sans SDK Android)
./gradlew :app:testDebugUnitTest     # tests Room + parcours UI (Robolectric) : docs/captures/*.png, docs/exemples/*.txt
./gradlew :app:assembleDebug         # APK debug -> app/build/outputs/apk/debug/app-debug.apk
scripts/build-apk.sh debug           # tests + APK debug + empreinte SHA-256
```

### APK release signée (distribution hors Play Store)

```bash
scripts/create-release-keystore.sh   # une seule fois : crée foodsafe-release.jks + keystore.properties (non versionnés)
scripts/build-apk.sh release         # tests + app-release.apk signée + vérification apksigner
```

Sauvegardez la clé et son mot de passe hors ligne : sans elle, aucune mise à jour ne pourra s'installer par-dessus.

### Installation sur une caisse / tablette

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

ou copier l'APK sur l'appareil et autoriser « Installer des applications inconnues » pour le gestionnaire de fichiers.
Android 7.0 (API 24) minimum.

### Réseau d'entreprise

Si Maven Central passe par un proxy ou un miroir : `-PmavenMirror=https://miroir/maven2` (ou dans `~/.gradle/gradle.properties`).
