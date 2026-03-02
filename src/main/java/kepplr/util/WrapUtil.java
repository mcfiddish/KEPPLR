package kepplr.util;

import org.apache.commons.text.WordUtils;

/**
 * Utilities for wrapping text while preserving blank lines exactly as written.
 *
 * <p>Behavior:
 *
 * <ul>
 *   <li>Line endings in the input may be {@code \r\n}, {@code \r}, or {@code \n}. They are normalized and the output
 *       uses {@code \n} line breaks.
 *   <li>Blank lines (lines whose {@code trim()} is empty), including lines that contain only spaces/tabs, are emitted
 *       verbatim. Runs of multiple blank lines are preserved byte-for-byte.
 *   <li>Non-blank lines are grouped into paragraphs. Within each paragraph, existing newlines are treated as spaces and
 *       the paragraph is fully reflowed to the requested width using {@link WordUtils#wrap}.
 *   <li>After a wrapped paragraph and before the next blank line, a newline is inserted so the blank line remains
 *       visible between paragraphs.
 * </ul>
 *
 * <p>This class is stateless and thread-safe.
 */
public final class WrapUtil {

    private WrapUtil() {}

    /**
     * Wraps text to the given width while preserving every blank line exactly.
     *
     * <p>A “blank line” is any line whose {@code trim()} is empty; such lines (including those containing only
     * spaces/tabs) are passed through unchanged. Non-blank lines are accumulated into paragraphs; intra-paragraph
     * newlines are treated as spaces so each paragraph is fully reflowed.
     *
     * <p>Line endings are normalized: the output always uses {@code \n} as the line separator, regardless of the
     * input’s platform.
     *
     * @param text input text; may be {@code null}; may contain any mix of {@code \r\n}, {@code \n}, or {@code \r}
     * @param width target column width (must be {@code >= 1})
     * @return wrapped text with all blank lines preserved, or {@code null} if {@code text} is {@code null}
     * @see #wrapPreservingBlankLines(String, int, boolean)
     */
    public static String wrapPreservingBlankLines(String text, int width) {
        return wrapPreservingBlankLines(text, width, /*wrapLongWords=*/ false);
    }

    /**
     * Wraps text to the given width while preserving every blank line exactly, with control over whether extremely long
     * “words” (unbreakable tokens such as URLs) may be split.
     *
     * <p>Semantics are identical to {@link #wrapPreservingBlankLines(String, int)} except for handling of long tokens:
     *
     * <ul>
     *   <li>If {@code wrapLongWords} is {@code true}, tokens longer than {@code width} may be broken to satisfy the
     *       width.
     *   <li>If {@code wrapLongWords} is {@code false}, such tokens are left intact and may exceed {@code width} on a
     *       line.
     * </ul>
     *
     * <p>Implementation notes:
     *
     * <ul>
     *   <li>Delegates wrapping to {@link WordUtils#wrap(String, int, String, boolean)}.
     *   <li>Output line separators are {@code "\n"} for consistency.
     * </ul>
     *
     * @param text input text; may be {@code null}; may contain any mix of {@code \r\n}, {@code \n}, or {@code \r}
     * @param width target column width (must be {@code >= 1})
     * @param wrapLongWords whether to split tokens longer than {@code width}
     * @return wrapped text with all blank lines preserved, or {@code null} if {@code text} is {@code null}
     */
    public static String wrapPreservingBlankLines(String text, int width, boolean wrapLongWords) {
        if (text == null) return null;

        // Normalize line endings to '\n'
        final String s = text.replace("\r\n", "\n").replace('\r', '\n');

        StringBuilder out = new StringBuilder(s.length() + s.length() / 8);
        StringBuilder paragraph = new StringBuilder();

        int lineStart = 0;
        for (int i = 0, n = s.length(); i <= n; i++) {
            if (i == n || s.charAt(i) == '\n') {
                String line = s.substring(lineStart, i);
                boolean isBlank = line.trim().isEmpty();

                if (isBlank) {
                    // Terminate current paragraph so the next emitted blank line is visible.
                    flushParagraph(out, paragraph, width, wrapLongWords, /*appendNewline=*/ true);
                    // Emit the blank line exactly as written (including any spaces/tabs).
                    out.append(line).append('\n');
                } else {
                    // Accumulate non-blank lines into the current paragraph.
                    paragraph.append(line).append('\n');
                }
                lineStart = i + 1;
            }
        }

        // Final paragraph (input may not end with a newline).
        flushParagraph(out, paragraph, width, wrapLongWords, /*appendNewline=*/ false);
        return out.toString();
    }

    // ---- internal helpers ----

    private static void flushParagraph(
            StringBuilder out, StringBuilder paragraph, int width, boolean wrapLongWords, boolean appendNewline) {
        if (paragraph.isEmpty()) return;

        // Flatten intra-paragraph newlines to single spaces and trim edges.
        String flattened =
                paragraph.toString().replaceAll("[ \\t]*\\n[ \\t]*", " ").trim();

        if (!flattened.isEmpty()) {
            String wrapped = WordUtils.wrap(flattened, width, "\n", wrapLongWords);
            out.append(wrapped);
            if (appendNewline) out.append('\n');
        }
        paragraph.setLength(0);
    }
}
