package ${package}.loader.api.metadata;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.neoforged.neoforgespi.language.IModInfo;
import ${package}.loader.api.Version;

/**
 * Fabric Loader's net.fabricmc.loader.api.metadata.ModMetadata read from a NeoForge mod's neoforge.mods.toml: its id
 * (the Fabric id a converted mod keeps, modproperties fabricId, else NeoForge's), name, description, version, authors
 * (the authors line, split at commas), license, logo, and the custom values a converted mod keeps as JSON in
 * modproperties fabric.custom. getType is "neoforge"; a mod has no Fabric dependency list here, so getDependencies is empty.
 */
public final class ModMetadata {

    private final IModInfo info;

    public ModMetadata(IModInfo info) {
        this.info = info;
    }

    public String getType() {
        return "neoforge";
    }

    public String getId() {
        Object fabricId = info.getModProperties().get("fabricId");
        return fabricId == null ? info.getModId() : String.valueOf(fabricId);
    }

    /** The side a converted mod's fabric.mod.json named (kept in modproperties fabricEnvironment), else every side. */
    public ModEnvironment getEnvironment() {
        Object environment = info.getModProperties().get("fabricEnvironment");
        if ("client".equals(environment)) return ModEnvironment.CLIENT;
        if ("server".equals(environment)) return ModEnvironment.SERVER;
        return ModEnvironment.UNIVERSAL;
    }

    public Collection<String> getProvides() {
        return List.of();
    }

    public Version getVersion() {
        try {
            return Version.parse(info.getVersion().toString());
        } catch (${package}.loader.api.VersionParsingException e) {
            throw new IllegalStateException(e);
        }
    }

    public String getName() {
        return info.getDisplayName();
    }

    public String getDescription() {
        return info.getDescription();
    }

    public Collection<Person> getAuthors() {
        List<Person> out = new ArrayList<>();
        Optional<Object> authors = info.getConfig().getConfigElement("authors");
        if (authors.isPresent()) for (String name : String.valueOf(authors.get()).split("\\s*,\\s*")) if (!name.isBlank()) out.add(new Person(name));
        return out;
    }

    public Collection<Person> getContributors() {
        List<Person> out = new ArrayList<>();
        Optional<Object> credits = info.getConfig().getConfigElement("credits");
        if (credits.isPresent()) for (String name : String.valueOf(credits.get()).split("\\s*,\\s*")) if (!name.isBlank()) out.add(new Person(name));
        return out;
    }

    public ContactInformation getContact() {
        Map<String, String> contact = new LinkedHashMap<>();
        info.getModURL().ifPresent(url -> contact.put("homepage", url.toString()));
        info.getOwningFile().getConfig().getConfigElement("issueTrackerURL").ifPresent(url -> contact.put("issues", String.valueOf(url)));
        return new ContactInformation(contact);
    }

    public Collection<String> getLicense() {
        String license = info.getOwningFile().getLicense();
        return license == null || license.isBlank() ? List.of() : List.of(license);
    }

    public Optional<String> getIconPath(int size) {
        return info.getLogoFile();
    }

    public boolean containsCustomValue(String key) {
        return custom().has(key);
    }

    public CustomValue getCustomValue(String key) {
        JsonElement value = custom().get(key);
        return value == null ? null : CustomValue.of(value);
    }

    public Map<String, CustomValue> getCustomValues() {
        Map<String, CustomValue> out = new LinkedHashMap<>();
        for (var entry : custom().entrySet()) out.put(entry.getKey(), CustomValue.of(entry.getValue()));
        return out;
    }

    private JsonObject custom() {
        Object text = info.getModProperties().get("fabric.custom");
        if (text == null) return new JsonObject();
        JsonElement parsed = JsonParser.parseString(String.valueOf(text));
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
    }
}
