# A. Reformulation des besoins et ambiguïtés bloquantes

> Statut : document de travail v0.1 (15/11/2026). Rien dans ce dépôt n'est certifié NF525 ou LNE :
> la certification est délivrée par un organisme tiers accrédité, après audit du produit livré.

## 1. Ce que l'on construit

FOODSAFE+ POS est un **système d'encaissement professionnel pour la restauration**, distribué en **APK Android signée** (hors Play Store), qui doit pouvoir être certifié au regard de l'article 286-I-3° bis du CGI (conditions « ISCA » : Inaltérabilité, Sécurisation, Conservation, Archivage) via le référentiel NF525 (AFNOR Certification / Infocert) ou le référentiel LNE.

Il se compose de cinq briques :

| # | Brique | Rôle | Phase |
|---|--------|------|-------|
| 1 | **App caisse Android** (Compose) | Caisse fixe, tablette, pad serveur. Prise de commande, cuisine, encaissement, clôtures. | MVP |
| 2 | **Hub local** | Service embarqué sur un terminal désigné (« caisse maître ») qui coordonne commandes/tables/impressions entre terminaux sur le LAN, **sans Internet**. | MVP (mono-terminal d'abord, puis multi) |
| 3 | **Backend cloud + back-office web** | Multi-établissements, catalogue, stats, archives, audit. | Phase 2 |
| 4 | **Modules futurs** | KDS, borne, commande en ligne / click & collect, connecteurs Uber Eats / Deliveroo, fidélité, stocks. | Phases 3+ |
| 5 | **Couche d'abstraction matérielle (HAL)** | Imprimantes ESC/POS (réseau, USB, Bluetooth), tiroir, lecteurs (code-barres, RFID, Dallas), TPE. | MVP pour imprimante réseau + tiroir via imprimante ; le reste par phases |

## 2. Besoins reformulés par domaine

**Commandes, salles, tables.** Plans de salle multiples ; table = note ouverte appartenant à un serveur propriétaire. Déplacement, fusion, transfert, renumérotation (43 → 56) avec petit ticket de notification aux seules imprimantes cuisine concernées, sans réimprimer les préparations. Chaque ligne garde son auteur. Visibilité des notes paramétrable par mode de vente (partagée / propriétaire seul ; managers voient tout ; un manager qui ouvre une table n'en devient pas propriétaire). Transfert unitaire ou en masse avec audit.

**Modes de vente et cuisine.** Sur place / emporter / livraison, chacun avec sa configuration (envoi cuisine auto ou manuel, impression ticket auto, visibilité…). Niveaux de service N1…Nn séparés par « À suivre ». « Réclamer N1 », puis N2… séquentiellement ; après réclamation retour plan de salle. Ajouts sur commande existante = « direct » par défaut ; ajout sur niveau déjà réclamé = ticket « AJOUT Nx » avec les seuls nouveaux articles. **Jamais de renvoi d'une ligne déjà envoyée.** Annulation d'un produit envoyé = ticket d'annulation (inversé noir/blanc si l'imprimante le permet), motif obligatoire journalisé mais non imprimé en cuisine.

**Impression.** Routage produit/famille → destination (cuisine, bar…). Chaîne de secours par imprimante : secours explicite → imprimante ticket caisse → autre disponible ; bon de secours marqué « DESTINATION : CUISINE ». File persistante, statut, reprise, retour automatique à la principale. **Un envoi TCP réussi ne vaut pas impression confirmée** : statut « incertain » sans réimpression aveugle.

**Authentification et droits.** Rôles serveur / responsable / administrateur ; chaque action a une politique : libre, avec limite, PIN responsable, validation systématique, interdit. Connexion : aucune, PIN, Dallas, RFID, code-barres/QR, biométrie si SDK. Déconnexion sur inactivité paramétrable ; à la déconnexion, la note est mise en attente et l'utilisateur suivant revient à l'accueil.

**Paiements.** Écran avec tous les moyens, pavé numérique, raccourcis espèces, rendu monnaie, paiements mixtes. « Diviser le reste à payer » (une note, solde décroissant) ≠ « Séparer la note » (notes indépendantes). Division par parts / montants / articles ; centimes résiduels sur les premières parts. Ticket provisoire (« TICKET PROVISOIRE », sans ventilation TVA, ne clôture pas). Ticket final à solde nul. Ticket cadeau sans prix, sans seconde vente fiscale.

**Remboursements, avoirs.** Jamais d'effacement : opérations correctives liées à la vente d'origine. Avoir numéroté avec QR, consommable partiellement, portée magasin ou enseigne, validité configurable ; consommation concurrente sécurisée, politique hors ligne explicite.

**Remises et promotions.** Remise ligne en % ou en € ; **remise ticket uniquement en %**, ventilée ligne par ligne en conservant le taux de TVA de chaque ligne (pizza 12 € à 10 %, bière 6 € à 20 %, −10 % ⇒ 10,80 € et 5,40 €). Droits et seuils par profil, PIN responsable. Promotions calendaires (ex. 2 achetées, 3e offerte) avec cumul / priorité explicites ; jamais de montant négatif.

**Fonds de caisse.** Optionnel par établissement. Si activé : fonds par **tiroir physique**, option « banane » par serveur. Déclarations successives **immuables** (théorique, déclaré, reçu, écarts, acteurs). Comptage direct ou calculette par coupure, mode visible ou aveugle. Règles d'attribution des espèces (caisse fixe → tiroir associé ; pad + banane → banane ; pad sans banane + un tiroir → ce tiroir ; plusieurs tiroirs → demander). X / Z, Z automatique à heure paramétrable y compris après minuit, Z historique jamais réécrit.

**Back-office.** Web responsive multi-établissements : tableau de bord (CA encaissé distinct des commandes ouvertes, heure de dernière synchro), gestion catalogue à distance avec versionnement et audit, diffusion silencieuse (« Mise à jour catalogue reçue » quelques secondes).

**Hors ligne.** Le LAN suffit pour caisses, tables, impressions, KDS. Le cloud est rattrapé à la reconnexion. TPE, plateformes de livraison et avoirs multi-sites dépendent de leurs services : on ne promet rien sans eux.

**Conformité et sécurité.** Inaltérabilité (chaînage + signature), numérotation continue, clôtures périodiques, archives signées vérifiables, journal des événements techniques, corrections non destructives ; RGPD, chiffrement, séparation des établissements, aucun PAN carte stocké.

## 3. Hypothèses retenues (non bloquantes, modifiables)

| Hypothèse | Pourquoi |
|-----------|----------|
| Les prix catalogue sont saisis **TTC** ; HT et TVA en sont déduits. | Usage standard en restauration B2C. |
| Montants en **centimes `Long`**, taux de TVA en **points de base** (1000 = 10,00 %). Arrondi « demi au-dessus » sur chaque ligne. | Pas de flottants ; arrondi contrôlé et documenté. |
| La TVA d'un ticket est calculée **par taux sur la somme TTC des lignes de ce taux** (et non ligne par ligne puis sommée). | Minimise les écarts d'arrondi ; méthode courante, à faire valider par l'expert-comptable. |
| La remise ticket % s'applique sur le net de chaque ligne **après** sa remise ligne éventuelle. | Évite qu'une remise ticket « annule » l'effet d'une remise ligne ; cumul explicite. |
| Chaque terminal possède **sa propre chaîne fiscale** (numérotation préfixée par l'identifiant terminal). | Un terminal reste capable d'émettre des tickets conformes même si le hub ou le cloud est absent. |
| Hub local hébergé sur la **caisse fixe maître** (pas de boîtier dédié au MVP). | Pas de matériel supplémentaire ; bascule documentée. |
| Android minimum : **7.0 (API 24)**. | Couvre la majorité des caisses Android du marché (Sunmi, iMin, etc.). |
| Imprimantes de référence : **ESC/POS réseau (port 9100)**. | Protocole le plus répandu, testable sans SDK propriétaire. |
| Depuis la loi n° 2023-… (AGEC), le ticket de caisse papier n'est plus imprimé systématiquement pour les ventes de biens ; en restauration, la **note** reste obligatoire au-delà de 25 € TTC (arrêté du 8 juin 1967 / 1983). | D'où « impression auto ou manuelle selon le mode de vente ». **À confirmer par un juriste.** |

## 4. Ambiguïtés bloquantes

Seules ces questions empêchent d'aller au-delà du premier incrément ; tout le reste est couvert par les hypothèses ci-dessus.

1. **Où publier le code ?** Aucun dépôt n'est rattaché au projet. Il faut un dépôt GitHub (privé recommandé) pour versionner, faire tourner la CI qui produit l'APK et suivre les livraisons.
2. **Matériel de référence pour la recette.** Quelle(s) caisse(s) Android et imprimante(s) précises seront utilisées pour les premiers tests réels (marque/modèle, connectique) ? Sans cela, la couche matérielle reste testée uniquement sur simulateur ESC/POS.
3. **Voie de certification visée** (NF525 via Infocert, ou LNE) et calendrier. Les deux référentiels se recoupent, mais l'organisme fixe les exigences de détail (format des archives, JET, cumul de grand total, signature). Le code est écrit pour être « certifiable », pas certifié.
4. **Prestataire TPE** (ex. Nepting, Worldline, Ingenico via SDK fabricant, SumUp…). Conditionne le paiement carte intégré ; d'ici là la carte est saisie comme « carte (TPE autonome) ».

Les questions 2 à 4 n'empêchent pas la phase 1 ; la question 1 conditionne la publication et le build APK automatisé.

## G — État de cette étape

- **Fonctionne :** rien d'exécutable dans ce document ; c'est le cadrage.
- **Simulé :** n/a.
- **À faire :** faire valider les hypothèses fiscales (méthode TVA, notes, conservation 6 ans) par un expert-comptable / juriste.
- **Risques :** évolution du cadre légal (la loi de finances 2025 a supprimé l'attestation individuelle de l'éditeur : seule une certification par organisme accrédité est désormais admise — à vérifier lors de la commercialisation).
