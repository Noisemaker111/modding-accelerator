package ${package}.loader.api.metadata;

import java.util.Map;

/** Fabric Loader's net.fabricmc.loader.api.metadata.Person: a name from neoforge.mods.toml's authors line, with no contact. */
public final class Person {

    private final String name;

    Person(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public ContactInformation getContact() {
        return new ContactInformation(Map.of());
    }
}
