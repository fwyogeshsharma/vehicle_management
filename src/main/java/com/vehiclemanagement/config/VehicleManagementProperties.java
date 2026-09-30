package com.vehiclemanagement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Settings under {@code vehicle-management:} in application.yml. */
@ConfigurationProperties(prefix = "vehicle-management")
public class VehicleManagementProperties {

    /**
     * Seed states, cities and the default body types at start-up. Idempotent, so leaving it on
     * costs two queries once everything is present — but a container that must not touch data
     * can turn it off.
     */
    private boolean seedReferenceData = true;

    /**
     * Load believable sample data at start-up. Development only, off by default, and the seeder
     * refuses a database that already has vehicles.
     */
    private boolean seedSampleData = false;

    private final Jwt jwt = new Jwt();
    private final Cors cors = new Cors();
    private final Admin admin = new Admin();
    private final Intake intake = new Intake();
    private final Company company = new Company();

    public boolean isSeedReferenceData() { return seedReferenceData; }
    public void setSeedReferenceData(boolean v) { this.seedReferenceData = v; }
    public boolean isSeedSampleData() { return seedSampleData; }
    public void setSeedSampleData(boolean v) { this.seedSampleData = v; }
    public Jwt getJwt() { return jwt; }
    public Cors getCors() { return cors; }
    public Admin getAdmin() { return admin; }
    public Intake getIntake() { return intake; }
    public Company getCompany() { return company; }

    /** Bearer-token signing. */
    public static class Jwt {

        /**
         * The HMAC signing key. <b>No default, on purpose.</b>
         *
         * <p>A development-convenience default is how a well-known key reaches production, and
         * anyone holding it can mint a token for any account. Boot fails with a message naming
         * this setting rather than starting with a guessable key — see SecurityConfig.
         */
        private String secret;

        /** How long a token lasts. There is no refresh token; signing in again is the refresh. */
        private int ttlMinutes = 480;

        public String getSecret() { return secret; }
        public void setSecret(String v) { this.secret = v; }
        public int getTtlMinutes() { return ttlMinutes; }
        public void setTtlMinutes(int v) { this.ttlMinutes = v; }
    }

    /** Which browser origins may call this API. The UI is a separate project on its own port. */
    public static class Cors {

        /**
         * Exact origins, never a wildcard.
         *
         * <p>The default is the usual Vite dev server. Set it explicitly for anything deployed.
         */
        private List<String> allowedOrigins = List.of("http://localhost:5173");

        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> v) { this.allowedOrigins = v; }
    }

    /** Photos from the field. The OCR worker that reads them is configured separately. */
    /**
     * Who the printed lorry slip is from.
     *
     * <p>Configuration, not constants in the renderer. These appear on a legal document and
     * change when the firm moves office or the proprietor does — a redeploy is the wrong unit
     * of change for an address, and tts keeps the same details in {@code tts.company.*} for
     * the same reason.
     *
     * <p>The defaults are the ones on the slip this design was taken from, so a fresh checkout
     * prints something recognisable rather than "null".
     */
    public static class Company {

        private String name = "TEZZZ TRANSPORT";
        private String tagline = "DRIVEN BY COMMITMENT";
        private String proprietor = "Tejpal Singh";
        private String mobile = "087690-32586";
        private String address = "13/14 Gopal Nagar, VKI, Jaipur-302013";
        private String slogan = "SAFE  -  FAST  -  RELIABLE";
        /** Classpath resource. Blank prints the slip without a logo rather than failing. */
        private String logo = "lr-logo.png";
        private String declaration = "I/We declare that the above details are correct and the "
                + "goods are handed over for transportation in good condition.";

        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public String getTagline() { return tagline; }
        public void setTagline(String v) { this.tagline = v; }
        public String getProprietor() { return proprietor; }
        public void setProprietor(String v) { this.proprietor = v; }
        public String getMobile() { return mobile; }
        public void setMobile(String v) { this.mobile = v; }
        public String getAddress() { return address; }
        public void setAddress(String v) { this.address = v; }
        public String getSlogan() { return slogan; }
        public void setSlogan(String v) { this.slogan = v; }
        public String getLogo() { return logo; }
        public void setLogo(String v) { this.logo = v; }
        public String getDeclaration() { return declaration; }
        public void setDeclaration(String v) { this.declaration = v; }
    }

    public static class Intake {

        /**
         * Which backend photo bytes go to: {@code local} or {@code gcs}.
         *
         * <p>Same two names FreightDesk uses for the same two backends. An unrecognised value
         * fails startup rather than falling back — see ImageStoreConfig.
         */
        private String storageBackend = "local";

        /** Where photo bytes are written by the {@code local} backend. Ignored by {@code gcs}. */
        private String storageDir = "./uploads";

        /** Required by the {@code gcs} backend, ignored by {@code local}. */
        private String gcsBucket = "";

        /** Optional key prefix inside the bucket, so one bucket can serve several things. */
        private String gcsPrefix = "";

        /** Most photos one upload may carry. FreightDesk settled on the same number. */
        private int maxImages = 5;

        /**
         * Largest single photo accepted, in megabytes.
         *
         * <p>Checked here as well as by {@code spring.servlet.multipart.max-file-size} so the
         * refusal is a sentence a field executive can act on rather than a framework page.
         */
        private int maxImageMb = 12;

        public String getStorageBackend() { return storageBackend; }
        public void setStorageBackend(String v) { this.storageBackend = v; }
        public String getStorageDir() { return storageDir; }
        public void setStorageDir(String v) { this.storageDir = v; }
        public String getGcsBucket() { return gcsBucket; }
        public void setGcsBucket(String v) { this.gcsBucket = v; }
        public String getGcsPrefix() { return gcsPrefix; }
        public void setGcsPrefix(String v) { this.gcsPrefix = v; }
        public int getMaxImages() { return maxImages; }
        public void setMaxImages(int v) { this.maxImages = v; }
        public int getMaxImageMb() { return maxImageMb; }
        public void setMaxImageMb(int v) { this.maxImageMb = v; }
    }

    /**
     * The first administrator, created at start-up only when no one can already administer the
     * system.
     *
     * <p>All three have no default. With any of them missing the bootstrap is skipped and says
     * so in the log — a built-in account with a known password would be worse than no account.
     */
    public static class Admin {

        private String username;
        private String password;
        private String mobile;
        private String name = "Administrator";

        public String getUsername() { return username; }
        public void setUsername(String v) { this.username = v; }
        public String getPassword() { return password; }
        public void setPassword(String v) { this.password = v; }
        public String getMobile() { return mobile; }
        public void setMobile(String v) { this.mobile = v; }
        public String getName() { return name; }
        public void setName(String v) { this.name = v; }

        public boolean isComplete() {
            return notBlank(username) && notBlank(password) && notBlank(mobile);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }
}
