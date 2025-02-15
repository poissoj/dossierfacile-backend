package fr.dossierfacile.api.pdfgenerator.service.templates;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import com.google.zxing.multi.MultipleBarcodeReader;
import com.twelvemonkeys.image.ImageUtil;
import fr.dossierfacile.api.pdfgenerator.configuration.FeatureFlipping;
import fr.dossierfacile.api.pdfgenerator.model.FileInputStream;
import fr.dossierfacile.api.pdfgenerator.model.PageDimension;
import fr.dossierfacile.api.pdfgenerator.model.PdfTemplateParameters;
import fr.dossierfacile.api.pdfgenerator.service.interfaces.PdfSignatureService;
import fr.dossierfacile.api.pdfgenerator.service.interfaces.PdfTemplate;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.tools.imageio.ImageIOUtil;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Primary;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

@Service
@AllArgsConstructor
@Slf4j
@Primary
public class BOPdfDocumentTemplate implements PdfTemplate<List<FileInputStream>> {
    public static final String DEFAULT_WATERMARK = "  DOCUMENTS EXCLUSIVEMENT DESTIN\u00c9S \u00c0 LA LOCATION IMMOBILI\u00c8RE     ";
    private final PdfTemplateParameters params = PdfTemplateParameters.builder().build();
    private final Locale locale = LocaleContextHolder.getLocale();
    private final MessageSource messageSource;

    private final FeatureFlipping featureFlipping;
    private final PdfSignatureService pdfSignatureService;

    private final Color[] COLORS = {
            new Color(64, 64, 64, 255),
            new Color(32, 32, 32, 220),
            new Color(0, 0, 0, 110),
            new Color(0, 0, 91, 170),
            new Color(255, 0, 0, 170)
    };


    private static ConvolveOp getGaussianBlurFilter(int radius, boolean horizontal) {
        int size = radius * 2 + 1;
        float[] data = new float[size];

        float sigma = radius / 3.0f;
        float twoSigmaSquare = 2.0f * sigma * sigma;
        float sigmaRoot = (float) Math.sqrt(twoSigmaSquare * Math.PI);
        float total = 0.0f;

        for (int i = -radius; i <= radius; i++) {
            float distance = i * i;
            int index = i + radius;
            data[index] = (float) Math.exp(-distance / twoSigmaSquare) / sigmaRoot;
            total += data[index];
        }
        for (int i = 0; i < data.length; i++) {
            data[i] /= total;
        }
        Kernel kernel = (horizontal) ? new Kernel(size, 1, data) : new Kernel(1, size, data);

        return new ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null);
    }

    @Override
    public InputStream render(List<FileInputStream> data) throws Exception {
        return this.render(data,
                messageSource.getMessage("tenant.pdf.watermark", null, DEFAULT_WATERMARK, locale));
    }

    public InputStream render(List<FileInputStream> data, String watermarkText) throws Exception {
        final String watermarkToApply = StringUtils.isNotBlank(watermarkText) ? watermarkText + "   " :
                messageSource.getMessage("tenant.pdf.watermark.default", null, " https://filigrane.beta.gouv.fr/   ", locale);

        try (PDDocument document = new PDDocument()) {

            data.stream()
                    .map(this::convertToImages)
                    .flatMap(Collection::stream)
                    .map(this::smartCrop)
                    .filter(Objects::nonNull)
                    .map(this::fitImageToPage)
                    .map(bim -> applyWatermark(bim, watermarkToApply))
                    .forEach(bim -> addImageAsPageToDocument(document, bim));


            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

                pdfSignatureService.signAndSave(document, baos);
                return new ByteArrayInputStream(baos.toByteArray());
            }
        } catch (Exception e) {
            log.error("Exception while generate BO PDF documents", e);
            throw e;
        }
    }

    /**
     * Convert PDF to image - let other type unchanged
     *
     * @param fileInputStream source
     * @return list of extracted images
     */
    private List<BufferedImage> convertToImages(FileInputStream fileInputStream) {
        try {

            if (MediaType.APPLICATION_PDF.equalsTypeAndSubtype(fileInputStream.getMediaType())) {
                List<BufferedImage> images = new ArrayList<>();
                try (PDDocument document = Loader.loadPDF(fileInputStream.getInputStream().readAllBytes())) {
                    PDFRenderer pdfRenderer = new PDFRenderer(document);
                    PDPageTree pagesTree = document.getPages();
                    for (int i = 0; i < pagesTree.getCount(); i++) {
                        PDRectangle pageMediaBox = pagesTree.get(i).getMediaBox();
                        float scale = getScale(pageMediaBox);
                        // x2 - double the image resolution (prevent quality loss if image is cropped)
                        images.add(pdfRenderer.renderImage(i, scale * 2, ImageType.RGB));
                    }
                    return images;
                } catch (Exception e) {
                    log.error("Exception while converting pdf page to image", e);
                    return images;
                }
            }

            return Collections.singletonList(createImageWithOrientation(fileInputStream.getInputStream()));

        } catch (IOException e) {
            throw new RuntimeException("Unable to convert pdf to image", e);
        }
    }

    private float getScale(PDRectangle pageMediaBox) {
        float ratioImage = pageMediaBox.getHeight() / pageMediaBox.getWidth();
        float ratioPDF = params.mediaBox.getHeight() / params.mediaBox.getWidth();

        // scale according the greater axis
        PageDimension dimension = (ratioImage < ratioPDF) ?
                new PageDimension((int) pageMediaBox.getWidth(), (int) (pageMediaBox.getWidth() * ratioPDF), 0)
                : new PageDimension((int) (pageMediaBox.getHeight() / ratioPDF), (int) pageMediaBox.getHeight(), 0);

        return (dimension.width < params.maxPage.width) ? 1f :
                params.maxPage.width / pageMediaBox.getWidth();
    }

    private BufferedImage createImageWithOrientation(InputStream inputStream) throws IOException {
        // duplicate input stream - because it will be read twice
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        IOUtils.copy(inputStream, baos);
        byte[] bytes = baos.toByteArray();

        try (ByteArrayInputStream imageInput = new ByteArrayInputStream(bytes)) {
            BufferedImage image = ImageIO.read(imageInput);

            // check en rotate according metadata
            try (ByteArrayInputStream imageInputForMeta = new ByteArrayInputStream(bytes)) {

                Metadata metadata = ImageMetadataReader.readMetadata(imageInputForMeta);
                Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
                int orientation = dir != null && dir.getInteger(ExifIFD0Directory.TAG_ORIENTATION) != null
                        ? dir.getInt(ExifIFD0Directory.TAG_ORIENTATION) : 0;

                return switch (orientation) {
                    case 2 -> ImageUtil.createFlipped(image, ImageUtil.FLIP_HORIZONTAL);
                    case 3 -> ImageUtil.createRotated(image, ImageUtil.ROTATE_180);
                    case 4 -> ImageUtil.createFlipped(image, ImageUtil.FLIP_VERTICAL);
                    case 5 -> ImageUtil.createRotated(
                            ImageUtil.createFlipped(image, ImageUtil.FLIP_VERTICAL),
                            ImageUtil.ROTATE_90_CW);
                    case 6 -> ImageUtil.createRotated(image, ImageUtil.ROTATE_90_CW);
                    case 7 -> ImageUtil.createRotated(
                            ImageUtil.createFlipped(image, ImageUtil.FLIP_VERTICAL),
                            ImageUtil.ROTATE_90_CCW);
                    case 8 -> ImageUtil.createRotated(image, ImageUtil.ROTATE_90_CCW);
                    default -> image; // 0,1 included
                };

            } catch (Exception e) {
                log.error("Unable to rotate and flip from metadata", e);
            }
            return image;
        }
    }

    /**
     * Apply document crop if require
     */
    protected BufferedImage smartCrop(BufferedImage image) {
        // by default there is not crop on BO Documents
        return image;
    }

    /**
     * Apply watermark - source image should already have good ratio
     *
     * @param bim source image
     * @return result image
     */
    public BufferedImage applyWatermark(BufferedImage bim, String watermarkText) {
        try {
            //Create a watermark layer
            int diagonal = (int) Math.sqrt(bim.getWidth() * bim.getWidth() + bim.getHeight() * bim.getHeight());

            BufferedImage watermarkLayer = new BufferedImage(diagonal, diagonal, BufferedImage.TYPE_INT_ARGB);

            Graphics2D g = watermarkLayer.createGraphics();

            String watermark = watermarkText.repeat(1 + (128 / watermarkText.length()));

            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ThreadLocalRandom.current().nextFloat(0.52f, 0.6f)));
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);


            // allows to have small variation on the watermark position at each generation
            float spaceBetweenText = diagonal / ThreadLocalRandom.current().nextFloat(8f, 10f);
            for (int i = 1; i < 11; i++) {
                Font font = new Font("Arial", Font.PLAIN, 28 * bim.getWidth() / params.maxPage.width);
                if (featureFlipping.shouldUseColors()) {
                    g.setColor(COLORS[ThreadLocalRandom.current().nextInt(0, COLORS.length)]);
                } else {
                    g.setColor(Color.DARK_GRAY);
                }
                g.setFont(font);
                g.drawString(watermark, 0, i * spaceBetweenText);
            }

            // Create a gaussian blur layer
            int radius = ThreadLocalRandom.current().nextInt(45, 65);

            BufferedImage blurredTextLayer = new BufferedImage(diagonal, diagonal, BufferedImage.TYPE_INT_ARGB);
            Graphics2D blurredTextLayerGraphics = blurredTextLayer.createGraphics();
            blurredTextLayerGraphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ThreadLocalRandom.current().nextFloat(0.75f, 0.95f)));
            blurredTextLayerGraphics.drawImage(watermarkLayer, 0, 0, null);
            blurredTextLayer = getGaussianBlurFilter(radius, true).filter(blurredTextLayer, null);
            blurredTextLayer = getGaussianBlurFilter(radius, false).filter(blurredTextLayer, null);
            blurredTextLayerGraphics.dispose();

            // Merge layers
            Graphics2D gf = bim.createGraphics();
            gf.drawImage(bim, 0, 0, null);

            BufferedImage buffer;
            if (featureFlipping.shouldUseDistortion()) {
                DFFilter filter = new DFFilter();
                buffer = filter.filter(watermarkLayer, null);
            } else {
                buffer = watermarkLayer;
            }

            BufferedImage rotated = new BufferedImage(diagonal, diagonal, buffer.getType());
            Graphics2D graphic = rotated.createGraphics();
            graphic.rotate(Math.toRadians(-25), diagonal / 2f, diagonal / 2f);
            graphic.drawImage(buffer, null, 0, 0);
            graphic.drawImage(blurredTextLayer, 0, 0, null);
            graphic.dispose();

            BufferedImage cropedRotated = rotated.getSubimage(diagonal / 2 - bim.getWidth() / 2, diagonal / 2 - bim.getHeight() / 2, diagonal / 2 + bim.getWidth() / 2, diagonal / 2 + bim.getHeight() / 2);
            List<Result> qrCodes = detectQRCodes(bim);

            Graphics2D graphics = cropedRotated.createGraphics();
            graphics.setComposite(AlphaComposite.Clear);
            int MAGIC_BORDER_SIZE = 20;
            for (Result qrCode : qrCodes) {
                ResultPoint[] points = qrCode.getResultPoints();
                if (points.length == 3) {
                    int minX = (int) Math.min(points[0].getX(), Math.min(points[1].getX(), points[2].getX())) - MAGIC_BORDER_SIZE;
                    int minY = (int) Math.min(points[0].getY(), Math.min(points[1].getY(), points[2].getY())) - MAGIC_BORDER_SIZE;
                    int maxX = (int) Math.max(points[0].getX(), Math.max(points[1].getX(), points[2].getX())) + MAGIC_BORDER_SIZE;
                    int maxY = (int) Math.max(points[0].getY(), Math.max(points[1].getY(), points[2].getY())) + MAGIC_BORDER_SIZE;

                    int width = maxX - minX;
                    int height = maxY - minY;
                    graphics.fillRect(minX, minY, (width), (height));
                }
            }
            graphics.dispose();

            gf.drawImage(cropedRotated, 0, 0, null);

            gf.dispose();

            return bim;
        } catch (Exception e) {
            log.error("Unable to fit image to the page", e);
            throw new RuntimeException("Unable to fit image to the page", e);
        }
    }

    private static List<Result> detectQRCodes(BufferedImage image) {
        LuminanceSource source = new BufferedImageLuminanceSource(image);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        com.google.zxing.Reader reader = new MultiFormatReader();
        MultipleBarcodeReader bcReader = new GenericMultipleBarcodeReader(reader);
        Hashtable<DecodeHintType, Object> hints = new Hashtable<>();
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        try {
            return List.of(bcReader.decodeMultiple(bitmap, hints));
        } catch (NotFoundException e) {
            return new ArrayList<>();
        }
    }

    /**
     * Fit image to page dimension - A4 with 128 DPI
     *
     * @param bim
     * @return image fit to the page
     */
    private BufferedImage fitImageToPage(BufferedImage bim) {
        try {
            float ratioImage = bim.getHeight() / (float) bim.getWidth();
            float ratioPDF = params.mediaBox.getHeight() / params.mediaBox.getWidth();

            // scale according the greater axis
            PageDimension dimension = (ratioImage < ratioPDF) ?
                    new PageDimension(bim.getWidth(), (int) (bim.getWidth() * ratioPDF), 0)
                    : new PageDimension((int) (bim.getHeight() / ratioPDF), bim.getHeight(), 0);


            float scale = (dimension.width < params.maxPage.width) ? 1f :
                    params.maxPage.width / (float) bim.getWidth();// image is too big - scale if necessary

            // translate in center and scale if necessary
            AffineTransform affineTransform = new AffineTransform();
            affineTransform.translate(scale * (dimension.width - bim.getWidth()) / 2, scale * (dimension.height - bim.getHeight()) / 2);
            affineTransform.scale(scale, scale);

            // Draw the image on to the buffered image
            BufferedImage resultImage = new BufferedImage((int) (scale * dimension.width), (int) (scale * dimension.height), BufferedImage.TYPE_INT_RGB);

            Graphics2D g = resultImage.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, (int) (scale * dimension.width), (int) (scale * dimension.height));
            g.drawImage(bim, affineTransform, null);
            g.dispose();

            return resultImage;
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Unable to fit image to the page", e);
        }
    }

    /**
     * Add A4 page to Document from image.
     *
     * @param document document destination
     * @param bim      image to include
     */
    private void addImageAsPageToDocument(PDDocument document, BufferedImage bim) {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIOUtil.writeImage(bim, "jpg", out, params.maxPage.dpi, params.compressionQuality);


            PDImageXObject pdImage = PDImageXObject.createFromByteArray(document, out.toByteArray(), "");
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.OVERWRITE, true, true)) {
                contentStream.drawImage(pdImage, 0, 0, PDRectangle.A4.getWidth(), bim.getHeight() * PDRectangle.A4.getWidth() / bim.getWidth());
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("Unable to write image");
        }
    }

}

