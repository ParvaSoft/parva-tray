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

I percorsi dentro `provision.json` sono relativi **al file stesso**, non alla
directory da cui lanci ant (`ProvisionBuilder.java:151`). E siccome `parva/certs/`
è escluso da git, su una macchina di build appena clonata quella cartella non
esiste: `ca.crt` va copiato a mano, e dev'essere lo **stesso** usato per gli altri
ambienti. Non rilanciare lì `make-signing-certs.sh`, creerebbe una CA diversa.

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

ant nsis "-Dprovision.file=parva/provision.json"
```

**Le virgolette attorno al `-D` non sono facoltative.** Su Windows `ant` è
`ant.bat`, e il passaggio degli argomenti attraverso cmd spezza il token
sull'uguale: Ant riceve il percorso come argomento a sé e lo interpreta come nome
di un target, fallendo con

```
Target "parva/provision.json" does not exist in the project "qz".
```

Il messaggio trae in inganno due volte: non parla del certificato, e quel `qz` è
solo il nome del progetto Ant (`build.xml:3`), rimasto di upstream. Lo stesso
errore esce scrivendo `-Dprovision.file parva/provision.json` con lo spazio al
posto dell'uguale, o se il percorso contiene spazi.

Dopo che il build parte, la conferma che il flag sia arrivato è questa riga nel
log:

```
Found provision file 'parva/provision.json' calling 'provision --json [...]'
```

Se non compare, il pacchetto esce senza CA anche se il build riesce.

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
  negli installer non c'è nessuna stringa da cambiare a mano.
* `src/qz/common/Constants.java` — il blocco `ABOUT_*`, più `PROPS_FILE` e
  `DATA_DIR`. Da `ABOUT_URL` e `ABOUT_COMPANY` derivano a cascata il bundle id
  Apple, gli URL di licensing/support/privacy/download e il testo del popup
  (`Verified by Parvasoft`).

**Due valori vanno tenuti identici fra i due file, o l'app si rompe in modi non
ovvi:**

| ant | java | valore | cosa succede se divergono |
|---|---|---|---|
| `project.filename` | `Constants.PROPS_FILE` | `parva-tray` | è anche il nome del jar che l'app cerca a runtime (`SystemUtilities.java:726`): se non combaciano, non si trova da sola |
| `project.datadir` | `Constants.DATA_DIR` | `parva` | cartella dati e protocollo URL registrato (`parva://`, `WindowsInstaller.java:126`); `vendor.name` deve seguire, è lo schema URL su macOS |

Verifica veloce dopo ogni merge da upstream:

```bash
ant && java -jar out/dist/parva-tray.jar --bundleid    # eu.parvasoft.parva-tray
```

**Cosa non si tocca.** Le intestazioni di copyright "QZ Industries, LLC" nei
sorgenti restano: la LGPL permette di rinominare il prodotto ma impone di
conservare copyright e licenza. Restano anche `CRL_URL` (`crl.qz.io`) e
`localhost.qz.io` in `CertificateChainBuilder`, che sono funzionali e non
attribuzione, e l'enum `Remover.QZ`, che serve a disinstallare un QZ Tray
originale eventualmente già presente sulla macchina.

Cambiando `ABOUT_EMAIL` la costante upstream `IS_REBRANDED` (`Constants.java:53`)
diventa `true` da sola: l'About passa in modalita' ridotta e sparisce il
controllo aggiornamenti verso le release di `qzind/tray`, che per un fork è
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

### 3. Backend: gli endpoint che firmano

Il backend che firma è 7FTCustoms. Servono due endpoint: `api/qz/cert`
restituisce il certificato pubblico dell'ambiente, `api/qz/sign` firma la stringa
che QZ gli passa. Vanno accanto agli altri controller in
`SevenFreightools/SevenFreightools.Web/API/`.

Il codice qui sotto è una proposta: **non è stato applicato a 7FTCustoms**, va
portato là con un ticket suo.

#### Risorse da aggiungere al progetto

Lo script `parva/make-signing-certs.sh` produce `<env>.crt` e `<env>-key.pem`.
Per un backend .NET conviene però **un solo file `.pfx`** che li contiene
entrambi, per due motivi concreti:

* il progetto è su **.NET Framework 4.8**, dove `RSA.ImportPkcs8PrivateKey` non
  esiste: quello arriva con .NET Core 3.0. Leggere una chiave PEM richiederebbe
  quindi una libreria, mentre un PKCS#12 lo apre `X509Certificate2` della BCL
  senza aggiungere niente;
* con un file solo, certificato e chiave non possono disallinearsi. Con due file
  è una svista che si scopre in produzione, quando la firma non verifica.

Il `.pfx` si ricava da quello che già generiamo:

```bash
openssl pkcs12 -export \
    -inkey parva/certs/prod-key.pem -in parva/certs/prod.crt \
    -name "Parvasoft QZ signing" \
    -out parva/certs/prod.pfx -passout pass:
```

| file | dove va | in git |
|---|---|---|
| `prod.pfx` | sul server dell'ambiente, **fuori dalla cartella di deploy** | **mai** |
| `ca.crt` | dentro l'installer di Parva Tray, non serve al backend | no (già ignorato) |

Percorso consigliato `C:\ProgramData\Parvasoft\qz\prod.pfx`, con ACL di
lettura alla sola identità dell'application pool. Fuori dal deploy perché dentro
finirebbe nei pacchetti di rilascio, nei backup e nelle copie dell'artefatto.

Il percorso si configura come tutto il resto dell'applicazione, con un
`appSettings` letto da `ConfigurationManager`:

```xml
<add key="QzSigningCertPath" value="C:\ProgramData\Parvasoft\qz\prod.pfx" />
```

**Sulla password del `.pfx`.** Il comando qui sopra usa `-passout pass:`, cioè
nessuna password: il file è protetto dalle ACL, esattamente come lo sarebbe
`prod-key.pem`, che infatti generiamo con `-nodes`. Se una policy interna
richiede la password, va letta da una **variabile d'ambiente**, non da
`Web.config`: quel file è tracciato in git, e aggiungerci la password della
chiave di firma significherebbe pubblicarla a tutti quelli che clonano.

#### Il controller

```csharp
using System;
using System.Configuration;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Web.Http;
using System.Web.Http.Description;

namespace SevenFreightools.API
{
    /// <summary>
    /// Firma le richieste di stampa inviate a Parva Tray. Senza una firma valida
    /// QZ mostra un popup di conferma a ogni stampa.
    /// </summary>
    [RoutePrefix("api/qz")]
    [Authorize]
    [ApiExplorerSettings(IgnoreApi = true)]
    public class QzController : ApiController
    {
        // Caricato una volta sola: su Windows ogni apertura di un PKCS#12 con
        // MachineKeySet scrive un key container su disco, quindi ricaricarlo a
        // ogni stampa li accumulerebbe in MachineKeys senza mai ripulirli.
        private static readonly Lazy<X509Certificate2> Signer =
            new Lazy<X509Certificate2>(LoadCertificate);

        [HttpGet, Route("cert")]
        public HttpResponseMessage Cert()
        {
            var pem = new StringBuilder()
                .AppendLine("-----BEGIN CERTIFICATE-----")
                .AppendLine(Convert.ToBase64String(Signer.Value.RawData,
                                                   Base64FormattingOptions.InsertLineBreaks))
                .AppendLine("-----END CERTIFICATE-----")
                .ToString();

            return Text(pem);
        }

        [HttpGet, Route("sign")]
        public HttpResponseMessage Sign(string request)
        {
            if (string.IsNullOrEmpty(request))
                return Request.CreateErrorResponse(HttpStatusCode.BadRequest, "Nothing to sign.");

            // SHA512 deve corrispondere a setSignatureAlgorithm lato client:
            // il default di QZ è SHA1 e la verifica fallirebbe senza dire perché.
            using (var rsa = Signer.Value.GetRSAPrivateKey())
            {
                var signature = rsa.SignData(Encoding.UTF8.GetBytes(request),
                                             HashAlgorithmName.SHA512,
                                             RSASignaturePadding.Pkcs1);

                return Text(Convert.ToBase64String(signature));
            }
        }

        /// <summary>
        /// QZ si aspetta il corpo così com'è. Restituendo una string, Web API la
        /// serializzerebbe in JSON, virgolette comprese, e la firma non verificherebbe.
        /// </summary>
        private HttpResponseMessage Text(string body)
        {
            var response = Request.CreateResponse(HttpStatusCode.OK);
            response.Content = new StringContent(body, Encoding.UTF8, "text/plain");
            return response;
        }

        private static X509Certificate2 LoadCertificate()
        {
            var path = ConfigurationManager.AppSettings["QzSigningCertPath"];
            if (string.IsNullOrWhiteSpace(path) || !File.Exists(path))
                throw new InvalidOperationException(
                    "Certificato di firma QZ non trovato, QzSigningCertPath = " + path);

            // MachineKeySet: sotto IIS l'identità dell'app pool non ha un profilo
            // utente caricato, e senza questo flag la chiave privata non si apre.
            return new X509Certificate2(
                path,
                Environment.GetEnvironmentVariable("QZ_CERT_PASSWORD") ?? string.Empty,
                X509KeyStorageFlags.MachineKeySet);
        }
    }
}
```

Sull'autenticazione: `[Authorize]` è quello che usano già `CommandController` e
`ExportController`, e in questa applicazione significa il cookie di sessione di
Serenity. L'interceptor Angular manda `withCredentials` su ogni chiamata, quindi
dal frontend non serve fare niente di speciale.

**`api/qz/sign` firma qualunque stringa gli venga passata.** Lasciato anonimo
equivale a pubblicare la chiave privata: chiunque si farebbe firmare le proprie
richieste e stamperebbe sulle macchine dei clienti. L'attributo `[Authorize]` non
è una formalità, è la misura di sicurezza.

#### Variante senza PFX

Se si preferisce distribuire la coppia `.crt` + `-key.pem` così com'è, la strada
esiste senza aggiungere dipendenze: `SevenFreightools.Services` referenzia già
BouncyCastle (`Portable.BouncyCastle 1.9.0` e `BouncyCastle.Cryptography 2.0.0`),
il cui `PemReader` legge il PKCS#8 in poche righe. Restano però due file da
tenere allineati a mano, che è il motivo per cui sopra è consigliato il `.pfx`.

#### Variante Java 8 / TomEE 1.7.3

Se il backend è l'applicazione Java, il `.pfx` non serve: `prod-key.pem` è già
PKCS#8 (`-----BEGIN PRIVATE KEY-----`), che `PKCS8EncodedKeySpec` legge senza
librerie. Si deployano i due file così come escono dallo script.

TomEE 1.7.3 è Java EE 6, quindi **JAX-RS 1.1**, e gira su Java 8: niente
`Files.readString` né `Path.of`, che sono API di Java 11.

TomEE pubblica le risorse JAX-RS a partire da una classe `Application`:

```java
package eu.parvasoft.qz;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

@ApplicationPath("/api")
public class QzApplication extends Application {
}
```

```java
package eu.parvasoft.qz;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Firma le richieste di stampa inviate a Parva Tray. Senza una firma valida QZ
 * mostra un popup di conferma a ogni stampa.
 */
@Path("/qz")
public class QzResource {

    private static final String CERT_PATH = System.getenv("QZ_CERT_PATH");
    private static final String KEY_PATH = System.getenv("QZ_KEY_PATH");

    /** Caricata una volta sola: il parsing PKCS#8 non ha motivo di ripetersi a ogni stampa. */
    private static final PrivateKey KEY = loadKey();

    @GET
    @Path("/cert")
    @Produces(MediaType.TEXT_PLAIN)
    public String cert() throws IOException {
        return new String(Files.readAllBytes(Paths.get(CERT_PATH)), StandardCharsets.UTF_8);
    }

    @GET
    @Path("/sign")
    @Produces(MediaType.TEXT_PLAIN)
    public String sign(@QueryParam("request") String toSign) throws Exception {
        if (toSign == null || toSign.isEmpty()) {
            throw new WebApplicationException(Response.Status.BAD_REQUEST);
        }

        // SHA512 deve corrispondere a setSignatureAlgorithm lato client: il default
        // di QZ è SHA1 e la verifica fallirebbe senza dire perché.
        // Signature non è thread safe, quindi se ne crea una per richiesta.
        Signature signature = Signature.getInstance("SHA512withRSA");
        signature.initSign(KEY);
        signature.update(toSign.getBytes(StandardCharsets.UTF_8));

        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private static PrivateKey loadKey() {
        try {
            String pem = new String(Files.readAllBytes(Paths.get(KEY_PATH)), StandardCharsets.UTF_8)
                    .replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");

            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        } catch (Exception e) {
            throw new IllegalStateException("Chiave di firma QZ non caricabile: " + KEY_PATH, e);
        }
    }
}
```

Il decoder base64 va alimentato senza spazi: `Base64.getDecoder()` non tollera gli
a capo del PEM, da cui il `replaceAll` sugli spazi. Se preferite non toglierli,
`Base64.getMimeDecoder()` li ignora da solo.

A differenza di Web API, qui non c'è la trappola del JSON: con
`@Produces(MediaType.TEXT_PLAIN)` una `String` esce come corpo grezzo, che è
quello che QZ si aspetta.

L'autenticazione è quella del contenitore, con un vincolo su questi due path in
`web.xml`. Il ruolo va sostituito con quello dell'applicazione:

```xml
<security-constraint>
    <web-resource-collection>
        <web-resource-name>Firma di stampa QZ</web-resource-name>
        <url-pattern>/api/qz/*</url-pattern>
    </web-resource-collection>
    <auth-constraint>
        <role-name>utente</role-name>
    </auth-constraint>
</security-constraint>
```

Vale anche qui, e vale doppio: **`/api/qz/sign` firma qualunque stringa gli venga
passata.** Senza il vincolo è come pubblicare la chiave privata.

Se invece volete un artefatto solo per entrambi i backend, il `.pfx` lo legge
anche Java, con `KeyStore.getInstance("PKCS12")`, `ks.load(stream, password)` e
`ks.getKey(alias, password)`; con il `.pfx` senza password si passa
`new char[0]`.

### 4. Frontend

Le tre chiamate a QZ vanno fatte una volta sola, all'avvio. Usando `HttpClient` e
non `fetch` passano dall'`authInterceptor`, quindi cookie di sessione e gestione
degli errori dell'applicazione valgono anche qui:

```ts
@Injectable({ providedIn: 'root' })
export class QzSigningService {
  private readonly http = inject(HttpClient);

  configure(): void {
    qz.security.setCertificatePromise((resolve, reject) =>
      this.http.get('api/qz/cert', { responseType: 'text' })
        .subscribe({ next: resolve, error: reject }));

    // senza questo QZ assume SHA1 (PrintSocketClient.java:221)
    qz.security.setSignatureAlgorithm('SHA512');

    qz.security.setSignaturePromise((toSign: string) => (resolve, reject) =>
      this.http.get('api/qz/sign', { params: { request: toSign }, responseType: 'text' })
        .subscribe({ next: resolve, error: reject }));
  }
}
```

`responseType: 'text'` non è un dettaglio: senza, `HttpClient` prova a fare il
parse JSON del PEM e della firma, e fallisce prima ancora di arrivare a QZ.

L'algoritmo va dichiarato su entrambi i lati, qui e nel controller: il default
lato QZ è SHA1.

### 5. Provare tutto in locale

`parva/test-backend.py` fa da backend finto: serve la pagina demo prodotta dalla
build e i due endpoint che in produzione stanno sull'applicazione. Solo stdlib
Python e `openssl`, niente da installare.

```bash
ant                                   # serve out/dist/demo/sample.html
parva/test-backend.py                 # ambiente "prod", http://127.0.0.1:8080
parva/test-backend.py pre 9090        # altro ambiente, altra porta
parva/test-backend.py --selftest      # firma e riverifica, senza avviare il server
```

Non modifica la pagina demo: le appende uno script che sovrascrive le promise di
qz-tray.js (sono semplici assegnazioni, `js/qz-tray.js:2811`, quindi vince
l'ultima) e rimpiazza `qz:launch` con il nostro schema, che la demo di upstream
ha hardcoded. Lo schema lo legge da `project.datadir`, così resta allineato da
solo.

Perché il test sia significativo serve **Parva Tray installato dal pacchetto
costruito con `-Dprovision.file`**: è l'installazione che porta dentro la CA. Con
un'installazione senza CA il popup esce comunque, e non è un problema di firma.

In cima al tab "Pixel Printing" lo script aggiunge un riquadro **Direct printing
(spooler)**, che la demo di upstream non ha: stampa con `format: "direct"` (vedi
"I due percorsi di stampa"). A campo vuoto usa il file trascinato sulla pagina,
altrimenti l'URL che gli indichi. Usa la config del pannello sottostante, quindi
lasciando Orientation e Size su Default vengono dedotti dal PDF.

Il test è passato se stampi dalla pagina demo e non compare nessun popup. Per
isolare un fallimento prima di tirare in mezzo QZ:

```bash
curl -s http://127.0.0.1:8080/qz/cert | openssl x509 -noout -subject
curl -s --get --data-urlencode 'request=prova' http://127.0.0.1:8080/qz/sign
```

**Non è un backend di riferimento.** Firma qualunque stringa senza chiedere
niente a nessuno: ascolta solo su `127.0.0.1` e serve a provare il giro, non a
essere copiato in produzione. Quello vero è al punto 3, con l'autenticazione
dell'applicazione davanti.

### 6. Se il popup esce lo stesso

QZ fa quattro controlli in fila; il testo del popup dice quale è fallito:

| popup | causa |
|---|---|
| `Expired Signature` | orologio del PC fuori sincrono: la firma vale 15 minuti (`Constants.VALID_SIGNING_PERIOD`) |
| `Invalid Signature` | firma non corrispondente: algoritmo diverso fra JS e backend, o stringa alterata prima di firmare |
| `Untrusted website` | manca la CA nell'installazione: `provision.file` non passato al build |
| `Verified by Parvasoft` con Allow/Remember | catena ok ma impronta non ancora salvata: succede solo se la lista consentiti non è scrivibile |

I log sono in `%APPDATA%\parva\debug.log` su Windows e
`~/Library/Application Support/parva/debug.log` su macOS (`App.java:130`).

### 7. Aggiungere un ambiente o rinnovare

Nuovo ambiente: `parva/make-signing-certs.sh <nome>`, la CA resta la stessa e le
installazioni esistenti si fidano subito del nuovo certificato — nessun
reinstall. Per isolare gli ambienti basta tenere la chiave privata di ciascuno
solo sul backend corrispondente.

I certificati durano 10 anni, la CA 20. Alla scadenza di un certificato si
rigenera solo quello; la CA si tocca solo se scade lei, e in quel caso va
rifatto l'installer.

## Stampa: qualità e configurazione della coda

Se la stampa esce scadente — sfondi grigi a bande o "colonne", testo poco
contrastato, linee sottili irregolari — **prima di cercare l'opzione giusta in
QZ, controlla come è aggiunta la stampante.** Nel nostro caso reale nessuna
opzione c'entrava: era la coda.

Il sintomo tipico è che la stessa pagina, stampata da Anteprima, viene bene.
Non è perché Anteprima sia più brava: è perché sta parlando con un'altra
coda, quella AirPrint che macOS crea al volo per le "stampanti nelle vicinanze".

### Cosa succede

Una coda creata con un driver generico ha un PPD con un filtro di
rasterizzazione, per esempio:

```
*NickName:   "Generic PCL Laser Printer"
*cupsFilter: "application/vnd.cups-raster 50 rastertohp"
```

Con questo filtro il Mac rasterizza **ogni** job in locale e lo converte in PCL
a 1 bit: il grigio non esiste come tono, viene simulato con un retino di punti,
e a 300 dpi quel retino si vede a occhio nudo. La stampante non riceve mai il
PDF, riceve punti bianchi e neri già decisi.

Vale per qualunque percorso di stampa — pixel printing, direct printing, perfino
`lp` da riga di comando — quindi **non è risolvibile da codice**.

Una coda AirPrint non ha quel filtro:

```
*NickName:    "Brother MFC-L5750DW series-AirPrint"
*cupsFilter2: "image/urf image/urf 10 -"
```

Il `-` significa nessuna conversione: il documento arriva intatto e lo
rasterizza il RIP della stampante, alla sua risoluzione nativa e con i toni di
grigio veri.

### Come si verifica

```bash
lpstat -v                                        # come sono agganciate le code
grep -E '^\*NickName|^\*cupsFilter' /etc/cups/ppd/<coda>.ppd
```

Segnali di coda da rifare: `device-uri` che contiene `_printer._tcp` (il
protocollo LPR legacy), `NickName` che inizia per `Generic`, presenza di un
filtro `rastertohp` o simili.

Per sapere cosa saprebbe fare davvero la stampante, si interroga direttamente:

```bash
ipptool -tv "ipp://<indirizzo>:631/ipp/print" get-printer-attributes.test \
    | grep -E "document-format-supported|printer-resolution-supported"
```

Sulla MFC-L5750DW risponde `application/pdf` fra i formati e risoluzioni fino a
1200 dpi: tutto sprecato passando per il PCL generico.

### Come si sistema

Da interfaccia, su macOS: Impostazioni di Sistema -> Stampanti e Scanner ->
Aggiungi -> scheda **IP** -> Protocollo **AirPrint** (non "Internet Printing
Protocol - IPP": quello fa scegliere il driver a mano e si finisce sulle
generiche). Il campo Coda si lascia vuoto.

Equivalente da terminale:

```bash
sudo lpadmin -p <nome> -E -v "ipp://<indirizzo>:631/ipp/print" -m everywhere
```

Dopo l'aggiunta il modello deve risultare `... -AirPrint` e non `Generic ...`.
Conviene anche renderla la stampante predefinita, altrimenti chi non sceglie
esplicitamente ricade sulla coda vecchia.

**Va nelle istruzioni di installazione per i clienti.** Su una macchina con la
stampante aggiunta male, la qualità è compromessa a monte e nessuna versione
del nostro software la recupera. Su Windows lo stack è diverso e si usa il
driver del produttore, ma il principio resta: driver generico, qualità generica.

## I due percorsi di stampa

QZ può stampare un documento in due modi diversi, e conviene sapere quale si sta
usando perché rispettano opzioni diverse.

| | pixel printing | direct printing |
|---|---|---|
| come si chiede | `type: "pixel"`, `format: "pdf"` (o `html`, `image`) | `type: "pixel"`, `format: "direct"` |
| chi disegna | PDFBox dentro Parva Tray | il driver / la stampante |
| opzioni rispettate | tutte: scala, margini, dimensioni, densità, rotazione | orientamento, formato, densità, duplex, colore, copie |
| quando conviene | HTML e immagini generate al volo, quando serve controllare la geometria da codice | PDF già impaginati, come le fatture |

`format: "direct"` richiede `type: "pixel"`: con `type: "raw"` il formato viene
forzato a `command` (`PrintingUtilities.java:138`) e la stampa fallisce con
"Cannot parse ... into a raw DIRECT command".

In direct il documento viene consegnato allo spooler senza reinterpretarlo. I
dati si passano con `flavor: "file"` (una URL, anche `file://`, aperta dal
processo di Parva Tray) oppure `flavor: "base64"`.

### Patch nostre su PrintDirect

`src/qz/printer/action/PrintDirect.java` è un file di upstream che abbiamo
modificato: da rivedere a ogni merge. Upstream mandava allo spooler il solo nome
del job, quindi tutte le opzioni del client venivano perse e il risultato
dipendeva da come era configurata la coda. Ora:

* orientamento, formato carta, densità, duplex, colore e copie finiscono negli
  attributi del job;
* orientamento e formato, se il client non li specifica, vengono dedotti dalla
  prima pagina del PDF (un A4 orizzontale non veniva ruotato);
* una densità non supportata dalla stampante viene segnalata a log invece di
  sparire in silenzio;
* i dati base64 con prefisso `data:...;base64,` vengono ripuliti, altrimenti la
  stampante riceve byte spazzatura e li stampa come pagine di testo.

## Integrazione lato applicazione

Questa sezione è per chi integra la stampa in 7FTCustoms. La pagina di test
(`parva/test-backend.py`) è un riferimento utile da guardare, ma non è un
esempio copiabile: è la demo di upstream, jQuery e Bootstrap, con la logica di
connessione intrecciata ai suoi controlli. Qui c'è il minimo che serve davvero.

### Il client JavaScript

Il client è il pacchetto npm di upstream, LGPL-2.1:

```bash
npm install qz-tray
```

**Non va rebrandizzato.** Parla il protocollo, non conosce il nome dell'app, e
funziona identico con Parva Tray. L'unico punto sensibile al nostro rebranding è
lo schema URL usato per avviare il tray: la demo di upstream ha `qz:launch`
hardcoded, per noi è `parva:launch` (il valore di `project.datadir`).

Il pacchetto **non ha tipi TypeScript**. Serve una dichiarazione, che conviene
scrivere sui soli membri che usiamo invece di dichiarare tutto `any`:

```ts
// src/types/qz-tray.d.ts
declare module 'qz-tray' {
  export interface ConnectOptions {
    host?: string | string[];
    usingSecure?: boolean;
    retries?: number;
    delay?: number;
  }

  export interface PrintData {
    type: 'raw' | 'pixel';
    format?: 'command' | 'direct' | 'html' | 'image' | 'pdf';
    flavor?: 'plain' | 'base64' | 'hex' | 'file' | 'xml';
    data: string;
    options?: Record<string, unknown>;
  }

  export interface Config { }

  const qz: {
    websocket: {
      connect(options?: ConnectOptions): Promise<void>;
      disconnect(): Promise<void>;
      isActive(): boolean;
    };
    security: {
      setCertificatePromise(handler: (resolve: (cert: string) => void, reject: (e: unknown) => void) => void): void;
      setSignatureAlgorithm(algorithm: 'SHA1' | 'SHA256' | 'SHA512'): void;
      setSignaturePromise(factory: (toSign: string) => (resolve: (sig: string) => void, reject: (e: unknown) => void) => void): void;
    };
    printers: {
      getDefault(): Promise<string>;
      find(query?: string): Promise<string | string[]>;
      details(): Promise<unknown[]>;
    };
    configs: {
      create(printer: string, options?: Record<string, unknown>): Config;
    };
    print(config: Config, data: (PrintData | string)[]): Promise<void>;
  };

  export default qz;
}
```

### Connessione

Conviene connettersi **alla prima stampa**, non all'avvio dell'applicazione: chi
non stampa mai non deve vedere errori per un software che non ha installato.

```ts
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import qz, { PrintData } from 'qz-tray';

@Injectable({ providedIn: 'root' })
export class ParvaTrayService {
  private readonly http = inject(HttpClient);
  private securityReady = false;

  /** Idempotente: si può chiamare prima di ogni stampa. */
  async connect(): Promise<void> {
    if (qz.websocket.isActive()) { return; }

    this.configureSecurity();

    try {
      await qz.websocket.connect({ retries: 2, delay: 1 });
    } catch {
      // Installato ma non in esecuzione: il protocollo registrato dall'installer
      // lo avvia. Se anche il secondo tentativo fallisce, non è installato.
      window.location.assign('parva:launch');
      await qz.websocket.connect({ retries: 5, delay: 1 });
    }
  }

  async print(data: PrintData[], printer?: string, options?: Record<string, unknown>): Promise<void> {
    await this.connect();
    const target = printer ?? await qz.printers.getDefault();
    await qz.print(qz.configs.create(target, options), data);
  }

  /**
   * Le promise di firma vanno registrate una volta sola, prima della connessione.
   * Passando da HttpClient e non da fetch valgono authInterceptor e la gestione
   * errori dell'applicazione.
   */
  private configureSecurity(): void {
    if (this.securityReady) { return; }
    this.securityReady = true;

    qz.security.setCertificatePromise((resolve, reject) =>
      this.http.get('api/qz/cert', { responseType: 'text' })
        .subscribe({ next: resolve, error: reject }));

    // senza questo QZ assume SHA1 (PrintSocketClient.java:221)
    qz.security.setSignatureAlgorithm('SHA512');

    qz.security.setSignaturePromise((toSign: string) => (resolve, reject) =>
      this.http.get('api/qz/sign', { params: { request: toSign }, responseType: 'text' })
        .subscribe({ next: resolve, error: reject }));
  }
}
```

### Tutte le forme di chiamata

`qz.print(config, data)` accetta un array: ogni elemento è un documento o un
blocco di comandi, e possono avere forme diverse nella stessa chiamata.

| cosa stampi | chiamata |
|---|---|
| comandi nativi (ZPL, EPL, ESC/POS…) | `{ type: 'raw', data: '^XA…^XZ' }` |
| gli stessi comandi da un file o URL | `{ type: 'raw', flavor: 'file', data: 'https://…/etichetta.zpl' }` |
| un'immagine convertita nel linguaggio dell'etichettatrice | `{ type: 'raw', format: 'image', flavor: 'file', data: '…/logo.png', options: { language: 'zpl' } }` |
| un PDF renderizzato da Parva Tray | `{ type: 'pixel', format: 'pdf', flavor: 'file', data: 'https://…/fattura.pdf' }` |
| dell'HTML renderizzato da Parva Tray | `{ type: 'pixel', format: 'html', flavor: 'plain', data: '<h1>…</h1>' }` |
| un'immagine renderizzata da Parva Tray | `{ type: 'pixel', format: 'image', flavor: 'base64', data: 'iVBORw0…' }` |
| un documento consegnato **intatto** allo spooler | `{ type: 'pixel', format: 'direct', flavor: 'file', data: 'https://…/fattura.pdf' }` |

I `flavor` disponibili sono `plain` (il valore è il dato), `base64`, `hex`,
`file` (il valore è una URL, anche `file://`, che apre **il processo di Parva
Tray**, non il browser) e `xml` (base64 dentro un tag, con `options.xmlTag`).

I linguaggi accettati da `options.language` per il ramo raw sono quelli di
`LanguageType`: `ZPL`, `EPL`, `CPCL`, `ESCPOS`, `SBPL`, `PGL`, `EVOLIS`, `FGL`,
`DPL`, `CPL`, `ESCP`, `JSCRIPT`, `LP`, `STAR`, con i soliti alias (`ZEBRA` per
ZPL, `ELTRON` per EPL, `SATO` per SBPL…).

`type: 'raw'` forza sempre il formato a `command` (`PrintingUtilities.java:138`):
i formati `html`, `image` e `pdf` dentro un item raw vengono comunque gestiti,
perché li interpreta `PrintRaw` convertendoli in immagine e poi nel linguaggio
della stampante. `format: 'direct'` invece richiede `type: 'pixel'`.

Oltre alla stampa, sul client esistono:

```ts
qz.printers.getDefault()        // nome della stampante predefinita
qz.printers.find('Zebra')       // ricerca per sottostringa, o tutte se omesso
qz.printers.details()           // driver, densita', vassoi, formati supportati
qz.printers.getStatus()         // stato via listener, previo startListening
```

Ci sono anche i namespace `serial`, `socket`, `usb`, `hid`, `file` e
`networking`, per dialogare con bilance, lettori e periferiche. Non servono alla
stampa e ogni loro chiamata è firmata come le altre, quindi passa dagli stessi
due endpoint.

### Gli errori che l'utente vedrà davvero

Sono tre casi con rimedi diversi, e vanno distinti nel messaggio: "riprova"
nasconde il rimedio. La `NotificationService` dell'applicazione c'è già.

| situazione | come si riconosce | cosa dire |
|---|---|---|
| Parva Tray non installato | `connect` fallisce, `parva:launch` non apre niente, e anche il secondo tentativo fallisce | "Parva Tray non risulta installato su questo computer": è un'installazione da fare, non un errore temporaneo |
| installato ma non in esecuzione | il primo `connect` fallisce, dopo `parva:launch` il secondo riesce | niente da dire: si è risolto da solo |
| firma rifiutata | la connessione riesce, ma compare il popup di conferma a ogni stampa | è un problema di configurazione lato server o di CA mancante nell'installazione: vedi "Se il popup esce lo stesso" |

Dal browser i primi due non si distinguono in modo pulito: l'unico
discriminante è il secondo tentativo dopo `parva:launch`. È il motivo per cui
nel servizio qui sopra il retry non è un dettaglio ma la logica di diagnosi.

## Licenza

Upstream è LGPL-2.1. Il repo privato va bene; se distribuiamo a un cliente un
binario modificato, dobbiamo rendergli disponibile il sorgente delle modifiche.
