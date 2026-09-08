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
  e JavaFX 27-ea non gira sotto.
* **Ant**
* **NSIS** — solo per l'installer Windows.

L'installazione di questi tre cambia a seconda dell'host, vedi sotto.

## Build

| target | produce | dove si può fare |
|---|---|---|
| `ant` | solo il jar | ovunque |
| `ant nsis` | installer Windows (`.exe`) | ovunque, serve NSIS |
| `ant makeself` | installer Linux (`.run`) | macOS e Linux, serve `makeself` |
| `ant pkgbuild` | `.pkg` macOS | **solo macOS** |
| `ant dmg` | `.dmg` macOS | **solo macOS** |

I target Apple chiamano `pkgbuild`, `productbuild` e `codesign`, che esistono
solo su macOS; l'exe Windows invece è cross-compilabile perché `makensis` gira
anche altrove e gli installer si scaricano da soli la JRE della piattaforma di
destinazione.

**Passa sempre `-Dprovision.file=parva/provision.json`** ai target installer,
altrimenti esce un pacchetto senza la nostra CA e QZ mostra il popup a ogni
stampa (vedi "Certificati di firma QZ"). Il jar da solo non c'entra: la CA entra
a livello di installer.

L'architettura di default è quella dell'host. Per l'altra:
`-Dtarget.arch=x86_64` oppure `-Dtarget.arch=aarch64`; sono due pacchetti
distinti, non uno universale.

Output in `out/`. Non serve `ant clean` fra una build e l'altra: `distribute` fa
già `quick-clean` e il target `provision` ricrea `out/dist/provision` ogni volta
(`build.xml:366`).

### Su macOS

```bash
brew install --cask corretto@25
brew install ant makensis makeself   # makeself solo per il target Linux

export JAVA_HOME=$(/usr/libexec/java_home -v 25)

ant pkgbuild -Dprovision.file=parva/provision.json                    # pkg
ant dmg      -Dprovision.file=parva/provision.json                    # dmg
ant nsis     -Dprovision.file=parva/provision.json -Dtarget.arch=x86_64   # exe
```

Su un Mac ARM il default è `aarch64`: per l'exe Windows serve `x86_64`
esplicito, o esce un installer che gira solo sui Windows ARM.

Per **provare** l'installazione usa il `.pkg`, non il `.dmg`: solo il pkg esegue
gli script di post-install, che sono dove il provisioning installa la CA e
scrive `authcert.override`. Dopo l'installazione:

```bash
grep authcert.override ~/Library/Application\ Support/parva/parva-tray.properties
```

Senza l'identità di firma Apple in keychain, `get-identity`
(`ant/apple/installer.xml:352`) ripiega sulla firma ad-hoc: il pacchetto si
costruisce e si installa in locale, ma su altre macchine Gatekeeper lo blocca.

### Su Windows

Da PowerShell, con JDK 25 (Amazon Corretto), Ant e NSIS installati. NSIS va
lasciato nel percorso di default `C:\Program Files (x86)\NSIS`: il build lo
cerca lì (`ant/windows/installer.xml:166`).

```powershell
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk25"   # adatta alla versione installata

ant nsis -Dprovision.file=parva/provision.json
```

L'architettura di default è quella della macchina, quindi su un Windows x64 esce
già `x86_64`. I target `dmg` e `pkgbuild` da qui non si possono fare.

Il `.pkg`/`.dmg` per i Mac va costruito su un Mac; se non ce n'è uno a
disposizione, l'unica via è una CI con runner macOS.

### Firma dei binari

La firma del codice usa `jsign` e resta inattiva senza certificato configurato:
i binari che escono da una build locale non sono firmati. È una cosa diversa dai
certificati di firma QZ descritti più sotto — quelli servono a togliere il popup
di stampa, questa a non far protestare Windows e macOS all'installazione.

## Branding: icone

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

## Branding: nomi e attribuzione

Il fork si presenta come "Parva Tray". Le stringhe stanno in due soli file:

* `ant/project.properties` — `vendor.*` e `project.*`. Tutti i template degli
  installer (nsi, plist, pkg, dmg, makeself) leggono queste property, quindi
  negli installer non c'e' nessuna stringa da cambiare a mano.
* `src/qz/common/Constants.java` — il blocco `ABOUT_*`, piu' `PROPS_FILE` e
  `DATA_DIR`. Da `ABOUT_URL` e `ABOUT_COMPANY` derivano a cascata il bundle id
  Apple, gli URL di licensing/support/privacy/download e il testo del popup
  (`Verified by Parvasoft`).

**Due valori vanno tenuti identici fra i due file, o l'app si rompe in modi non
ovvi:**

| ant | java | valore | cosa succede se divergono |
|---|---|---|---|
| `project.filename` | `Constants.PROPS_FILE` | `parva-tray` | e' anche il nome del jar che l'app cerca a runtime (`SystemUtilities.java:726`): se non combaciano, non si trova da sola |
| `project.datadir` | `Constants.DATA_DIR` | `parva` | cartella dati e protocollo URL registrato (`parva://`, `WindowsInstaller.java:126`); `vendor.name` deve seguire, e' lo schema URL su macOS |

Verifica veloce dopo ogni merge da upstream:

```bash
ant && java -jar out/dist/parva-tray.jar --bundleid    # eu.parvasoft.parva-tray
```

**Cosa non si tocca.** Le intestazioni di copyright "QZ Industries, LLC" nei
sorgenti restano: la LGPL permette di rinominare il prodotto ma impone di
conservare copyright e licenza. Restano anche `CRL_URL` (`crl.qz.io`) e
`localhost.qz.io` in `CertificateChainBuilder`, che sono funzionali e non
attribuzione, e l'enum `Remover.QZ`, che serve a disinstallare un QZ Tray
originale eventualmente gia' presente sulla macchina.

Cambiando `ABOUT_EMAIL` la costante upstream `IS_REBRANDED` (`Constants.java:53`)
diventa `true` da sola: l'About passa in modalita' ridotta e sparisce il
controllo aggiornamenti verso le release di `qzind/tray`, che per un fork e'
quello che vogliamo.

## Certificati di firma QZ

Senza firma, QZ mostra un popup di conferma a ogni stampa. Per non vederlo mai
servono tre cose: una CA nostra, un certificato per ambiente firmato da quella,
e un endpoint sul backend che firma le richieste.

Non c'entra nulla con i certificati HTTPS dei siti: QZ verifica l'impronta del
certificato, non il dominio. Lo stesso certificato vale per pre, prod, localhost
e qualunque URL futuro; il CN è solo l'etichetta che comparirebbe nel popup, per
cui va bene `Parvasoft`, non un hostname.

### 1. Generare CA e certificati

```bash
parva/make-signing-certs.sh          # CA + certificato "prod"
parva/make-signing-certs.sh pre      # altro ambiente, riusa la stessa CA
```

Serve `openssl`. Lo script verifica catena e firma prima di uscire.

La passphrase protegge solo `ca-key.pem` e viene chiesta a video in due momenti:
quando si crea la CA (una volta sola) e a ogni emissione di un certificato per un
nuovo ambiente. È sempre la stessa, anche se openssl la chiama `PEM pass phrase`
quando crea la chiave e `pass phrase for ca-key.pem` quando la riusa per firmare.
**Non è salvata da nessuna parte**: persa la passphrase non si possono più emettere
certificati, e l'unica via è rigenerare la CA e reinstallare il client su tutte le
macchine. Va nel password manager insieme al backup della chiave. La chiave
d'ambiente (`<env>-key.pem`) è invece senza passphrase, perché il backend deve
caricarla all'avvio da sola.

Esiste `QZ_CA_PASS` per usi non interattivi, ma serve solo su una macchina fidata
che abbia già `ca-key.pem`: in una CI normale quella chiave non ci deve stare. La
passphrase finisce comunque nella command line di openssl, visibile in `ps` sulla
stessa macchina.

**La CA si genera una volta sola.** Rigenerarla invalida tutte le installazioni
già in campo: lo script se la trova la riusa, non cancellare `parva/certs/ca.crt`.
Fanne un backup offline (la chiave della CA emette certificati validi per tutto
il parco macchine) e non metterla su nessun server.

Output in `parva/certs/`, escluso da git tramite `parva/.gitignore`:

| file | dove va | segreto |
|---|---|---|
| `ca.crt` | dentro l'installer, via `parva/provision.json` | no |
| `<env>.crt` | servito dal backend su `/qz/cert` | no |
| `<env>-key.pem` | solo sul backend di quell'ambiente, fuori dal war | **sì** |
| `ca-key.pem` | offline, serve solo a emettere altri certificati | **sì** |

### 2. Mettere la CA dentro l'installer

`parva/provision.json` contiene già lo step che serve:

```json
[
  {
    "description": "CA Parvasoft: ...",
    "type": "ca",
    "data": "certs/ca.crt"
  }
]
```

Il tipo `ca` copia il certificato nell'installazione e imposta la preferenza
`authcert.override` al suo percorso (`CaInvoker.java`). I percorsi in `data`
sono relativi al `provision.json`, e la fase è forzata a `certgen`.

Si attiva passando il file al build:

```bash
ant nsis -Dprovision.file=parva/provision.json
```

Vale per tutti i target (`nsis`, `dmg`, `pkgbuild`, `makeself`). Per non doverlo
ripetere si può decommentare `provision.file` in `ant/project.properties`, ma
allora è una modifica a un file di upstream che va in conflitto ai merge.

**Non serve la cartella `whitelist/`.** Quando un certificato risale a una CA di
terze parti, QZ lo aggiunge da solo alla lista consentiti alla prima richiesta
valida (`Certificate.java:248-261`), quindi il popup non compare mai. La
`whitelist/` serve solo per pre-approvare un certificato self-signed, senza CA.

### 3. Backend: i due endpoint

`/qz/cert` restituisce il certificato pubblico, `/qz/sign` firma la stringa
ricevuta. JAX-RS, funziona sia su TomEE che su Quarkus:

```java
@Path("/qz")
public class QzSigning {

    private static final PrivateKey KEY = loadKey(System.getenv("QZ_KEY_PATH"));

    @GET @Path("/cert") @Produces(MediaType.TEXT_PLAIN)
    public String cert() throws IOException {
        return Files.readString(Path.of(System.getenv("QZ_CERT_PATH")));
    }

    @GET @Path("/sign") @Produces(MediaType.TEXT_PLAIN)
    public String sign(@QueryParam("request") String toSign) throws GeneralSecurityException {
        Signature sig = Signature.getInstance("SHA512withRSA");
        sig.initSign(KEY);
        sig.update(toSign.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    private static PrivateKey loadKey(String path) {
        try {
            String pem = Files.readString(Path.of(path))
                    .replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        } catch(Exception e) {
            throw new IllegalStateException("Chiave di firma QZ non caricabile: " + path, e);
        }
    }
}
```

La chiave è già in PKCS#8, il formato che si aspetta `KeyFactory`.

`QZ_KEY_PATH` e `QZ_CERT_PATH` puntano a file fuori dal war: dentro
l'artefatto la chiave finirebbe nei backup, nei registry e nei cloni del repo.

**`/qz/sign` firma qualunque stringa gli si passi.** Lasciato aperto equivale a
pubblicare la chiave privata: chiunque si fa firmare le proprie richieste e
stampa sulle macchine dei clienti. Va dietro l'autenticazione dell'applicazione,
esattamente come il resto delle API.

### 4. Frontend

```js
qz.security.setCertificatePromise((resolve, reject) =>
    fetch('/qz/cert').then(r => r.text()).then(resolve).catch(reject));

qz.security.setSignatureAlgorithm('SHA512');   // senza questo QZ assume SHA1

qz.security.setSignaturePromise(toSign => (resolve, reject) =>
    fetch('/qz/sign?request=' + encodeURIComponent(toSign))
        .then(r => r.text()).then(resolve).catch(reject));
```

L'algoritmo va dichiarato su entrambi i lati: il default lato QZ è SHA1
(`PrintSocketClient.java:221`).

### 5. Se il popup esce lo stesso

QZ fa quattro controlli in fila; il testo del popup dice quale è fallito:

| popup | causa |
|---|---|
| `Expired Signature` | orologio del PC fuori sincrono: la firma vale 15 minuti (`Constants.VALID_SIGNING_PERIOD`) |
| `Invalid Signature` | firma non corrispondente: algoritmo diverso fra JS e backend, o stringa alterata prima di firmare |
| `Untrusted website` | manca la CA nell'installazione: `provision.file` non passato al build |
| `Verified by Parvasoft` con Allow/Remember | catena ok ma impronta non ancora salvata: succede solo se la lista consentiti non è scrivibile |

I log sono in `%APPDATA%\parva\debug.log` su Windows e
`~/Library/Application Support/parva/debug.log` su macOS (`App.java:130`).

### 6. Aggiungere un ambiente o rinnovare

Nuovo ambiente: `parva/make-signing-certs.sh <nome>`, la CA resta la stessa e le
installazioni esistenti si fidano subito del nuovo certificato — nessun
reinstall. Per isolare gli ambienti basta tenere la chiave privata di ciascuno
solo sul backend corrispondente.

I certificati durano 10 anni, la CA 20. Alla scadenza di un certificato si
rigenera solo quello; la CA si tocca solo se scade lei, e in quel caso va
rifatto l'installer.

## Licenza

Upstream è LGPL-2.1. Il repo privato va bene; se distribuiamo a un cliente un
binario modificato, dobbiamo rendergli disponibile il sorgente delle modifiche.
