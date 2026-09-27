# Riftbound Scanner

PWA de scan de cartes Riftbound pour Android, avec export CSV.

## Développement

    npm test              # logique pure, sans navigateur
    npm run dev           # http://localhost:8080
    npm run catalog:update  # rafraîchit data/cards.json depuis l'API Riftcodex

## Tester sur le téléphone

Chrome exige HTTPS pour la caméra. En développement, brancher le téléphone en USB,
ouvrir `chrome://inspect` sur le PC, activer « Port forwarding » du port 8080, puis
ouvrir `http://localhost:8080` **sur le téléphone** : `localhost` est un contexte
sécurisé, la caméra est autorisée.

En production, le site est servi en HTTPS par GitHub Pages.
