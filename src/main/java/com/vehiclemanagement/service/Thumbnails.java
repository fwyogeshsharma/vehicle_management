package com.vehiclemanagement.service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shrink a field photo down to something a list can afford to show.
 *
 * <p><b>Why this exists.</b> The CSR worklist renders one photo per row into an 84x63 box, and
 * it was being handed the original — a 3 to 4 MB, 4000px-wide phone photo. The download is the
 * small half of that cost: a browser decodes each one to a full-size bitmap before scaling it,
 * so eight rows put roughly 27 MB on the wire and several hundred megabytes of pixels in the
 * renderer. Measured on the twenty photos in the first real batch, that froze Chrome for about
 * thirty seconds per page. The HTTP cache does not help — the bytes are cached, the decode is
 * not, and it happens again on every render.
 *
 * <p><b>Decode small, do not decode and then shrink.</b> {@link ImageReadParam#setSourceSubsampling}
 * makes the JPEG reader skip pixels as it goes, so peak memory is the reduced image rather than
 * the 48 MB bitmap the full frame would need. That is the whole trick, and it is why this does
 * not simply call {@code ImageIO.read} followed by a scale.
 *
 * <p>Subsampling alone aliases badly — it is nearest-neighbour by another name — so this decodes
 * at roughly twice the target and finishes with one bilinear pass. Two steps, because the cheap
 * step cannot produce a clean edge and the clean step cannot be afforded at full resolution.
 *
 * <p><b>What it costs, measured on the first real batch of twenty photos.</b> One page of the
 * worklist — the first photo of each of eight rows:
 *
 * <pre>
 *   original    27.18 MB   63.4 Mpx   254 MB of bitmap in the renderer
 *   ?w=200       0.07 MB    0.2 Mpx     1 MB
 * </pre>
 *
 * <p>400x fewer bytes and 407x fewer pixels, paid for with CPU here: serving a 3 MB photo
 * untouched takes about 15 ms, resizing it takes about 180 ms. That is the real trade, and it
 * is worth it — 180 ms of one request thread against a renderer that stopped responding for
 * roughly thirty seconds.
 *
 * <p><b>No cache here, deliberately.</b> The endpoint sends a year of {@code max-age} on objects
 * that never change, so a given browser pays the 180 ms once per photo and never again. An
 * in-process cache would help only the second CSR to open the same row, and whether that is
 * common enough to be worth the heap is exactly the sort of thing this project measures before
 * building. If the worklist ever feels slow on first load, the number to look at is this one.
 */
final class Thumbnails {

    private static final Logger log = LoggerFactory.getLogger(Thumbnails.class);

    /** JPEG quality for the output. High enough that 84px of truck still reads as that truck. */
    private static final float QUALITY = 0.82f;

    private Thumbnails() {
    }

    /**
     * A JPEG no wider than {@code maxWidth}, or {@code null} to say "send the original".
     *
     * <p>Null rather than an exception for every reason a resize might not happen — the image is
     * already small enough, the bytes are not an image this JVM can read, the decode failed. A
     * thumbnail is a convenience; failing the request over one would replace a slow list with a
     * broken one.
     */
    static byte[] scaleToWidth(byte[] source, int maxWidth) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            if (in == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= maxWidth) {
                    return null;
                }

                // Decode at about twice the target so the bilinear pass below has pixels to
                // average. Clamped at 1 because a step of 0 is an IllegalArgumentException and a
                // target wider than half the source would ask for one.
                int step = Math.max(1, width / Math.max(1, maxWidth * 2));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage decoded = reader.read(0, param);

                int targetWidth = Math.min(maxWidth, decoded.getWidth());
                int targetHeight = Math.max(1, Math.round(
                        targetWidth * (float) height / (float) width));
                return encode(resize(decoded, targetWidth, targetHeight));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            // A corrupt or exotic photo is not a reason to fail the worklist. The original still
            // goes out; it is merely large.
            log.warn("Could not build a {}px thumbnail, sending the original: {}",
                    maxWidth, e.toString());
            return null;
        }
    }

    /**
     * TYPE_INT_RGB, always.
     *
     * <p>Not the source's own type: the JPEG writer cannot encode an alpha channel and throws
     * rather than dropping it, so a PNG upload would fail here and nowhere else. Flattening to
     * RGB costs nothing on a photograph, which is what all of these are.
     */
    private static BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
