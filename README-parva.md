QZ Tray — fork Parvasoft
========================

Mirror di [qzind/tray](https://github.com/qzind/tray) (LGPL-2.1) con le nostre
modifiche. Il `README.md` è quello di upstream: non modificarlo, o ogni merge
genera un conflitto. Tutto quello che riguarda il nostro fork va qui.

## Struttura

| Remote | Punta a | Uso |
|---|---|---|
| `origin` | `ParvaSoft/parva-tray` | fetch e push |
| `upstream` | `qzind/tray` | solo fetch (push disabilitato) |

| Branch | Uso |
|---|---|
| `master` | copia fedele di upstream. **Non committare mai qui.** |
| `parva/dev` | il nostro sviluppo |

Se cloni il repo da zero, l'`upstream` va riaggiunto a mano:

```bash
git remote add upstream https://github.com/qzind/tray
git remote set-url --push upstream DISABLED
```

## Aggiornare da upstream

```bash
# 1. scarica le novità di QZ
git fetch upstream

# 2. porta master al pari di upstream (fast-forward: se fallisce, qualcuno
#    ha committato su master ed è da sistemare)
git checkout master
git merge --ff-only upstream/master
git push origin master

# 3. porta le novità nel nostro branch
git checkout parva/dev
git merge master
```

Usiamo `merge`, non `rebase`: `parva/dev` è condiviso e il rebase riscriverebbe
la storia già clonata dagli altri.

I conflitti si concentrano dove tocchiamo file di upstream. Per tenerli bassi:
commit piccoli e a tema unico, e quando è possibile un file nuovo invece di una
modifica a un file esistente.

Per aggiornare a una release precisa invece che all'ultimo commit:

```bash
git fetch upstream --tags
git checkout parva/dev
git merge v2.2.4     # o il tag che serve
```

## Prerequisiti di build

* **JDK 25** — obbligatorio: `ant/project.properties` fissa `javac.source/target=25`
  e JavaFX 27-ea non gira sotto. Su macOS: `brew install --cask corretto@25`, poi
  `export JAVA_HOME=$(/usr/libexec/java_home -v 25)`.
* **Ant**
* **NSIS** (`brew install makensis`) — solo per generare l'installer Windows.

## Build

```bash
ant                 # compila e impacchetta il jar
ant nsis            # installer Windows (cross-compilabile da macOS e Linux)
ant dmg             # dmg macOS
ant pkgbuild        # pkg macOS
ant makeself        # installer Linux
```

Gli installer scaricano e impacchettano da soli la JRE della piattaforma di
destinazione, quindi non serve una macchina Windows per fare l'exe.
L'architettura di default è quella dell'host: per l'altra, `-Dtarget.arch=x86_64`
oppure `-Dtarget.arch=aarch64`.

La firma del codice usa `jsign` e resta inattiva senza certificato configurato:
i binari che escono da una build locale non sono firmati.

## Branding

Le icone stanno in **due** posti:

* `assets/branding/` — sorgenti SVG e derivati per il sistema operativo
  (`linux-icon.svg` è il master, `apple-icon.icns` e `windows-icon.ico` si
  rigenerano con `create_branding.sh`, che richiede `librsvg` e `imagemagick`).
* `src/qz/ui/resources/` — i PNG usati dentro l'applicazione: icona della barra
  di sistema (`qz-default*`, `qz-warning*`, `qz-danger*`, `qz-mask*`), voci di
  menu, dialog, logo e banner. **Questi lo script non li tocca**, vanno
  rigenerati a mano nelle misure 16/20/24/32/40/48.

Il brief per il reparto grafico è in `assets/branding/icone-qz-tray.html`
(non tracciato).

## Licenza

Upstream è LGPL-2.1. Il repo privato va bene; se distribuiamo a un cliente un
binario modificato, dobbiamo rendergli disponibile il sorgente delle modifiche.
