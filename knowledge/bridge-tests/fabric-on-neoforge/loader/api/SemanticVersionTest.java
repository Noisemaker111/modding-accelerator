package ${package}.loader.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A semantic version reads its numbers, pre-release and build metadata; versions order by number (1.10 after 1.9), a
 * pre-release before its release, build metadata not at all; text that is no version is refused.
 */
class SemanticVersionTest {

    @Test
    void versionsParseAndOrderAsSemanticVersions() throws VersionParsingException {
        SemanticVersion beta = SemanticVersion.parse("1.2.3-beta.1+build.7");
        assertEquals(3, beta.getVersionComponentCount());
        assertEquals(2, beta.getVersionComponent(1));
        assertEquals(0, beta.getVersionComponent(5));
        assertEquals(Optional.of("beta.1"), beta.getPrereleaseKey());
        assertEquals(Optional.of("build.7"), beta.getBuildKey());
        assertEquals("1.2.3-beta.1+build.7", beta.getFriendlyString());
        assertTrue(beta.compareTo(SemanticVersion.parse("1.2.3")) < 0);
        assertTrue(SemanticVersion.parse("1.10.0").compareTo(SemanticVersion.parse("1.9.0")) > 0);
        assertEquals(0, SemanticVersion.parse("2.0.0+a").compareTo(SemanticVersion.parse("2.0.0+b")));
        Version plain = Version.parse("2.0.0");
        assertTrue(plain.compareTo(SemanticVersion.parse("1.9.9")) > 0);
        assertThrows(VersionParsingException.class, () -> SemanticVersion.parse("one.two"));
    }
}
