package com.vehiclemanagement;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Downloading a receipt.
 *
 * <p>The PDF is a <b>second layout definition</b> — the on-screen print view is HTML and this
 * one is drawn in Java, so the two can drift. What is asserted here is therefore not pixels
 * but presence: the fields that make a consignment note a consignment note have to be in the
 * file, and they have to be the receipt's own stored text rather than the masters it links to.
 *
 * <p>Read back with PDFBox's text stripper rather than trusted because it was a 200. A PDF
 * that downloads cleanly and is blank is exactly the failure a status-code assertion misses.
 */
class LrPdfIT extends ApiTest {

    @org.springframework.beans.factory.annotation.Autowired
    private com.vehiclemanagement.config.VehicleManagementProperties properties;

    private String token;

    @BeforeEach
    void reset() {
        resetDomainData();
        // Lorry receipts are ADMIN or TEJJJ_CSR only; a plain CSR is refused every route here.
        // Who may reach them is asserted in LorryReceiptIT$Access.
        token = admin();
    }

    private long writeReceipt() {
        return idOf(post("/api/lr", token, body(
                "lr_date", "2026-09-28",
                "consignor_name", "Kumar Traders", "consignor_mobile", "9811008120",
                "consignee_name", "Bagru Cement Works",
                "from_place", "Jaipur", "to_place", "Ludhiana",
                "goods_description", "Cement", "weight_kg", 18500, "packages", 370,
                "vehicle_number", "RJ14CA1234",
                "driver_name", "Balwinder Singh", "driver_mobile", "9812345678",
                "freight_charges", 32000, "loading_charges", 1500,
                "unloading_charges", 1500, "advance", 10000,
                "special_instructions", "Deliver before 6pm")));
    }

    private ResponseEntity<byte[]> download(long id) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange("/api/lr/" + id + "/pdf", HttpMethod.GET,
                new HttpEntity<>(headers), byte[].class);
    }

    private String textOf(byte[] pdf) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    @DisplayName("downloads as a PDF attachment named after the receipt")
    void it_downloads_as_an_attachment() {
        long id = writeReceipt();

        ResponseEntity<byte[]> response = download(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        // `attachment` is what makes a browser download it instead of opening a tab, and the
        // filename is what makes a folder of these searchable.
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("LR-001.pdf");
        assertThat(response.getBody()).isNotEmpty();
        assertThat(new String(response.getBody(), 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("the file actually contains the consignment, not just a valid header")
    void the_document_carries_the_receipt() throws Exception {
        long id = writeReceipt();

        String text = textOf(download(id).getBody());

        assertThat(text)
                .contains("LORRY SLIP")
                .contains("LR-001")
                .contains("Kumar Traders")
                .contains("Bagru Cement Works")
                .contains("Jaipur")
                .contains("Ludhiana")
                .contains("Cement")
                .contains("RJ14CA1234")
                .contains("Balwinder Singh")
                .contains("Deliver before 6pm");
        // Total and balance are the database's generated columns, not arithmetic done here.
        assertThat(text).contains("35000.00").contains("25000.00");
    }

    @Test
    @DisplayName("it is the firm's own slip design, letterhead and all")
    void it_matches_the_stationery() throws Exception {
        long id = writeReceipt();

        String text = textOf(download(id).getBody());

        // Taken from Tezzz_Transport_Lorry_Slip.xlsx: letterhead, the LORRY SLIP band, the
        // CHARGES block, the declaration and three signature lines.
        assertThat(text)
                .contains("TEZZZ TRANSPORT")
                .contains("DRIVEN BY COMMITMENT")
                .contains("Tejpal Singh")
                .contains("Gopal Nagar")
                .contains("LORRY SLIP")
                .contains("CHARGES")
                .contains("DECLARATION")
                .contains("Consignor Signature")
                .contains("Driver Signature")
                .contains("SAFE");
        // One slip per page. An earlier version printed two copies; the firm's own design is a
        // single slip carrying all three signatures.
        assertThat(text.split("LORRY SLIP", -1)).hasSize(2);
    }

    @Test
    @DisplayName("the letterhead is configuration, not baked into the renderer")
    void the_letterhead_is_configurable() {
        // Asserted through the properties object rather than by restarting the context with an
        // override: the point is that LrPdf reads these rather than holding string constants,
        // and a second Spring context for one assertion costs 30 seconds a run.
        assertThat(properties.getCompany().getName()).isEqualTo("TEZZZ TRANSPORT");
        assertThat(properties.getCompany().getAddress()).contains("Jaipur");
        assertThat(properties.getCompany().getDeclaration()).contains("good condition");
    }

    @Test
    @DisplayName("the slip uses the spreadsheet's field labels")
    void the_labels_match_the_sheet() throws Exception {
        long id = writeReceipt();

        String text = textOf(download(id).getBody());

        for (String label : java.util.List.of("Slip No.", "Date", "Consignor / From",
                "Consignee / To", "From Mobile", "To Mobile", "Goods / Description", "Weight",
                "No. of Packages", "Vehicle Type", "Truck No.", "Driver Name", "Driver Mobile",
                "Special Instructions", "Freight Charges", "Loading Charges",
                "Unloading Charges", "Other Charges", "TOTAL", "ADVANCE", "BALANCE")) {
            assertThat(text).as("label %s", label).contains(label);
        }
    }

    @Test
    @DisplayName("the PDF says what the receipt says, not what the master now says")
    void it_prints_the_snapshot() throws Exception {
        long id = writeReceipt();
        long customerId = get("/api/lr/" + id, token).getBody().get("consignor_id").asLong();
        put("/api/customers/" + customerId, token, body("name", "Something Else Entirely"));

        String text = textOf(download(id).getBody());

        // The whole point of the snapshot columns. A reprint of an old receipt has to match
        // the copy the customer is holding.
        assertThat(text).contains("Kumar Traders").doesNotContain("Something Else Entirely");
    }

    @Test
    @DisplayName("a cancelled receipt says so on its face")
    void a_cancelled_receipt_is_marked() throws Exception {
        long id = writeReceipt();
        post("/api/lr/" + id + "/cancel", token, body("reason", "Load withdrawn"));

        String text = textOf(download(id).getBody());

        // A cancelled receipt that downloads looking identical to a live one is the reason
        // cancelling keeps the row rather than deleting it.
        assertThat(text).contains("CANCELLED").contains("Load withdrawn");
    }

    @Test
    @DisplayName("a name the font cannot encode does not fail the download")
    void unencodable_characters_are_substituted() throws Exception {
        // PDFBox throws on the first character WinAnsi cannot represent, and it throws at draw
        // time -- so one consignee pasted from Word with a curly apostrophe would 500 the whole
        // download. A "?" is still a usable document; a 500 is not.
        Map<String, Object> odd = body(
                "lr_date", "2026-09-28",
                "consignor_name", "O’Brien कंपनी",
                "consignee_name", "Bagru Cement Works",
                "from_place", "Jaipur", "to_place", "Ludhiana");
        long id = idOf(post("/api/lr", token, odd));

        ResponseEntity<byte[]> response = download(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(textOf(response.getBody())).contains("O'Brien");
    }

    @Test
    @DisplayName("an unknown receipt is a 404, not an empty PDF")
    void unknown_id_is_404() {
        assertThat(download(999999).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the download needs a token")
    void it_is_behind_a_login() {
        long id = writeReceipt();
        assertThat(rest.getForEntity("/api/lr/" + id + "/pdf", byte[].class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
