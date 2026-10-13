package autorequesttuar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class FormOptions {

    private final List<OptionItem> requestTypes;
    private final List<OptionItem> services;
    private final List<OptionItem> purposes;
    private final List<OptionItem> accountTypes;
    private final List<OptionItem> expiryOptions;

    FormOptions(
            List<OptionItem> requestTypes,
            List<OptionItem> services,
            List<OptionItem> purposes,
            List<OptionItem> accountTypes,
            List<OptionItem> expiryOptions) {
        this.requestTypes = immutableCopy(requestTypes);
        this.services = immutableCopy(services);
        this.purposes = immutableCopy(purposes);
        this.accountTypes = immutableCopy(accountTypes);
        this.expiryOptions = immutableCopy(expiryOptions);
    }

    List<OptionItem> getRequestTypes() {
        return requestTypes;
    }

    List<OptionItem> getServices() {
        return services;
    }

    List<OptionItem> getPurposes() {
        return purposes;
    }

    List<OptionItem> getAccountTypes() {
        return accountTypes;
    }

    List<OptionItem> getExpiryOptions() {
        return expiryOptions;
    }

    private static List<OptionItem> immutableCopy(List<OptionItem> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
