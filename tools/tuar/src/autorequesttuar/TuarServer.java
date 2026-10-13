package autorequesttuar;

/**
 * Validates the TUAR IPv4 address and builds the application base URL.
 */
final class TuarServer {

    static final String DEFAULT_IP = "10.50.64.186";

    private TuarServer() {
    }

    static String normalizeIp(String input) {
        String value = input == null ? "" : input.trim();
        if (value.regionMatches(true, 0, "http://", 0, 7)) {
            value = value.substring(7);
        } else if (value.regionMatches(true, 0, "https://", 0, 8)) {
            value = value.substring(8);
        }

        int slash = value.indexOf('/');
        if (slash >= 0) {
            String path = value.substring(slash);
            if (!"/".equals(path)
                    && !"/tuar".equalsIgnoreCase(path)
                    && !"/tuar/".equalsIgnoreCase(path)) {
                throw invalidIp(input);
            }
            value = value.substring(0, slash);
        }

        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            throw invalidIp(input);
        }

        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < parts.length; index++) {
            if (!parts[index].matches("\\d{1,3}")) {
                throw invalidIp(input);
            }
            int number;
            try {
                number = Integer.parseInt(parts[index]);
            } catch (NumberFormatException ex) {
                throw invalidIp(input);
            }
            if (number < 0 || number > 255) {
                throw invalidIp(input);
            }
            if (index > 0) {
                normalized.append('.');
            }
            normalized.append(number);
        }
        return normalized.toString();
    }

    static String baseUrl(String input) {
        return "http://" + normalizeIp(input) + "/tuar";
    }

    private static IllegalArgumentException invalidIp(String input) {
        return new IllegalArgumentException(
                "Enter a valid TUAR IPv4 address, for example "
                + DEFAULT_IP + ".");
    }
}
