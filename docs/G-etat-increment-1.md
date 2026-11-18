# G. État réel de l'incrément 1 (18/11/2026)

> **Aucune certification NF525 / LNE n'est acquise.** Le code est conçu pour être certifiable ; la certification
> sera délivrée (ou non) par un organisme accrédité après audit d'une version complète.

## Ce qui fonctionne vraiment (testé automatiquement)

| Fonction | Preuve |
|----------|--------|
| Montants en centimes `Long`, arrondi demi au-dessus, dépassement détecté | `MoneyTest` |
| Ventilation TVA par taux (HT + TVA = TTC exactement, 10 000 tirages aléatoires) | `PricingTest` |
| Remise ligne % ou € ; remise note **uniquement %** (impossible en € : pas de type pour ça) ; appliquée ligne par ligne avec la TVA de chaque ligne ; exemple pizza/bière −10 % ⇒ 10,80 € et 5,40 € | `PricingTest` |
| Refus d'une remise € supérieure à la ligne ; jamais de net négatif (propriété sur 2 000 notes aléatoires) | `PricingTest` |
| Envoi cuisine : un second envoi ne renvoie rien ; routage par destination (cuisine / bar) | `KitchenDispatcherTest`, `PosRepositoryTest` |
| Niveaux N1…Nn, « À suivre », bouton dynamique « Réclamer N1 » puis N2…, réclamation strictement séquentielle, note mise en attente | `KitchenDispatcherTest`, test UI |
| Ajout sur niveau déjà réclamé ⇒ bon « AJOUT N2 » avec les seuls nouveaux articles | `KitchenDispatcherTest`, `PosRepositoryTest` |
| Annulation : motif obligatoire, journalisé, **non imprimé** ; bon inversé si la ligne était partie ; annulation partielle | `KitchenDispatcherTest`, `PosRepositoryTest` |
| Changement de table 43 → 56 : petit bon aux seules destinations ayant reçu des préparations | `KitchenDispatcherTest` |
| Division égale avec centimes aux premières parts ; division par montants | `SplitAndPaymentTest` |
| Paiement mixte, rendu monnaie espèces, refus carte > reste dû | `SplitAndPaymentTest`, `PosRepositoryTest` |
| Règles d'attribution des espèces (fixe/pad/banane/plusieurs tiroirs ; carte = aucun fond) | `SplitAndPaymentTest` |
| Idempotence : une même clé (double tap, renvoi réseau) n'est jamais rejouée, y compris pour un paiement | `PosRepositoryTest` |
| Journal append-only chaîné SHA-256 : altération ou suppression d'un maillon détectée ; UPDATE/DELETE et insertion hors chaîne refusés **par la base** (triggers) | `JournalTest` (SQLite JDBC), `PosRepositoryTest` (Room) |
| Paiements append-only (triggers) ; commande soldée non modifiable | `PosRepositoryTest` |
| Signature ECDSA P-256 vérifiable et rejet d'un contenu modifié | `JournalChainTest` |
| Parcours écran complet : produits → envoyer → remise 10 % → bons → encaisser → vérifier le journal | `OrderScreenUiTest` (Compose + Room réels sous Robolectric), captures dans `docs/captures/` |
| APK debug compilée ; APK release signée (schéma v2) vérifiée avec `apksigner` sur une clé jetable | build Gradle |

Total : **35 tests** dans `:core`, **7 tests** dans `:app`, tous au vert.

## Ce qui est simulé

- Catalogue de démonstration codé en dur (`DemoCatalog`).
- Utilisateur « marie », établissement `DEMO-ETAB` et terminal `T01` fixes (pas de connexion PIN ni d'appairage).
- **Impression** : les bons sont rendus en texte et affichés à l'écran ; aucune imprimante n'est pilotée.
- **Carte bancaire** : saisie du montant validé sur un TPE autonome ; aucun TPE n'est intégré.
- Clé de signature logicielle dans les tests (pas encore dans l'Android Keystore).

## Ce qui reste à faire (extrait, voir docs/E)

Documents fiscaux (ticket, note provisoire, duplicata, numérotation continue, grand total perpétuel, signature Keystore), clôtures X/Z, fonds de caisse, plan de salle, PIN et droits, HAL imprimante ESC/POS réelle, divisions dans l'UI, remboursements et avoirs, hub local multi-terminal, backend cloud, back-office, chiffrement de la base.

## Risques identifiés

1. **Conformité** : le format des documents fiscaux et des archives doit être calé avec l'organisme certificateur avant d'écrire la phase 1 fiscale.
2. **Pas testé sur matériel réel** : le test UI tourne sous Robolectric (rendu Android simulé). Un essai sur la caisse/tablette cible est indispensable (performances, taille d'écran, Android 7).
3. **Monoterminal** : la cohérence multi-terminal (hub) n'existe pas encore ; deux tablettes ne partagent rien aujourd'hui.
4. **Clé de signature APK** : à générer et sauvegarder par vous (`scripts/create-release-keystore.sh`) ; sa perte empêche toute mise à jour.
5. **Défaut d'ergonomie trouvé et corrigé** : la notification recouvrait le bouton « Remise note % » ; elle s'affiche désormais sous la grille produits. D'autres défauts de ce type ne se verront qu'en service réel.
