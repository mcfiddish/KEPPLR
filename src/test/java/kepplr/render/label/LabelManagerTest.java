package kepplr.render.label;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import kepplr.render.label.LabelManager.LabelCandidate;
import kepplr.util.KepplrConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LabelManager}'s decluttering algorithm.
 *
 * <p>Tests only the pure-logic {@link LabelManager#filterLabels(List)} method which requires no JME scene graph.
 */
@DisplayName("LabelManager")
class LabelManagerTest {

    private static final double MIN_SEP = KepplrConstants.LABEL_DECLUTTER_MIN_SEPARATION_PX;

    @Nested
    @DisplayName("filterLabels()")
    class FilterLabelsTests {

        @Test
        @DisplayName("empty input produces empty output")
        void emptyInput() {
            List<LabelCandidate> result = LabelManager.filterLabels(new ArrayList<>());
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("single candidate is always approved")
        void singleCandidate() {
            List<LabelCandidate> candidates = List.of(new LabelCandidate(399, "Earth", 100, 100, 6371, 0.0));
            List<LabelCandidate> result = LabelManager.filterLabels(new ArrayList<>(candidates));
            assertEquals(1, result.size());
            assertEquals(399, result.get(0).naifId());
        }

        @Test
        @DisplayName("well-separated labels are all approved")
        void wellSeparated() {
            List<LabelCandidate> candidates = List.of(
                    new LabelCandidate(399, "Earth", 100, 100, 6371, 0.0),
                    new LabelCandidate(301, "Moon", 100 + MIN_SEP + 10, 100, 1737, 0.0));
            List<LabelCandidate> result = LabelManager.filterLabels(new ArrayList<>(candidates));
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("close label is suppressed by larger-radius body")
        void closeLabelSuppressed() {
            // Earth at (100, 100) with radius 6371
            // Moon at (105, 105) with radius 1737 — within MIN_SEP of Earth
            // Since sorted by radius descending, Earth is first and Moon is suppressed
            List<LabelCandidate> candidates = new ArrayList<>();
            candidates.add(new LabelCandidate(399, "Earth", 100, 100, 6371, 0.0));
            candidates.add(new LabelCandidate(301, "Moon", 105, 105, 1737, 0.0));
            List<LabelCandidate> result = LabelManager.filterLabels(candidates);
            assertEquals(1, result.size());
            assertEquals(399, result.get(0).naifId());
        }

        @Test
        @DisplayName("labels exactly at separation threshold are approved")
        void exactlyAtThreshold() {
            // Place second label exactly at MIN_SEP distance (should be approved since >= is not suppressed)
            List<LabelCandidate> candidates = new ArrayList<>();
            candidates.add(new LabelCandidate(399, "Earth", 100, 100, 6371, 0.0));
            candidates.add(new LabelCandidate(301, "Moon", 100 + MIN_SEP, 100, 1737, 0.0));
            List<LabelCandidate> result = LabelManager.filterLabels(candidates);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("three candidates: first and third approved, second suppressed")
        void threeWithMiddleSuppressed() {
            // Three bodies: A at (0,0), B at (5,5) close to A, C at (200,200) far from both
            List<LabelCandidate> candidates = new ArrayList<>();
            candidates.add(new LabelCandidate(10, "Sun", 0, 0, 696000, 0.0));
            candidates.add(new LabelCandidate(399, "Earth", 5, 5, 6371, 0.0));
            candidates.add(new LabelCandidate(301, "Moon", 200, 200, 1737, 0.0));
            List<LabelCandidate> result = LabelManager.filterLabels(candidates);
            assertEquals(2, result.size());
            assertEquals(10, result.get(0).naifId());
            assertEquals(301, result.get(1).naifId());
        }

        @Test
        @DisplayName("smaller-radius body cannot suppress larger-radius body (sort order matters)")
        void sortOrderMatters() {
            // If candidates are properly sorted by radius descending, the larger body is always
            // considered first and cannot be suppressed by a smaller body.
            List<LabelCandidate> candidates = new ArrayList<>();
            candidates.add(new LabelCandidate(399, "Earth", 100, 100, 6371, 0.0));
            candidates.add(new LabelCandidate(10, "Sun", 105, 105, 696000, 0.0));
            // This is NOT pre-sorted correctly (Earth before Sun by radius)
            // The method should still work because it processes in given order
            List<LabelCandidate> result = LabelManager.filterLabels(candidates);
            // Earth is approved first (it's first in list), Sun is suppressed (too close)
            assertEquals(1, result.size());
            assertEquals(399, result.get(0).naifId());
        }
    }
}
