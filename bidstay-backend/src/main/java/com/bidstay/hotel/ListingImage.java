package com.bidstay.hotel;

import com.bidstay.common.ApiException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

/** Renders pixels into bounded JPEGs without retaining the original upload or its metadata. */
final class ListingImage {
    static final int MAX_BYTES = 5 * 1024 * 1024;

    private ListingImage() {}

    static Renditions create(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw ApiException.invalid("Choose a photo under 5 MB");
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("jpeg") && !format.equals("png")) throw invalid();
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long) width * height > 20_000_000) {
                    throw ApiException.invalid("Photo resolution exceeds 20 megapixels");
                }
                BufferedImage source = reader.read(0);
                return new Renditions(jpeg(source, 1440), jpeg(source, 480));
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static byte[] jpeg(BufferedImage source, int maxSide) throws IOException {
        double scale = Math.min(1.0, (double) maxSide / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D canvas = image.createGraphics();
        try {
            canvas.setColor(Color.WHITE);
            canvas.fillRect(0, 0, width, height);
            canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            canvas.drawImage(source, 0, 0, width, height, null);
        } finally {
            canvas.dispose();
        }
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var result = new ByteArrayOutputStream();
        try (var output = ImageIO.createImageOutputStream(result)) {
            writer.setOutput(output);
            var parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(0.85f);
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
        return result.toByteArray();
    }

    private static ApiException invalid() {
        return ApiException.invalid("Choose a readable JPEG or PNG image");
    }

    record Renditions(byte[] full, byte[] thumbnail) {}
}
