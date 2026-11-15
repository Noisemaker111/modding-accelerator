package ${package}.loader.api.metadata;

import java.util.Map;
import java.util.Optional;

/** Fabric Loader's net.fabricmc.loader.api.metadata.ContactInformation: homepage (displayURL) and issues (issueTrackerURL). */
public final class ContactInformation {

    public static final ContactInformation EMPTY = new ContactInformation(Map.of());

    private final Map<String, String> values;

    ContactInformation(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    public Map<String, String> asMap() {
        return values;
    }
}
