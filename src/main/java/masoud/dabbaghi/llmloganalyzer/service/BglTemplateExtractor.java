package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.dto.LogBglEntryDto;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Converts raw BGL log entries into stable templates.
 * <p>
 * Important:
 * - The original BGL dataset label is never used.
 * - Runtime-only values such as node id, hex values, ids, paths and numbers are normalized.
 * - Semantic words such as corrected/uncorrected/failed/terminated are preserved.
 */
public final class BglTemplateExtractor {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern BGL_NODE = Pattern.compile(
            "\\bR\\d{2}-M\\d-N\\d-C(?::J\\d{2}-U\\d{2}|-J\\d{2})?\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern HEX = Pattern.compile("0x[0-9a-fA-F]+\\b");
    private static final Pattern DATETIME = Pattern.compile(
            "\\b\\d{4}[-.]\\d{2}[-.]\\d{2}(?:-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d+)?\\b"
    );
    private static final Pattern PATH = Pattern.compile("(?<!\\S)/(?:[^\\s:]+/)*[^\\s:]+ ");
    private static final Pattern PATH_AT_END_OR_BEFORE_COLON = Pattern.compile("(?<!\\S)/(?:[^\\s:]+/)*[^\\s:]+(?=\\s|:|$)");
    private static final Pattern FLOAT_NUMBER = Pattern.compile("\\b\\d+\\.\\d+\\b");
    private static final Pattern INTEGER_NUMBER = Pattern.compile("\\b\\d+\\b");

    private BglTemplateExtractor() {
        // Utility class.
    }

    public static BglTemplate extract(LogBglEntryDto dto) {
        if (dto == null) {
            return new BglTemplate("BGL|empty", "", "message_template=");
        }

        String category = normalizeField(dto.getCategory());
        String component = normalizeField(dto.getComponent());
        String severity = normalizeField(dto.getSeverity());
        String normalizedMessage = normalizeMessage(dto.getMessage());

        String templateKey = ("BGL|category=" + category
                + "|component=" + component
                + "|severity=" + severity
                + "|message=" + normalizedMessage).toLowerCase(Locale.ROOT);

        String modelInput = """
                BGL_TEMPLATE:
                category=%s
                component=%s
                severity=%s
                message_template=%s
                
                EXAMPLE_WITHOUT_DATASET_LABEL:
                category=%s
                component=%s
                severity=%s
                message=%s
                """.formatted(
                category,
                component,
                severity,
                normalizedMessage,
                safe(dto.getCategory()),
                safe(dto.getComponent()),
                safe(dto.getSeverity()),
                safe(dto.getMessage())
        );

        return new BglTemplate(templateKey, normalizedMessage, modelInput);
    }

    public static String normalizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }

        String normalized = message.trim();
        normalized = BGL_NODE.matcher(normalized).replaceAll("<NODE>");
        normalized = HEX.matcher(normalized).replaceAll("<HEX>");
        normalized = DATETIME.matcher(normalized).replaceAll("<DATE>");
        normalized = PATH_AT_END_OR_BEFORE_COLON.matcher(normalized).replaceAll("<PATH>");
        normalized = PATH.matcher(normalized).replaceAll("<PATH> ");
        normalized = FLOAT_NUMBER.matcher(normalized).replaceAll("<NUM>");
        normalized = INTEGER_NUMBER.matcher(normalized).replaceAll("<NUM>");
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ");

        return normalized.trim();
    }

    private static String normalizeField(String value) {
        return safe(value).trim().toUpperCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
