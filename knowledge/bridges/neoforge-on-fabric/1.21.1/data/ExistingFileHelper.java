package ${package}.data;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;

/**
 * NeoForge 21.1's net.neoforged.neoforge.common.data.ExistingFileHelper, which data providers ask whether a texture or
 * model they name exists. Fabric's data run has no such check: every file counts as existing (as NeoForge's own helper
 * when disabled), and getResource finds none.
 */
public class ExistingFileHelper {

    public interface IResourceType {
        PackType getPackType();

        String getSuffix();

        String getPrefix();
    }

    public static class ResourceType implements IResourceType {
        final PackType packType;
        final String suffix;
        final String prefix;

        public ResourceType(PackType type, String suffix, String prefix) {
            this.packType = type;
            this.suffix = suffix;
            this.prefix = prefix;
        }

        @Override
        public PackType getPackType() {
            return this.packType;
        }

        @Override
        public String getSuffix() {
            return this.suffix;
        }

        @Override
        public String getPrefix() {
            return this.prefix;
        }
    }

    public ExistingFileHelper(Collection<Path> existingPacks, Set<String> existingMods, boolean enable, String assetIndex, File assetsDir) {}

    public boolean exists(ResourceLocation loc, PackType packType) {
        return true;
    }

    public boolean exists(ResourceLocation loc, IResourceType type) {
        return true;
    }

    public boolean exists(ResourceLocation loc, PackType packType, String pathSuffix, String pathPrefix) {
        return true;
    }

    public void trackGenerated(ResourceLocation loc, IResourceType type) {}

    public void trackGenerated(ResourceLocation loc, PackType packType, String pathSuffix, String pathPrefix) {}

    public Resource getResource(ResourceLocation loc, PackType packType, String pathSuffix, String pathPrefix) throws FileNotFoundException {
        throw new FileNotFoundException(loc + " (Fabric's data run reads no existing resources)");
    }

    public Resource getResource(ResourceLocation loc, PackType packType) throws FileNotFoundException {
        throw new FileNotFoundException(loc + " (Fabric's data run reads no existing resources)");
    }

    public List<Resource> getResourceStack(ResourceLocation loc, PackType packType) {
        return List.of();
    }

    public boolean isEnabled() {
        return false;
    }
}
