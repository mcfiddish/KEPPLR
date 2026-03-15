package kepplr.ephemeris;

import static org.junit.jupiter.api.Assertions.*;

import kepplr.config.KEPPLRConfiguration;
import kepplr.testsupport.TestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("BodyLookupService")
class BodyLookupServiceTest {

    @BeforeEach
    void setUp() {
        TestHarness.resetSingleton();
        KEPPLRConfiguration.getTestTemplate();
    }

    // ── resolve ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolve(String)")
    class Resolve {

        @Test
        @DisplayName("resolves 'EARTH' to NAIF 399")
        void resolveByName() {
            assertEquals(399, BodyLookupService.resolve("EARTH"));
        }

        @Test
        @DisplayName("resolves case-insensitively ('earth' → 399)")
        void resolveCaseInsensitive() {
            assertEquals(399, BodyLookupService.resolve("earth"));
        }

        @Test
        @DisplayName("resolves numeric string '399' to NAIF 399")
        void resolveByNumericString() {
            assertEquals(399, BodyLookupService.resolve("399"));
        }

        @Test
        @DisplayName("resolves 'SUN' to NAIF 10")
        void resolveSun() {
            assertEquals(10, BodyLookupService.resolve("SUN"));
        }

        @Test
        @DisplayName("resolves 'MOON' to NAIF 301")
        void resolveMoon() {
            assertEquals(301, BodyLookupService.resolve("MOON"));
        }

        @Test
        @DisplayName("throws on null input")
        void throwsOnNull() {
            assertThrows(IllegalArgumentException.class, () -> BodyLookupService.resolve(null));
        }

        @Test
        @DisplayName("throws on blank input")
        void throwsOnBlank() {
            assertThrows(IllegalArgumentException.class, () -> BodyLookupService.resolve("   "));
        }

        @Test
        @DisplayName("throws on unknown name")
        void throwsOnUnknown() {
            assertThrows(IllegalArgumentException.class, () -> BodyLookupService.resolve("XYZZY"));
        }
    }

    // ── formatName ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("formatName(int)")
    class FormatName {

        @Test
        @DisplayName("returns '—' for -1")
        void noBody() {
            assertEquals("—", BodyLookupService.formatName(-1));
        }

        @Test
        @DisplayName("returns title-cased name for Earth (399)")
        void earth() {
            String name = BodyLookupService.formatName(399);
            assertEquals("Earth", name);
        }

        @Test
        @DisplayName("returns title-cased name for Sun (10)")
        void sun() {
            assertEquals("Sun", BodyLookupService.formatName(10));
        }

        @Test
        @DisplayName("returns title-cased name for Moon (301)")
        void moon() {
            assertEquals("Moon", BodyLookupService.formatName(301));
        }

        @Test
        @DisplayName("returns 'NAIF <id>' for unknown body")
        void unknownBody() {
            assertEquals("NAIF 999999", BodyLookupService.formatName(999999));
        }
    }

    // ── titleCase ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("titleCase(String)")
    class TitleCase {

        @Test
        @DisplayName("converts 'EARTH' → 'Earth'")
        void singleWord() {
            assertEquals("Earth", BodyLookupService.titleCase("EARTH"));
        }

        @Test
        @DisplayName("converts 'MARS BARYCENTER' → 'Mars Barycenter'")
        void multiWord() {
            assertEquals("Mars Barycenter", BodyLookupService.titleCase("MARS BARYCENTER"));
        }

        @Test
        @DisplayName("handles empty string")
        void empty() {
            assertEquals("", BodyLookupService.titleCase(""));
        }

        @Test
        @DisplayName("handles null")
        void nullInput() {
            assertNull(BodyLookupService.titleCase(null));
        }
    }
}
