# G. État réel de l'incrément 2 (18/11/2026) — ticket fiscal, clôtures, PIN

> **Aucune certification NF525 / LNE n'est acquise.** Les choix ci-dessous (contenu signé, mentions du ticket,
> totaux perpétuels, format du Z) doivent être confrontés au référentiel de l'organisme certificateur choisi.

## Ce qui fonctionne vraiment (testé automatiquement)

| Fonction | Preuve |
|----------|--------|
| **Ticket fiscal** émis automatiquement au solde nul, dans la même transaction que le dernier paiement | `FiscalRepositoryTest`, test UI |
| Numérotation continue par terminal et par type (`T01-T-000001`, `T01-N-…`, `T01-D-…`, Z) | `FiscalTest`, trigger SQL `fiscal_chain_insert` |
| Chaînage SHA-256 par type + **signature ECDSA P-256** de chaque document ; vérification complète (numéros, chaîne, contenu, signature) | `FiscalTest`, `FiscalRepositoryTest` |
| Clé de signature générée dans l'**Android Keystore** (non extractible) ; clés publiques conservées pour vérifier l'historique | code `AndroidKeystoreKeyProvider` (voir « Simulé ») |
| **Grand total perpétuel** et GT perpétuel absolu, jamais remis à zéro | `FiscalTest` |
| Documents fiscaux et clés **en ajout seul** : UPDATE, DELETE et insertion hors séquence refusés par la base | `FiscalRepositoryTest` |
| **Note provisoire** (« Imprimer la note ») : marquée « TICKET PROVISOIRE » avant les produits, sans ventilation TVA, avec paiements et reste dû, sans effet sur les totaux | `FiscalTest`, `docs/exemples/note-provisoire-80mm.txt` |
| **Duplicata** marqué, numéroté dans sa propre séquence, soumis à validation responsable | `FiscalTest`, `FiscalRepositoryTest` |
| **Ticket cadeau** sans aucun prix, sans seconde vente fiscale | `FiscalTest` |
| **Rapport X** (consultation, rien n'est remis à zéro) et **clôture Z** (tickets depuis le Z précédent, TVA par taux, moyens de paiement, remises, GT perpétuel) ; Z sans ticket accepté | `FiscalTest`, `FiscalRepositoryTest` |
| Rendu 80 mm (48 colonnes) et 58 mm (32 colonnes) | `docs/exemples/*.txt` |
| **Connexion par PIN** (PBKDF2-HMAC-SHA256 salé, vecteur de référence RFC 7914 vérifié) ; verrouillage 30 s après 5 échecs ; tentatives journalisées sans le PIN | `AuthTest`, `FiscalRepositoryTest` |
| **Droits par action** : libre / limite / PIN responsable / validation systématique / interdit, vérifiés dans le dépôt de données (pas seulement l'écran) ; le validateur est tracé dans le journal | `AuthTest`, `FiscalRepositoryTest`, test UI |
| Politique par défaut : remise note ≤ 10 % libre pour un serveur, au-delà PIN responsable ; annulation d'un produit envoyé et duplicata : PIN responsable ; Z interdit au serveur | idem |
| **Déconnexion** manuelle ou après 2 min d'inactivité : note en cours mise en attente, retour à l'écran PIN ; l'utilisateur suivant arrive sur les tables, jamais sur la note du précédent | test UI (manuelle) ; inactivité : code, non testé automatiquement |
| **Accueil « Tables »** : tables ouvertes avec propriétaire, statut et total ; ouverture d'une table par numéro ; refus d'ouvrir deux fois la même table | `FiscalRepositoryTest`, test UI |
| **Migration** de la base 0.1 → 0.2 sans perte (paiements, journal) | `MigrationTest` |
| Compatibilité Android 7 : codec Base64 maison (java.util.Base64 absent avant Android 8), java.time via desugaring | `B64Test` + revue des imports |

Total : **50 tests** dans `:core`, **16 tests** dans `:app`, tous au vert.

## Ce qui est simulé

- **Équipe de démonstration** créée au premier lancement : Marie (serveuse, PIN 1111) et Paul (responsable, PIN 9999). À changer avant toute utilisation réelle (gestion des employés : prochaine étape).
- Établissement fictif (raison sociale, SIRET, TVA) : à renseigner (paramétrage établissement : prochaine étape).
- Impression : aperçu à l'écran, aucune imprimante pilotée.
- Dans les tests, la clé de signature est logicielle (Robolectric n'a pas d'Android Keystore). **Le chemin Keystore n'a pas encore tourné sur un vrai appareil.**

## Ce qui reste à faire (phase 1)

Plan de salle graphique (positions, déplacements, fusions), transfert de tables/propriété, fonds de caisse et comptage, Z automatique à heure paramétrable, archives périodiques signées exportables, remboursements et avoirs, paramétrage établissement/employés sur la caisse, HAL imprimante ESC/POS.

## Risques

1. **Contenu du ticket et des archives** : les mentions légales et le format d'archive doivent être validés (expert-comptable + organisme). Exemples prêts à relire dans `docs/exemples/`.
2. **Réinstallation de l'application** = nouvelle clé de signature : l'historique reste vérifiable (anciennes clés publiques en base), mais la perte de la base locale sans sauvegarde serait bloquante. La sauvegarde/synchronisation cloud est donc un prérequis avant production.
3. **PIN à 4 chiffres** : protection faible si quelqu'un copie la base ; le verrouillage limite les essais sur l'écran, pas hors ligne. Chiffrement de la base prévu en phase 2.
4. Déconnexion pour inactivité non couverte par un test automatique (à tester sur appareil).
