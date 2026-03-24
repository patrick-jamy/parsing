# Gear Parser (Spring Boot)

Projet Spring Boot qui récupère une page HTML SWGOH puis extrait dynamiquement les items par Gear pour produire:

- nombre d'items par Gear,
- nombre d'items bleus,
- nombre d'items orange,
- visualisation graphique (Chart.js) + tableau.

## Lancer

```bash
mvn spring-boot:run
```

Puis ouvrir: `http://localhost:8080`.

## API

- `GET /api/stats` : retourne les données en cache (charge si nécessaire)
- `POST /api/stats/reload` : force un nouveau parsing (bouton **Reload** sur l'UI)

## Stratégie de parsing

Le moteur tente d'abord un parsing par sections Gear (titres + voisins DOM), puis bascule sur un parsing de lignes (tables/listes) si la structure n'est pas détectée. La détection de couleur est heuristique (classes CSS, style inline, alt/image, texte métier).

## Limite connue

Le site cible peut activer un anti-bot (HTTP 403). Dans ce cas, l'application bascule automatiquement sur un HTML local d'exemple (`sample-gear-page.html`) pour garder l'UI fonctionnelle.
