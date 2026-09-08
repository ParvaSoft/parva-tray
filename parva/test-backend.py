#!/usr/bin/env python3
"""
Backend di firma finto, SOLO PER TEST IN LOCALE.

Serve la pagina demo prodotta dalla build e i due endpoint che in produzione
stanno sull'applicazione:

    GET /qz/cert                 -> il certificato pubblico dell'ambiente
    GET /qz/sign?request=<dato>  -> la firma SHA512withRSA, in base64

Alla pagina demo viene appeso uno script che sovrascrive le promise di
qz-tray.js per puntare qui: il file su disco non viene toccato.

    ./parva/test-backend.py               # ambiente "prod", porta 8080
    ./parva/test-backend.py pre 9090
    ./parva/test-backend.py --selftest    # verifica firma senza avviare nulla

Firma qualunque cosa gli si passi, senza autenticazione: ascolta solo su
127.0.0.1 e non deve finire da nessun'altra parte.
"""

import base64
import functools
import http.server
import subprocess
import sys
import tempfile
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEMO = ROOT / "out" / "dist" / "demo"
CERTS = ROOT / "parva" / "certs"


def ant_property(name):
    """Legge una property da ant/project.properties, cosi' i nomi restano allineati."""
    for line in (ROOT / "ant" / "project.properties").read_text().splitlines():
        key, sep, value = line.partition("=")
        if sep and key.strip() == name:
            return value.strip()
    raise SystemExit(f"property mancante in ant/project.properties: {name}")


def sign(key, data):
    """La stessa firma che fara' il backend: SHA512withRSA, base64."""
    out = subprocess.run(["openssl", "dgst", "-sha512", "-sign", str(key)],
                         input=data, capture_output=True, check=True).stdout
    return base64.b64encode(out)


OVERRIDE = """
<script>
    /// parva/test-backend.py: cert e firma arrivano da questo server ///
    qz.security.setCertificatePromise(function(resolve, reject) {
        fetch('/qz/cert', {cache: 'no-store'})
            .then(function(r) { return r.text(); }).then(resolve).catch(reject);
    });
    qz.security.setSignatureAlgorithm('SHA512');
    qz.security.setSignaturePromise(function(toSign) {
        return function(resolve, reject) {
            fetch('/qz/sign?request=' + encodeURIComponent(toSign), {cache: 'no-store'})
                .then(function(r) { return r.text(); }).then(resolve).catch(reject);
        };
    });

    // la demo di upstream ha "qz:launch" hardcoded, qui vale il nostro schema
    window.launchQZ = function() {
        if (!qz.websocket.isActive()) {
            window.location.assign('__SCHEME__:launch');
            startConnection({retries: 5, delay: 1});
        }
    };

    /// Direct printing: manda il file allo spooler senza passare per Java2D ///
    $('#pxlContent').prepend(
        '<div class="panel panel-default" style="border-color:#5cb85c">' +
        '  <div class="panel-heading"><h3 class="panel-title">Direct printing (spooler)</h3></div>' +
        '  <div class="panel-body">' +
        '    <input id="parvaDirectUrl" type="text" class="form-control"' +
        '           placeholder="file:///percorso/file.pdf oppure http://... (vuoto: usa il file trascinato)" />' +
        '    <button id="parvaDirectBtn" type="button" class="btn btn-success" style="margin-top:10px">Print direct</button>' +
        '    <p class="help-block" style="margin-top:10px">Consegna il file allo spooler come fa Anteprima:' +
        '       lo rasterizza il driver, non Java2D. Usa orientamento, formato, densita\\', duplex,' +
        '       colore e copie del pannello qui sotto; margini e scala non si applicano.' +
        '       Orientamento e formato lasciati su Default vengono dedotti dal PDF;' +
        '       per la densita\\' scrivi <code>best</code>, che evita equivoci sulle unita\\'.</p>' +
        '  </div>' +
        '</div>');

    $('#parvaDirectBtn').on('click', function() {
        var url = $.trim($('#parvaDirectUrl').val());
        var dropped = $('#pxlDropBtn').attr('data-base64');
        var flavor, data;

        if (url) {
            flavor = 'file'; data = url;
        } else if (dropped) {
            // la demo ci mette l'intera data URI (readAsDataURL): va tolto il prefisso,
            // altrimenti la stampante riceve byte spazzatura e li stampa come testo
            data = dropped.split('base64,')[1];
            if (!data) {
                displayError('Il file trascinato non e\\' in base64 leggibile.');
                return;
            }
            flavor = 'base64';
        } else {
            displayError('Trascina un file sulla pagina, oppure indica una URL.');
            return;
        }

        // type "pixel" non rasterizza: e' il ramo che fa leggere il campo "format"
        qz.print(getUpdatedConfig(), [{type: 'pixel', format: 'direct', flavor: flavor, data: data}])
            .then(function() { displayMessage('Inviato allo spooler (' + flavor + ').'); })
            .catch(displayError);
    });

    console.log('Firma via __CERT__');
</script>
"""


def patched_page(cert_path, launch_scheme):
    page = (DEMO / "sample.html").read_bytes()
    override = OVERRIDE.replace("__SCHEME__", launch_scheme).replace("__CERT__", cert_path.name)
    return page + override.encode()


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, cert, key, page, **kwargs):
        self.cert, self.key, self.page = cert, key, page
        super().__init__(*args, directory=str(DEMO), **kwargs)

    def reply(self, body, ctype="text/plain"):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path, _, query = self.path.partition("?")
        if path == "/qz/cert":
            return self.reply(self.cert.read_bytes())
        if path == "/qz/sign":
            request = urllib.parse.parse_qs(query).get("request", [""])[0]
            return self.reply(sign(self.key, request.encode()))
        if path in ("/", "/sample.html"):
            return self.reply(self.page, "text/html")
        return super().do_GET()


def selftest(cert, key):
    """Firma e riverifica con la chiave pubblica del certificato, come fara' QZ."""
    data = b'{"call":"print","params":{},"timestamp":0}'
    pub = subprocess.run(["openssl", "x509", "-in", str(cert), "-pubkey", "-noout"],
                         capture_output=True, check=True).stdout
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        (tmp / "pub").write_bytes(pub)
        (tmp / "sig").write_bytes(base64.b64decode(sign(key, data)))
        (tmp / "data").write_bytes(data)
        subprocess.run(["openssl", "dgst", "-sha512", "-verify", str(tmp / "pub"),
                        "-signature", str(tmp / "sig"), str(tmp / "data")],
                       check=True, stdout=subprocess.DEVNULL)


def main():
    args = [a for a in sys.argv[1:] if a != "--selftest"]
    env = args[0] if args else "prod"
    port = int(args[1]) if len(args) > 1 else 8080

    cert, key = CERTS / f"{env}.crt", CERTS / f"{env}-key.pem"
    for f in (cert, key):
        if not f.exists():
            raise SystemExit(f"manca {f}\nGenera i certificati: parva/make-signing-certs.sh {env}")

    if "--selftest" in sys.argv:
        selftest(cert, key)
        print("Firma e verifica: OK")
        return

    if not (DEMO / "sample.html").exists():
        raise SystemExit(f"manca {DEMO}/sample.html\nFai prima una build: ant")

    page = patched_page(cert, ant_property("project.datadir"))
    handler = functools.partial(Handler, cert=cert, key=key, page=page)
    print(f"Pagina di test:  http://127.0.0.1:{port}/\n"
          f"Firma con:       {key.name}\n"
          f"Ctrl-C per uscire. Firma qualunque richiesta, non esporlo in rete.")
    http.server.ThreadingHTTPServer(("127.0.0.1", port), handler).serve_forever()


if __name__ == "__main__":
    main()
