package ${package};

import net.fabricmc.loader.api.Version;

/**
 * FancyModLoader's net.neoforged.fml.i18n.MavenVersionTranslator, as mods use it: a mod's version as text. On Fabric
 * the version is Fabric Loader's (IModInfo.getVersion), written as Fabric Loader writes it.
 */
public class MavenVersionTranslator {

    public static String artifactVersionToString(Version version) {
        return version == null ? "" : version.getFriendlyString();
    }
}
