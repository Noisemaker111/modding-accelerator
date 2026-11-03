import java.io.File;
import net.minecraftforge.srgutils.IMappingFile;

/**
 * Mojang names to the loader's runtime names, for pack-check's probe: Mojang's mappings (named -> obfuscated)
 * chained with MCPConfig's (obfuscated -> runtime). Run with the srgutils jar from the loader's own install.
 * Usage: ProbeNames MOJANG_MAPPINGS.txt MCP_MERGED.tsrg OUT.tsrg
 */
public final class ProbeNames {
    public static void main(String[] args) throws Exception {
        IMappingFile named = IMappingFile.load(new File(args[0]));
        IMappingFile runtime = IMappingFile.load(new File(args[1]));
        named.chain(runtime).write(new File(args[2]).getAbsoluteFile().toPath(), IMappingFile.Format.TSRG2, false);
    }
}
