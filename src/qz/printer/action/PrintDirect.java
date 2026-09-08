package qz.printer.action;

import org.apache.commons.codec.binary.Base64InputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import qz.common.Constants;
import qz.printer.PrintOptions;
import qz.printer.PrintOutput;
import qz.utils.PrintingUtilities;

import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintException;
import javax.print.SimpleDoc;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.ResolutionSyntax;
import javax.print.attribute.Size2DSyntax;
import javax.print.attribute.standard.MediaSize;
import javax.print.attribute.standard.MediaSizeName;
import javax.print.attribute.standard.PrinterResolution;
import javax.print.attribute.standard.Copies;
import javax.print.attribute.standard.CopiesSupported;
import javax.print.attribute.standard.JobName;
import javax.print.attribute.standard.OrientationRequested;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;

public class PrintDirect extends PrintRaw {

    private static final Logger log = LogManager.getLogger(PrintDirect.class);

    /** Quanto puo' scostarsi la pagina dal formato standard, in pollici (~2.5mm) */
    private static final float MEDIA_TOLERANCE = 0.1f;

    private ArrayList<String> prints = new ArrayList<>();
    private ArrayList<PrintingUtilities.Flavor> flavors = new ArrayList<>();


    @Override
    public PrintingUtilities.Format getFormat() {
        return PrintingUtilities.Format.DIRECT;
    }

    @Override
    public void parseData(JSONArray printData, PrintOptions options) throws JSONException, UnsupportedOperationException {
        for(int i = 0; i < printData.length(); i++) {
            JSONObject data = printData.optJSONObject(i);
            if (data == null) { continue; }

            prints.add(data.getString("data"));
            flavors.add(PrintingUtilities.Flavor.parse(data, PrintingUtilities.Flavor.PLAIN));
        }
    }

    @Override
    public void print(PrintOutput output, PrintOptions options) throws PrintException {
        PrintOptions.Pixel pxlOpts = options.getPixelOptions();

        PrintRequestAttributeSet attributes = new HashPrintRequestAttributeSet();
        attributes.add(new JobName(options.getRawOptions().getJobName(Constants.RAW_PRINT), Locale.getDefault()));

        // Upstream manda allo spooler il solo nome del job, quindi tutte le opzioni del
        // client vengono perse e il risultato dipende da come e' configurata la coda.
        // Qui il documento lo disegna il driver, per cui si passano gli attributi che sa
        // applicare da se'; restano fuori i margini, che servono al percorso Java2D.
        if (pxlOpts.getColorType() != PrintOptions.ColorType.DEFAULT) {
            attributes.add(pxlOpts.getColorType().getAsChromaticity());
        }
        attributes.add(pxlOpts.getDuplex());
        if (pxlOpts.getOrientation() != null) {
            attributes.add(pxlOpts.getOrientation().getAsOrientRequested());
        }
        if (pxlOpts.getDensity() > 0) {
            int dpi = (int)(pxlOpts.getDensity() * pxlOpts.getUnits().as1Inch());
            PrinterResolution resolution = new PrinterResolution(dpi, dpi, ResolutionSyntax.DPI);
            // senza questo controllo una densita' non supportata verrebbe scartata in
            // silenzio e il job uscirebbe alla risoluzione di default della coda
            if (output.getPrintService().isAttributeValueSupported(resolution, null, null)) {
                attributes.add(resolution);
            } else {
                log.warn("The printer does not support {} dpi (density {} {}); the queue default will be used instead",
                         dpi, pxlOpts.getDensity(), pxlOpts.getUnits());
            }
        }
        if (pxlOpts.getSize() != null && pxlOpts.getSize().getWidth() > 0 && pxlOpts.getSize().getHeight() > 0) {
            float toInch = pxlOpts.getUnits().toInches();
            MediaSizeName media = MediaSize.findMedia((float)pxlOpts.getSize().getWidth() * toInch,
                                                      (float)pxlOpts.getSize().getHeight() * toInch,
                                                      Size2DSyntax.INCH);
            if (media != null) {
                attributes.add(media);
            }
        }

        // Copie: se il driver le sa fare da solo una stampa basta, altrimenti si ripete
        CopiesSupported cSupport = (CopiesSupported)output.getPrintService()
                .getSupportedAttributeValues(Copies.class, output.getPrintService().getSupportedDocFlavors()[0], attributes);
        int jobs = 1;
        if (cSupport != null && cSupport.contains(pxlOpts.getCopies())) {
            attributes.add(new Copies(pxlOpts.getCopies()));
        } else {
            jobs = pxlOpts.getCopies();
        }

        log.info("Starting printing ({} copies)", pxlOpts.getCopies());

        for(int i = 0; i < prints.size(); i++) {
            PrintRequestAttributeSet docAttributes = new HashPrintRequestAttributeSet(attributes);

            // Orientamento e formato carta vanno messi nel job: ereditare i default della
            // coda significa, su una coda tarata Letter, farsi riscalare un A4 dal driver.
            // ponytail: il documento viene tenuto in memoria per poterlo ispezionare e poi
            // ristampare; per file molto grandi servirebbe un file temporaneo.
            byte[] buffered = null;
            if (pxlOpts.getOrientation() == null || pxlOpts.getSize() == null) {
                try(InputStream peek = openStream(i)) {
                    buffered = peek.readAllBytes();
                }
                catch(IOException e) {
                    throw new PrintException(e);
                }
                applyDocumentDefaults(buffered, pxlOpts, docAttributes);
            }

            for(int copy = 0; copy < jobs; copy++) {
                DocPrintJob printJob = output.getPrintService().createPrintJob();
                InputStream stream = null;

                try {
                    stream = buffered != null? new ByteArrayInputStream(buffered):openStream(i);

                    SimpleDoc doc = new SimpleDoc(stream, DocFlavor.INPUT_STREAM.AUTOSENSE, null);

                    waitForPrint(printJob, doc, docAttributes);
                }
                catch(IOException e) {
                    throw new PrintException(e);
                }
                finally {
                    if (stream != null) {
                        try { stream.close(); } catch(Exception ignore) {}
                    }
                }
            }
        }
    }

    /**
     * Reads the first page geometry and puts orientation and media size in the job, so the
     * result doesn't depend on how the queue happens to be configured. Only fills in what
     * the caller left unset, and gives up quietly on anything that isn't a readable PDF.
     */
    private void applyDocumentDefaults(byte[] data, PrintOptions.Pixel pxlOpts, PrintRequestAttributeSet attributes) {
        if (data.length < 4 || data[0] != '%' || data[1] != 'P' || data[2] != 'D' || data[3] != 'F') {
            return;
        }
        try(PDDocument doc = PDDocument.load(data)) {
            if (doc.getNumberOfPages() == 0) {
                return;
            }
            PDPage page = doc.getPage(0);
            PDRectangle bounds = page.getBBox();

            float width = bounds.getWidth();
            float height = bounds.getHeight();
            if ((page.getRotation() / 90) % 2 == 1) {
                width = bounds.getHeight();
                height = bounds.getWidth();
            }

            if (pxlOpts.getOrientation() == null) {
                OrientationRequested orientation = width > height? OrientationRequested.LANDSCAPE:OrientationRequested.PORTRAIT;
                log.info("Orientation not specified, using {} from the document", orientation);
                attributes.add(orientation);
            }

            if (pxlOpts.getSize() == null) {
                // il formato si cerca sempre in verticale, a ruotare ci pensa l'orientamento
                float shortSide = Math.min(width, height) / 72f;
                float longSide = Math.max(width, height) / 72f;

                MediaSizeName media = MediaSize.findMedia(shortSide, longSide, Size2DSyntax.INCH);
                MediaSize found = media == null? null:MediaSize.getMediaSizeForName(media);
                if (found != null
                        && Math.abs(found.getX(Size2DSyntax.INCH) - shortSide) < MEDIA_TOLERANCE
                        && Math.abs(found.getY(Size2DSyntax.INCH) - longSide) < MEDIA_TOLERANCE) {
                    log.info("Media size not specified, using {} from the document", media);
                    attributes.add(media);
                } else {
                    log.warn("Document is {}x{}in, which matches no standard media size; leaving it to the printer",
                             shortSide, longSide);
                }
            }
        }
        catch(Exception e) {
            log.warn("Could not read the document to detect its page setup, leaving it to the printer", e);
        }
    }

    /** Ogni copia consuma lo stream, quindi va riaperto a ogni giro */
    private InputStream openStream(int i) throws IOException {
        switch(flavors.get(i)) {
            case BASE64:
                // Un client che passa una data URI intera decodificherebbe in spazzatura, e
                // qui la spazzatura finisce in stampa: meglio togliere il prefisso.
                String encoded = prints.get(i);
                int prefix = encoded.startsWith("data:")? encoded.indexOf("base64,"):-1;
                if (prefix >= 0) {
                    log.warn("Stripping the data URI prefix from base64 print data");
                    encoded = encoded.substring(prefix + "base64,".length());
                }
                return new Base64InputStream(new ByteArrayInputStream(encoded.getBytes("UTF-8")));
            case FILE:
                return new DataInputStream(new URL(prints.get(i)).openStream());
            case PLAIN:
            default:
                return new ByteArrayInputStream(prints.get(i).getBytes("UTF-8"));
        }
    }

    @Override
    public void cleanup() {
        prints.clear();
        flavors.clear();
    }

}
