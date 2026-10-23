# BoxTwitch — mod Fabric pour Minecraft Java 1.21.11

Défi Twitch : une immense box en bedrock flotte dans le ciel, le chat la remplit (`!bloc`, `!pluie`…) ou la sabote (`!tnt`, `!chaos`).

## ⚠️ À lire d'abord (honnêteté)
Ce projet a été écrit **sans pouvoir être compilé ni lancé** par l'assistant (pas d'accès réseau ni de JDK complet dans son environnement).
Il n'y a donc **pas de .jar fourni** : tu le compiles toi-même (commandes plus bas, 2 minutes).
Minecraft 1.21.9+ a changé plusieurs API ; si la compilation échoue, ce sont les points probables :
- `KeyBinding.Category.create(...)` (BoxTwitchClient) ;
- `server.getSinglePlayerName()` (BoxManager.isAdminPlayer) ;
- `SoundEvents.ENTITY_GENERIC_EXPLODE.value()` (retire `.value()` si l'erreur le demande) ;
- `player.getEntityWorld()` (sinon `player.getWorld()`), `isChunkLoaded(BlockPos)`.
Colle-moi le message d'erreur exact et je corrige.

## Versions visées (trouvées par recherche web, à revérifier sur fabricmc.net/develop)
Minecraft 1.21.11 · Fabric Loader 0.18.4 · Fabric API 0.141.3+1.21.11 · Yarn 1.21.11+build.4 · Loom 1.15 (plugin `fabric-loom-remap`) · Java 21 · Gradle 9.2+.

## Architecture (la plus simple)
- **Un seul mod** côté serveur/solo : il modifie le monde sur le thread serveur ; en solo, le serveur intégré suffit.
- **Solo ou LAN** : installe le mod sur ton client. **Serveur Fabric dédié** : installe-le sur le serveur ; le client a besoin du mod uniquement pour le menu (touche/GUI), les événements marchent sans.
- **Twitch** : lecture du chat par IRC anonyme (`justinfan`), **sans OAuth ni mot de passe**. Conséquence : le mod ne peut pas écrire dans le chat Twitch ; `!progression` et `!top` s'affichent dans Minecraft et sur l'overlay.
- **Overlay** : mini serveur HTTP intégré, **127.0.0.1 uniquement**, jeton obligatoire, lecture seule.

## Compiler
```bash
# Prérequis : JDK 21 + Gradle 9.2+ installés
cd BoxTwitch
gradle wrapper --gradle-version 9.2.1   # génère gradlew (une seule fois)
./gradlew build                          # Windows : gradlew.bat build
```
Résultat : `build/libs/BoxTwitch-fabric-1.21.11.jar` (ignore le `-sources.jar`).

## Installer
1. Installe Fabric Loader pour 1.21.11 : https://fabricmc.net/use/installer/
2. Télécharge Fabric API 1.21.11 (Modrinth/CurseForge) → dossier `.minecraft/mods`.
3. Copie `BoxTwitch-fabric-1.21.11.jar` dans `.minecraft/mods`. Lance le profil Fabric.

## Premier lancement (mode test, sans Twitch)
1. Crée un monde, place-toi à l'endroit du coin bas de la box.
2. `/box size 40 40 40` puis `/box build` (ou `/box pos1`, déplace-toi, `/box pos2`, `/box build`).
3. `/box start`, puis `/box test bloc`, `/box test tnt`, `/box test pluie Alice` : tout fonctionne sans Twitch.
4. Touche **B** (configurable dans Contrôles) ou `/box` : ouvre le menu.

## Connecter Twitch
Menu → page **Twitch** → saisis ton salon → *Appliquer* (ou `/box channel tonpseudo`). Statut « connecté à #salon » attendu.

## Overlay Streamlabs Desktop
1. `/box overlay` (ou bouton *Copier l'URL overlay*) donne l'URL `http://127.0.0.1:8765/overlay?token=…`.
2. Streamlabs → **+** → *Source navigateur* → colle l'URL, **largeur 1920, hauteur 1080**, décoche « Contrôler l'audio ».
3. Minecraft doit tourner sur le même PC. Ne partage jamais l'URL (le jeton protège l'accès).

## Commandes admin (propriétaire du monde en solo, console, ou `/box admin add <pseudo>`)
`/box` menu · `pos1` `pos2` `size x y z` `build` · `start` `pause` `resume` `stop` · `reset confirm` · `clear confirm` (vide l'intérieur) · `toggle <all|tnt|aide|bloc|remplir|pluie|chance|chaos>` · `adj <réglage> <±>` · `block next|prev|set <id>` · `channel` · `twitch reconnect|on|off|status` · `test <cmd> [viewer]` · `admin add|remove|list`.
Réglages avancés (délais par commande, liste de blocs, port) : `config/boxtwitch.json`.

## Règles du jeu
- Le **volume** = intérieur de la box (coque exclue). La progression est recomptée en continu (colonne par colonne, chunks chargés uniquement) : elle baisse donc bien quand une TNT détruit des blocs. **Reste près de la box** ou charge-la, sinon les chunks non chargés ne sont pas recomptés.
- Les TNT sont **simulées** : seules les positions intérieures sont modifiées, la coque (fond, murs, rebord) n'est jamais touchée et est protégée contre le minage, même en créatif (relance `/box build` pour la reconstruire).
- Les viewers ne passent que par une liste blanche de 9 mots-clés ; aucune commande Minecraft n'est exécutable depuis le chat.
- Recharge globale par commande + recharge par viewer + plafond par viewer (menu / config).

## Dépannage
- *Twitch « erreur réseau »* : pare-feu/antivirus bloquant `irc.chat.twitch.tv:6697`.
- *Rien ne se passe* : le défi doit être en **RUNNING** (`/box start`), interactions ON, événement activé, box valide.
- *Overlay « déconnecté »* : Minecraft fermé, mauvais jeton, ou port 8765 pris (change `overlayPort`).
- *Menu inaccessible* : `/box admin add TonPseudo` depuis la console ; en solo seul le propriétaire du monde est admin.
- *Pourcentage figé* : chunks non chargés → rapproche-toi.
