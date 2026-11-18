# D. Parcours UX et écrans

Principes : cibles tactiles ≥ 56 dp, contraste élevé (cuisines lumineuses), action principale toujours en bas à droite, jamais plus de 2 taps pour l'action la plus fréquente (ajouter un produit), confirmations uniquement pour les actions irréversibles, textes sans jargon technique. Orientation paysage pour caisse/tablette, portrait pour pad serveur (même code, mises en page adaptatives `WindowSizeClass`).

## 1. Parcours caisse

### P1 — Connexion
1. Écran d'accueil : logo établissement, pavé PIN (ou « Passez votre badge »).
2. Identification → **Plan de salle** (jamais la note du précédent utilisateur).
3. Inactivité > seuil configuré → note en cours mise en attente (selon le mode de vente) → retour accueil.

### P2 — Service à table
1. Plan de salle : tables colorées (libre / occupée / en attente de réclamation / à encaisser), badge du serveur propriétaire.
2. Tap table libre → saisie couverts (facultatif) → **Écran commande**.
3. Tap produits ; le produit prend son niveau par défaut ; appui long → choisir N1/N2/N3 ou « direct ».
4. « Envoyer commande » → bons cuisine routés ; lignes envoyées grisées avec ✓.
5. Plus tard : bouton dynamique **« Réclamer N1 »** → ticket de réclamation → note en attente → retour plan de salle. Le bouton devient « Réclamer N2 ».
6. Ajout après réclamation N2 → ticket **« AJOUT N2 »** avec seulement les nouveaux articles.

### P3 — Annulation d'une ligne envoyée
Appui long ligne → « Annuler » → quantité → **motif obligatoire** (liste + libre) → PIN responsable si la politique l'exige → ticket d'annulation inversé en cuisine (sans motif) ; ligne barrée dans la note, conservée dans l'historique.

### P4 — Encaissement
1. « Encaisser » → écran paiement : total, reste dû, tous les moyens visibles, pavé, raccourcis 5/10/20/50 €, « Montant exact ».
2. Espèces > reste → rendu monnaie affiché en grand.
3. Paiement mixte : chaque paiement s'ajoute à la liste ; le reste décroît.
4. « Diviser le reste » (parts égales / montant libre / articles) ou « Séparer la note » (choix des articles → nouvelle note indépendante).
5. Solde nul → ticket final (auto ou proposé selon mode) → retour plan de salle.

### P5 — Remise
Sur ligne : appui long → Remise → % ou €. Sur note : bouton « Remise note » → **% uniquement** (le choix € n'existe pas). Au-delà du seuil du profil → PIN responsable. Le détail par ligne s'affiche (prix barré → nouveau prix).

### P6 — Changement / transfert de table
Plan de salle → appui long table → « Déplacer vers… » / « Fusionner avec… » / « Transférer à un serveur » ; transfert en masse : menu responsable → serveur source → cases à cocher / tout sélectionner → serveur cible. Petit ticket « TABLE 43 → 56 » aux seules imprimantes ayant reçu des bons.

### P7 — Fonds et clôture
Menu → « Ma caisse » : fond actif, théorique (si mode visible), mouvements. « Clôturer mon fond » → comptage (total ou calculette coupures) → déclaration enregistrée → responsable confirme (montant, calculette, ou « sans recomptage détaillé »). Tables encore ouvertes → proposition de transfert, non imposée.

### P8 — Remboursement
Historique ventes → recherche (n°, date, montant, QR du ticket) → « Rembourser » → articles/quantités ou montant → motif → moyen de remboursement (original par défaut) → document de remboursement lié ; avoir éventuel imprimé avec QR.

## 2. Maquettes (fil de fer)

### Écran commande (paysage)
```
┌──────────────────────────────────────────────────────────────────────────┐
│ Table 43 · 4 couv. · Marie (propriétaire)        Sur place      12:41  ⚙ │
├───────────────┬──────────────────────────────────────┬───────────────────┤
│ FAMILLES      │  PRODUITS                            │ NOTE              │
│ ▸ Entrées     │ ┌────────┐┌────────┐┌────────┐       │ DIRECT            │
│ ▸ Pizzas      │ │Margher.││ Reine  ││ 4 From.│       │  1 Bière   6,00 ✓ │
│ ▸ Boissons    │ │ 12,00  ││ 13,50  ││ 14,00  │       │ N1 ───────────────│
│ ▸ Desserts    │ └────────┘└────────┘└────────┘       │  2 Salade 18,00 ✓ │
│               │ ┌────────┐┌────────┐┌────────┐       │ N2 ── À suivre ───│
│               │ │Calzone ││ ...    ││        │       │  2 Pizza  24,00   │
│               │ └────────┘└────────┘└────────┘       │                   │
│               │                                      │ Total     48,00 € │
├───────────────┴──────────────────────────────────────┴───────────────────┤
│ [Plan]  [Remise note %]  [Imprimer la note]  [Envoyer commande] [Réclamer N1] [ENCAISSER] │
└──────────────────────────────────────────────────────────────────────────┘
```

### Écran paiement
```
┌──────────────────────────────────────────────────────────────┐
│ Table 43            Total 48,00 €     Reste à payer 28,00 €  │
├────────────────────────┬─────────────────────────────────────┤
│ [Espèces] [Carte]      │   7  8  9     [5 €]  [10 €]         │
│ [Ticket resto] [Avoir] │   4  5  6     [20 €] [50 €]         │
│                        │   1  2  3     [Montant exact]       │
│ Paiements :            │   0  ,  ⌫                           │
│  Carte   20,00 €  Marie│                                     │
│                        │  Rendu : 2,00 €                     │
├────────────────────────┴─────────────────────────────────────┤
│ [Diviser le reste]   [Séparer la note]   [Valider paiement]  │
└──────────────────────────────────────────────────────────────┘
```

### Plan de salle
```
┌──────────────────────────────────────────────────────────┐
│ [Salle] [Terrasse] [Bar]                  Marie ▾  12:41 │
│   ┌──┐   ┌──┐   ┌────┐    ┌──┐                           │
│   │12│   │14│   │ 43 │●M  │56│      ● occupée            │
│   └──┘   └──┘   └────┘    └──┘      ◐ N2 à réclamer      │
│   ┌──┐   ┌──┐                       ○ libre              │
│   │20│◐P │21│                                            │
│   └──┘   └──┘                                            │
├──────────────────────────────────────────────────────────┤
│ [Emporter] [Livraison] [Notes en attente] [Ma caisse] [☰]│
└──────────────────────────────────────────────────────────┘
```

## 3. Écrans back-office (web responsive)

| Écran | Contenu |
|-------|---------|
| Tableau de bord | CA encaissé (TTC/HT), commandes ouvertes **séparées** du CA, moyens de paiement, remises, remboursements, écarts de caisse ; horodatage de dernière synchro par terminal ; comparaison de périodes. |
| Ventes | Recherche de tickets, détail, duplicata PDF, chaîne de vérification (« Chaîne intègre ✓ »). |
| Catalogue | Familles, produits, prix, TVA par mode, disponibilité, routage, niveaux par défaut ; publication versionnée ; historique avant/après. |
| Promotions | Assistant de création, calendrier, simulation sur un panier d'exemple. |
| Équipe et droits | Employés, moyens de connexion, rôles, matrice action × politique. |
| Matériel | Terminaux appairés, imprimantes, chaînes de secours, tiroirs. |
| Caisse et clôtures | Z par terminal et établissement, déclarations de fonds (théorique / déclaré / reçu), exports comptables, archives signées téléchargeables. |
| Audit | Journal filtrable (qui, quoi, quand, avant/après, motif). |

## G — État de cette étape

- **Fonctionne (incrément 1) :** écran commande minimal (produits de démonstration, lignes, niveaux, envoi cuisine, remise note %, encaissement espèces/carte simplifié), écran journal avec vérification de chaîne.
- **Simulé :** catalogue codé en dur ; « impression » cuisine affichée à l'écran (aperçu texte du bon) au lieu d'une imprimante.
- **À faire :** plan de salle, PIN, paiement complet, divisions, fonds, back-office.
- **Risques :** ergonomie à valider sur matériel réel avec des serveurs en service (tests utilisateurs indispensables avant la phase pilote).
