# E. Roadmap MVP et phases futures

Chaque phase se termine par une **recette sur matériel réel** et une revue de conformité. Une fonctionnalité n'est « livrée » que si ses tests automatisés passent en CI **et** sa recette manuelle est validée.

## Phase 0 — Fondations (incrément 1, en cours)

| Livrable | Critère d'acceptation | Tests |
|----------|-----------------------|-------|
| Module `:core` : argent, TVA, remises | Exemple pizza/bière −10 % ⇒ 10,80 € et 5,40 € ; ventilation HT/TVA exacte ; aucune somme négative ; remise note en € impossible (type) | `TaxCalculatorTest`, `DiscountTest` |
| Commandes, niveaux, envoi cuisine | Un second « Envoyer » n'envoie rien ; « AJOUT N2 » ne contient que les nouveaux articles ; réclamation strictement séquentielle ; annulation sans motif refusée | `KitchenDispatcherTest`, `OrderTest` |
| Division | 100,00 € / 3 = 33,34 + 33,33 + 33,33 | `SplitTest` |
| Attribution espèces | Les 4 règles du cahier des charges + carte ⇒ aucun fond | `CashAttributionTest` |
| Journal chaîné + signature | Altération d'un octet détectée ; triggers SQL refusent UPDATE/DELETE | `JournalChainTest`, `JournalSqliteTest` |
| App Android | APK debug installable ; parcours « ajouter, envoyer, remise, encaisser, consulter journal » fonctionnel | build CI + test manuel |

## Phase 1 — MVP restaurant mono-établissement (8 à 12 semaines)

1. Connexion PIN, rôles, politiques d'action, déconnexion par inactivité.
2. Plan de salle (une salle), ouverture, déplacement, renumérotation, transfert serveur.
3. Catalogue local éditable sur la caisse (familles, produits, TVA par mode).
4. HAL imprimante ESC/POS TCP : encodeur, file persistante, statut DLE EOT, chaîne de secours, retour principale.
5. Paiement complet : mixte, rendu, diviser le reste, séparer la note.
6. **Documents fiscaux** : ticket, note provisoire, duplicata, numérotation continue, grand total perpétuel, signature Keystore.
7. Fonds par tiroir, déclarations immuables, X/Z journalier, Z auto à heure paramétrable.
8. Remboursement total/partiel avec document lié.
9. Archives périodiques signées exportables (format à valider avec l'organisme).

**Critères :** une journée de service simulée (200 commandes, 3 coupures imprimante, 1 redémarrage) sans perte, sans doublon cuisine, Z égal à la somme des tickets, chaîne vérifiée. **Tests :** unitaires `:core` ≥ 90 % des règles métier, tests Room instrumentés, tests de propriétés (montants aléatoires), test de bout en bout Compose sur émulateur.

## Phase 2 — Multi-terminal et cloud (8 semaines)

- Hub local Ktor, découverte NSD, verrous optimistes, idempotence, mode autonome, promotion manuelle de hub.
- Fond « banane », remise au responsable, plusieurs tiroirs.
- Backend cloud + back-office : tableau de bord, catalogue distant versionné, audit, Z établissement.
- Chiffrement base (SQLCipher), appairage QR.

**Critères :** 3 terminaux + 2 imprimantes, coupure du Wi-Fi 10 min en plein service : aucune commande perdue ni dupliquée, synchro cloud complète en < 2 min au retour. Tests de chaos (coupures aléatoires) automatisés.

## Phase 3 — Certification et pilote

- Dossier technique pour l'organisme (Infocert NF525 ou LNE), corrections d'audit.
- Pilote dans 1 à 3 restaurants. **Aucune commercialisation avant certificat.**

## Phase 4+ — Extensions

| Extension | Prérequis externe |
|-----------|-------------------|
| Promotions avancées (X achetés / Y offerts, calendrier) | — |
| Avoirs multi-sites | Backend cloud disponible |
| TPE intégré | Contrat et SDK prestataire, matériel compatible |
| KDS | Hub local |
| Borne de commande | Hub + paiement intégré |
| Click & collect / commande en ligne | Backend cloud, prestataire de paiement en ligne |
| Uber Eats / Deliveroo | Contrat partenaire et accès API accordés par les plateformes |
| Fidélité, stocks, inventaires, autres métiers (bar, boulangerie) | — |

## Risques transverses

1. **Certification** : délai et coût d'audit, exigences de détail découvertes tard → contacter l'organisme dès la phase 1.
2. **Matériel hétérogène** : imprimantes « ESC/POS compatibles » partielles, Bluetooth instable → liste de matériel validé publiée.
3. **APK hors Play Store** : mises à jour (prévoir un mécanisme de MAJ signé et vérifié), installation « sources inconnues », Play Protect.
4. **Charge de travail** : le périmètre complet représente plusieurs années-personne ; la roadmap priorise un MVP vendable.
