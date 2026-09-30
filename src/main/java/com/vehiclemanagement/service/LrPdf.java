package com.vehiclemanagement.service;

import com.vehiclemanagement.config.VehicleManagementProperties;
import com.vehiclemanagement.domain.LorryReceipt;
import com.vehiclemanagement.domain.LrStatus;
import com.vehiclemanagement.exception.ApiException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The lorry slip, drawn to the firm's own stationery design.
 *
 * <p>The layout is taken from {@code Tezzz_Transport_Lorry_Slip.xlsx}: an eight-column grid,
 * a logo and letterhead, a dark LORRY SLIP band, seven rows of paired label/value boxes, a
 * CHARGES block, a declaration and three signature lines. Column widths, row heights and the
 * three brand colours are the spreadsheet's, converted from its units — see {@link #COL_UNITS}
 * and {@link #UNIT}.
 *
 * <p><b>One slip per page.</b> An earlier version printed two copies on a sheet; the firm's own
 * design is a single slip carrying all three signatures, so that invention is gone.
 *
 * <p><b>Everything variable comes off the receipt row, never from a master.</b> A slip printed
 * a year later has to say what the customer's copy says, even though the truck has been sold
 * and the consignor renamed. The letterhead is the one exception and it is configuration, not
 * constants — see {@code VehicleManagementProperties.Company}.
 *
 * <p>Standard-14 fonts, so nothing is embedded and no font file has to exist on the server.
 * The trade is that only WinAnsi characters survive; {@link #ascii} substitutes the rest, and
 * it is why amounts read "Rs" rather than the rupee sign.
 */
@Service
public class LrPdf {

    private static final Logger log = LoggerFactory.getLogger(LrPdf.class);

    private static final PDFont BODY = PDType1Font.HELVETICA;
    private static final PDFont BOLD = PDType1Font.HELVETICA_BOLD;
    private static final PDFont ITALIC = PDType1Font.HELVETICA_OBLIQUE;

    /** The spreadsheet's three colours. */
    private static final Color INK = new Color(0x20, 0x20, 0x20);
    private static final Color GOLD = new Color(0xC5, 0x8A, 0x00);
    private static final Color WASH = new Color(0xF7, 0xF7, 0xF7);
    private static final Color RULE = new Color(0xCC, 0xCC, 0xCC);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");

    /** Columns A–H, in the spreadsheet's character units. */
    private static final int[] COL_UNITS = {16, 18, 16, 18, 16, 16, 16, 16};
    private static final int TOTAL_UNITS = 132;

    private static final float MARGIN = 28;
    private static final float LEFT = MARGIN;
    private static final float RIGHT = PDRectangle.A4.getWidth() - MARGIN;
    /** Points per spreadsheet column unit, so the grid keeps the template's proportions. */
    private static final float UNIT = (RIGHT - LEFT) / TOTAL_UNITS;

    /** Row height on the body rows. The spreadsheet's 24, and its units are already points. */
    private static final float ROW = 24;

    private final VehicleManagementProperties.Company company;

    public LrPdf(VehicleManagementProperties properties) {
        this.company = properties.getCompany();
    }

    public byte[] render(LorryReceipt lr) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream c = new PDPageContentStream(doc, page)) {
                draw(doc, c, lr);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ApiException(500, "Could not build the PDF.");
        }
    }

    private void draw(PDDocument doc, PDPageContentStream c, LorryReceipt lr) throws IOException {
        float y = PDRectangle.A4.getHeight() - MARGIN;

        y = letterhead(doc, c, y);
        y = band(c, y, "LORRY SLIP", 15, 27);

        if (lr.getStatus() == LrStatus.CANCELLED) {
            // On the face of the slip. A cancelled receipt that prints identically to a live
            // one is the reason cancelling keeps the row instead of deleting it.
            y -= 4;
            box(c, LEFT, y - 20, RIGHT - LEFT, 20, new Color(0xFD, 0xEA, 0xEA), RULE);
            text(c, BOLD, 11, new Color(0xA3, 0x20, 0x20), LEFT + 8, y - 14,
                    "CANCELLED" + (lr.getCancelReason() == null || lr.getCancelReason().isBlank()
                            ? "" : "  -  " + lr.getCancelReason()));
            y -= 24;
        }

        y -= 6;
        y = pair(c, y, "Slip No.", lr.getLrNumber(),
                "Date", lr.getLrDate() == null ? "" : lr.getLrDate().format(DATE));
        // The party and its place share one box on the sheet. Stacked rather than joined with
        // a comma: "Bagru Cement Works, Ludhiana" does not fit a quarter-width cell, and
        // clipping it loses the destination -- which is the half nobody can guess.
        y = stackedPair(c, y,
                "Consignor / From", dash(lr.getConsignorName()), dash(lr.getFromPlace()),
                "Consignee / To", dash(lr.getConsigneeName()), dash(lr.getToPlace()));
        y = pair(c, y, "From Mobile", dash(lr.getConsignorMobile()),
                "To Mobile", dash(lr.getConsigneeMobile()));
        y = pair(c, y, "Goods / Description", dash(lr.getGoodsDescription()),
                "Weight", lr.getWeightKg() == null ? "-" : plain(lr.getWeightKg()) + " kg");
        y = pair(c, y, "No. of Packages",
                lr.getPackages() == null ? "-" : String.valueOf(lr.getPackages()),
                "Vehicle Type", dash(lr.getVehicleType()));
        y = pair(c, y, "Truck No.", dash(lr.getVehicleNumber()),
                "Driver Name", dash(lr.getDriverName()));
        y = pair(c, y, "Driver Mobile", dash(lr.getDriverMobile()),
                "Special Instructions", dash(lr.getSpecialInstructions()));

        y -= 14;
        y = band(c, y, "CHARGES", 11, ROW);
        y = pair(c, y, "Freight Charges", rupees(lr.getFreightCharges()),
                "Loading Charges", rupees(lr.getLoadingCharges()));
        y = pair(c, y, "Unloading Charges", rupees(lr.getUnloadingCharges()),
                "Other Charges", rupees(lr.getOtherCharges()));
        // TOTAL / ADVANCE / BALANCE span six columns for the label and two for the figure,
        // as they do on the sheet.
        y = wideRow(c, y, "TOTAL", rupees(lr.getTotalCharges()), true);
        y = wideRow(c, y, "ADVANCE", rupees(lr.getAdvance()), false);
        y = wideRow(c, y, "BALANCE", rupees(lr.getBalance()), true);

        y -= 16;
        y = band(c, y, "DECLARATION", 11, ROW);
        float declarationHeight = 40;
        box(c, LEFT, y - declarationHeight, RIGHT - LEFT, declarationHeight, WASH, RULE);
        float ty = y - 16;
        for (String line : wrap(company.getDeclaration(), BODY, 9, RIGHT - LEFT - 16)) {
            text(c, BODY, 9, INK, LEFT + 8, ty, line);
            ty -= 12;
        }
        y -= declarationHeight + 34;

        // ── three signatures across the foot ────────────────────────────────
        float third = (RIGHT - LEFT) / 3;
        String[] captions = {"Consignor Signature", "Driver Signature", company.getName()};
        for (int i = 0; i < 3; i++) {
            float x = LEFT + i * third;
            c.setStrokingColor(INK);
            line(c, x, y, x + third - 24, y);
            text(c, i == 2 ? BOLD : BODY, 9, i == 2 ? GOLD : INK, x, y - 12, captions[i]);
        }

        y -= 34;
        centred(c, BOLD, 10, GOLD, y, company.getSlogan());
    }

    /** Logo on the left, the firm's details on the right, as rows 1–5 of the sheet. */
    private float letterhead(PDDocument doc, PDPageContentStream c, float y) throws IOException {
        float top = y;
        float textX = LEFT + colX(3) - colX(0);   // column D, where the sheet puts the name

        PDImageXObject logo = logo(doc);
        if (logo != null) {
            float boxW = textX - LEFT - 12;
            float boxH = 74;
            float scale = Math.min(boxW / logo.getWidth(), boxH / logo.getHeight());
            float w = logo.getWidth() * scale;
            float h = logo.getHeight() * scale;
            c.drawImage(logo, LEFT + (boxW - w) / 2, top - h, w, h);
        }

        float ty = top - 22;
        text(c, BOLD, 22, INK, textX, ty, company.getName());
        ty -= 18;
        text(c, ITALIC, 10, GOLD, textX, ty, company.getTagline());
        ty -= 15;
        text(c, BODY, 9, INK, textX, ty,
                "Proprietor: " + company.getProprietor() + "   |   Mob.: " + company.getMobile());
        ty -= 13;
        text(c, BODY, 9, INK, textX, ty, company.getAddress());

        float bottom = Math.min(ty - 10, top - 84);
        c.setStrokingColor(GOLD);
        c.setLineWidth(1.2f);
        line(c, LEFT, bottom, RIGHT, bottom);
        c.setLineWidth(0.6f);
        return bottom - 10;
    }

    /**
     * The logo, or null.
     *
     * <p>A missing or unreadable file prints the slip without it rather than failing the
     * download. The logo is letterhead; the consignment is the document, and losing the second
     * for the first would be the wrong way round.
     */
    private PDImageXObject logo(PDDocument doc) {
        String path = company.getLogo();
        if (path == null || path.isBlank()) {
            return null;
        }
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            BufferedImage image = ImageIO.read(in);
            return image == null ? null : LosslessFactory.createFromImage(doc, image);
        } catch (IOException | RuntimeException e) {
            log.warn("lorry-slip logo '{}' could not be read; printing without it", path, e);
            return null;
        }
    }

    /** A full-width dark band with white text, as rows 6, 15 and 22. */
    private float band(PDPageContentStream c, float y, String label, float size, float height)
            throws IOException {
        box(c, LEFT, y - height, RIGHT - LEFT, height, INK, INK);
        text(c, BOLD, size, Color.WHITE, LEFT + 10, y - height + (height - size) / 2 + 2, label);
        return y - height;
    }

    /** Two label/value boxes across the eight columns: A:B C:D and E:F G:H. */
    private float pair(PDPageContentStream c, float y, String leftLabel, String leftValue,
                       String rightLabel, String rightValue) throws IOException {
        float top = y - ROW;
        cell(c, colX(0), top, colX(2) - colX(0), leftLabel, true);
        cell(c, colX(2), top, colX(4) - colX(2), leftValue, false);
        cell(c, colX(4), top, colX(6) - colX(4), rightLabel, true);
        cell(c, colX(6), top, RIGHT - colX(6), rightValue, false);
        return top;
    }

    /** As {@link #pair}, but each value box carries a name over its place. */
    private float stackedPair(PDPageContentStream c, float y,
                              String leftLabel, String leftName, String leftPlace,
                              String rightLabel, String rightName, String rightPlace)
            throws IOException {
        float top = y - ROW;
        cell(c, colX(0), top, colX(2) - colX(0), leftLabel, true);
        stacked(c, colX(2), top, colX(4) - colX(2), leftName, leftPlace);
        cell(c, colX(4), top, colX(6) - colX(4), rightLabel, true);
        stacked(c, colX(6), top, RIGHT - colX(6), rightName, rightPlace);
        return top;
    }

    private void stacked(PDPageContentStream c, float x, float bottom, float width,
                         String first, String second) throws IOException {
        box(c, x, bottom, width, ROW, Color.WHITE, RULE);
        text(c, BODY, 9.5f, INK, x + 6, bottom + 13,
                clip(first, BODY, 9.5f, width - 12));
        text(c, BODY, 8, GOLD, x + 6, bottom + 4, clip(second, BODY, 8, width - 12));
    }

    /** A six-column label with a two-column figure: TOTAL, ADVANCE, BALANCE. */
    private float wideRow(PDPageContentStream c, float y, String label, String value,
                          boolean strong) throws IOException {
        float top = y - ROW;
        cell(c, colX(0), top, colX(6) - colX(0), label, true);
        box(c, colX(6), top, RIGHT - colX(6), ROW, strong ? WASH : Color.WHITE, RULE);
        rightText(c, strong ? BOLD : BODY, 11, INK, RIGHT - 8, top + 8, value);
        return top;
    }

    /** One boxed cell. Labels get the wash background the sheet gives them. */
    private void cell(PDPageContentStream c, float x, float bottom, float width, String value,
                      boolean label) throws IOException {
        box(c, x, bottom, width, ROW, label ? WASH : Color.WHITE, RULE);
        String clipped = clip(value, label ? BOLD : BODY, label ? 9 : 10, width - 12);
        text(c, label ? BOLD : BODY, label ? 9 : 10, INK, x + 6, bottom + 8, clipped);
    }

    private void box(PDPageContentStream c, float x, float bottom, float width, float height,
                     Color fill, Color stroke) throws IOException {
        c.setNonStrokingColor(fill);
        c.addRect(x, bottom, width, height);
        c.fill();
        c.setStrokingColor(stroke);
        c.addRect(x, bottom, width, height);
        c.stroke();
    }

    /** Left edge of spreadsheet column {@code index} (0 = A), in points. */
    private static float colX(int index) {
        int units = 0;
        for (int i = 0; i < index; i++) {
            units += COL_UNITS[i];
        }
        return LEFT + units * UNIT;
    }

    private void text(PDPageContentStream c, PDFont font, float size, Color colour, float x,
                      float y, String value) throws IOException {
        c.setNonStrokingColor(colour);
        c.beginText();
        c.setFont(font, size);
        c.newLineAtOffset(x, y);
        c.showText(ascii(value));
        c.endText();
    }

    private void rightText(PDPageContentStream c, PDFont font, float size, Color colour,
                           float rightEdge, float y, String value) throws IOException {
        String safe = ascii(value);
        text(c, font, size, colour, rightEdge - width(safe, font, size), y, safe);
    }

    private void centred(PDPageContentStream c, PDFont font, float size, Color colour, float y,
                         String value) throws IOException {
        String safe = ascii(value);
        float x = LEFT + ((RIGHT - LEFT) - width(safe, font, size)) / 2;
        text(c, font, size, colour, x, y, safe);
    }

    private void line(PDPageContentStream c, float x1, float y1, float x2, float y2)
            throws IOException {
        c.moveTo(x1, y1);
        c.lineTo(x2, y2);
        c.stroke();
    }

    private static float width(String value, PDFont font, float size) {
        try {
            return font.getStringWidth(value) / 1000 * size;
        } catch (IOException e) {
            return value.length() * size * 0.5f;
        }
    }

    /**
     * Truncate to the cell rather than letting the text run over the box beside it.
     *
     * <p>A consignee name longer than its box is ordinary, not exceptional, and text spilling
     * across a ruled form is worse than an ellipsis — it makes the neighbouring value
     * unreadable too.
     */
    private static String clip(String value, PDFont font, float size, float maxWidth) {
        String safe = ascii(value);
        if (width(safe, font, size) <= maxWidth) {
            return safe;
        }
        StringBuilder out = new StringBuilder();
        for (char ch : safe.toCharArray()) {
            if (width(out.toString() + ch + "...", font, size) > maxWidth) {
                break;
            }
            out.append(ch);
        }
        return out + "...";
    }

    /** Greedy wrap for the declaration, the one paragraph on the slip. */
    private static List<String> wrap(String value, PDFont font, float size, float maxWidth) {
        List<String> lines = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : ascii(value).split("\\s+")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (width(candidate, font, size) > maxWidth && !current.isEmpty()) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    /**
     * Keep only what a standard-14 font can encode.
     *
     * <p>PDFBox throws on the first character WinAnsi cannot represent, and it throws at
     * <i>draw</i> time — so one consignee whose name carries a Devanagari character or a curly
     * apostrophe pasted from Word would fail the whole download with a 500, for a slip that is
     * otherwise fine. A name with a "?" in it is still a usable document; a 500 is not.
     *
     * <p>The rupee sign is the reason this matters most: it is not in WinAnsi at all, so
     * amounts read "Rs".
     */
    private static String ascii(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (char ch : value.toCharArray()) {
            if (ch == '’' || ch == '‘') {
                out.append('\'');
            } else if (ch == '“' || ch == '”') {
                out.append('"');
            } else if (ch == '–' || ch == '—' || ch == '•') {
                out.append('-');
            } else if (ch >= 32 && ch < 127) {
                out.append(ch);
            } else {
                out.append('?');
            }
        }
        return out.toString();
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String rupees(BigDecimal value) {
        return "Rs " + plain(value);
    }

    /** Two decimals, no grouping — a thousands separator is a locale argument nobody needs. */
    private static String plain(BigDecimal value) {
        return value == null ? "0.00"
                : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
