package autorequesttuar;

/**
 * Immutable description of one downloadable application release.
 */
final class UpdateManifest {

    private final String version;
    private final String downloadUrl;
    private final String sha256;
    private final String notes;

    UpdateManifest(
            String version,
            String downloadUrl,
            String sha256,
            String notes) {
        this.version = safe(version);
        this.downloadUrl = safe(downloadUrl);
        this.sha256 = safe(sha256);
        this.notes = safe(notes);
    }

    String getVersion() {
        return version;
    }

    String getDownloadUrl() {
        return downloadUrl;
    }

    String getSha256() {
        return sha256;
    }

    String getNotes() {
        return notes;
    }

    boolean isValid() {
        return version.matches("\\d+\\.\\d+\\.\\d+")
                && !downloadUrl.isEmpty()
                && sha256.matches("(?i)[0-9a-f]{64}");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
