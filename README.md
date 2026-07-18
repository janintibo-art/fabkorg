# fabkorg 🎛️🩷

Enregistreur MIDI natif Android pour boîtes à rythmes et grooveboxes
(Korg Electribe ER-1, Volca, Roland…), en **Kotlin + Jetpack Compose**.
Nom affiché de l'app : « Fab La Grosse Basse ».

## Spécial Electribe·R (ER-1)
- **Mode ER-1** (bouton en haut de la liste) : l'ER-1 envoie tous ses sons
  sur UN seul canal MIDI et les distingue par numéro de note. Ce mode crée
  automatiquement **une piste par son** (Kick, Snare, HH fermé, Clap…),
  avec nom deviné, Solo/Mute et timeline par son.
- **Thème rose ER-1** : bouton rond « R » dans l'en-tête, assorti à la machine.

## Connectivité
- Ouverture de **tous les ports de sortie**, reconnexion automatique
- **USB-MIDI** et **Bluetooth-MIDI**
- **LED « signal »** verte dès qu'un octet MIDI arrive

## Musique
- Mode classique : 16 pistes (1 canal = 1 piste)
- Solo / Mute, compte à rebours 4 temps, métronome visuel
- Tap tempo, Overdub
- Lecture vers la machine + export **.mid** (SMF format 1)

## Compilation
Workflow GitHub Actions inclus : chaque `git push` sur `main` fabrique un APK
(**Actions → dernier run → Artifacts**).
