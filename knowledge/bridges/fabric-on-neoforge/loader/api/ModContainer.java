package ${package}.loader.api;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ${package}.loader.api.metadata.ModMetadata;
import ${package}.loader.api.metadata.ModOrigin;

/**
 * Fabric Loader's net.fabricmc.loader.api.ModContainer for a mod NeoForge loaded: its metadata from neoforge.mods.toml,
 * and its files, read from the mod's jar (or folder in development) through a file system, as Fabric reads them.
 */
public final class ModContainer {

    private final net.neoforged.fml.ModContainer container;

    ModContainer(net.neoforged.fml.ModContainer container) {
        this.container = container;
    }

    /** The NeoForge mod container this one reads. */
    public net.neoforged.fml.ModContainer neoforge() {
        return container;
    }

    public ModMetadata getMetadata() {
        return new ModMetadata(container.getModInfo());
    }

    public List<Path> getRootPaths() {
        List<Path> out = new ArrayList<>();
        var file = container.getModInfo().getOwningFile();
        if (file == null) return out;
        for (Path root : file.getFile().getContents().getContentRoots()) {
            if (Files.isDirectory(root)) out.add(root);
            else try {
                out.add(fileSystem(root).getPath("/"));
            } catch (IOException e) {
                throw new IllegalStateException("Could not open " + root, e);
            }
        }
        return out;
    }

    public Path getRootPath() {
        List<Path> roots = getRootPaths();
        if (roots.isEmpty()) throw new IllegalStateException(container.getModId() + " has no files");
        return roots.get(0);
    }

    public Optional<Path> findPath(String file) {
        for (Path root : getRootPaths()) {
            Path path = root.resolve(file.replace("/", root.getFileSystem().getSeparator()));
            if (Files.exists(path)) return Optional.of(path);
        }
        return Optional.empty();
    }

    /** NeoForge does not say which mod a jar-in-jar mod came in: none here. */
    public Optional<ModContainer> getContainingMod() {
        return Optional.empty();
    }

    public java.util.Collection<ModContainer> getContainedMods() {
        return List.of();
    }

    /** The mod's own file (its jar, or its folders in development). */
    public ModOrigin getOrigin() {
        var file = container.getModInfo().getOwningFile();
        List<Path> paths = file == null ? List.of() : List.of(file.getFile().getFilePath());
        return new ModOrigin() {
            @Override
            public Kind getKind() {
                return paths.isEmpty() ? Kind.UNKNOWN : Kind.PATH;
            }

            @Override
            public List<Path> getPaths() {
                return paths;
            }

            @Override
            public String getParentModId() {
                throw new UnsupportedOperationException("not a nested mod");
            }

            @Override
            public String getParentSubLocation() {
                throw new UnsupportedOperationException("not a nested mod");
            }
        };
    }

    public Path getPath(String file) {
        return findPath(file).orElseGet(() -> getRootPath().resolve(file));
    }

    static FileSystem fileSystem(Path jar) throws IOException {
        URI uri = URI.create("jar:" + jar.toUri());
        try {
            return FileSystems.getFileSystem(uri);
        } catch (FileSystemNotFoundException e) {
            return FileSystems.newFileSystem(uri, Map.of());
        }
    }
}
