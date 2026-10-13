package autorequesttuar;

/**
 * A stable web form option. The label is shown to the user while the value is
 * used when selecting the corresponding HTML option.
 */
final class OptionItem {

    private final String value;
    private final String label;

    OptionItem(String value, String label) {
        this.value = value == null ? "" : value;
        this.label = label == null ? "" : label.trim();
    }

    String getValue() {
        return value;
    }

    String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
