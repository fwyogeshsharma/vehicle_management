package com.vehiclemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The scaler, on its own. No database, no Spring — it is a pure function over bytes, which is
 * the cheapest kind of thing to test and the kind this project has historically left untested.
 */
class ThumbnailsTest {

    /** Something shaped like a field photo: 4000px wide, 3:4, not a flat colour. */
    private static byte[] photo(int width, int height, String format) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setPaint(new java.awt.GradientPaint(0, 0, Color.WHITE, width, height, Color.BLUE));
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        g.fillRect(width / 4, height / 2, width / 2, height / 8);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Nested
    @DisplayName("a photo larger than the target")
    class Shrinks {

        @Test
        void comesBackAtTheRequestedWidth() throws IOException {
            byte[] scaled = Thumbnails.scaleToWidth(photo(4000, 3000, "jpg"), 200);

            assertThat(scaled).isNotNull();
            assertThat(decode(scaled).getWidth()).isEqualTo(200);
        }

        @Test
        void keepsItsAspectRatio() throws IOException {
            BufferedImage out = decode(Thumbnails.scaleToWidth(photo(4000, 3000, "jpg"), 200));

            // 200 x 150, give or take the rounding of one row.
            assertThat(out.getHeight()).isBetween(149, 151);
        }

        @Test
        @DisplayName("and is dramatically smaller, which is the entire point")
        void isMuchSmaller() throws IOException {
            byte[] original = photo(4000, 3000, "jpg");
            byte[] scaled = Thumbnails.scaleToWidth(original, 200);

            assertThat(scaled.length).isLessThan(original.length / 20);
        }

        @Test
        @DisplayName("a PNG too, flattened to JPEG rather than failing on its alpha channel")
        void handlesPng() throws IOException {
            byte[] scaled = Thumbnails.scaleToWidth(photo(2000, 1500, "png"), 200);

            assertThat(scaled).isNotNull();
            assertThat(decode(scaled).getWidth()).isEqualTo(200);
        }

        @Test
        @DisplayName("a target barely under the source still resizes, and does not divide by zero")
        void handlesANearlyIdenticalTarget() throws IOException {
            byte[] scaled = Thumbnails.scaleToWidth(photo(300, 225, "jpg"), 299);

            assertThat(decode(scaled).getWidth()).isEqualTo(299);
        }
    }

    @Nested
    @DisplayName("null, meaning send the original")
    class DeclinesTo {

        @Test
        @DisplayName("when the photo is already narrower than the target")
        void whenAlreadySmallEnough() throws IOException {
            assertThat(Thumbnails.scaleToWidth(photo(120, 90, "jpg"), 200)).isNull();
        }

        @Test
        @DisplayName("when the bytes are not an image at all — a broken list beats a failed one")
        void whenTheBytesAreNotAnImage() {
            assertThat(Thumbnails.scaleToWidth("not a photograph".getBytes(), 200)).isNull();
        }

        @Test
        void whenTheBytesAreEmpty() {
            assertThat(Thumbnails.scaleToWidth(new byte[0], 200)).isNull();
        }

        @Test
        @DisplayName("when the bytes claim a size this JVM will not allocate")
        void whenTheHeaderIsNonsense() {
            // A BMP header promising a 2-billion-pixel image. Nothing is read, so nothing is
            // allocated — the point is that an OutOfMemoryError-shaped input is still just null.
            byte[] absurd = new byte[] {
                'B', 'M', 0, 0, 0, 0, 0, 0, 0, 0, 54, 0, 0, 0, 40, 0, 0, 0,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F, 1, 0, 24, 0,
            };

            assertThat(Thumbnails.scaleToWidth(absurd, 200)).isNull();
        }
    }

    @Test
    @DisplayName("a half-downloaded JPEG still yields a thumbnail, rather than nothing")
    void survivesATruncatedJpeg() throws IOException {
        // The JPEG reader is lenient: the header carries the dimensions, so a file cut off
        // mid-scan decodes as far as it got and greys the rest. That is the behaviour to want
        // here — half a photograph tells a CSR which row they are looking at, and the
        // alternative on this path is the multi-megabyte original.
        byte[] original = photo(1000, 750, "jpg");
        byte[] truncated = new byte[original.length / 2];
        System.arraycopy(original, 0, truncated, 0, truncated.length);

        byte[] scaled = Thumbnails.scaleToWidth(truncated, 200);

        assertThat(scaled).isNotNull();
        assertThat(decode(scaled).getWidth()).isEqualTo(200);
    }
}
