package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.dto.LogBglEntryDto;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Converts raw BGL log entries into stable templates.
 *
 * The extractor removes runtime-only values but keeps semantic words such as
 * corrected/uncorrected/failed/terminated because they change the label meaning.
 */
public final class BglTemplateExtractor {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /* Broad BGL location/node patterns such as R63-M1-N0-I:J18-U11 or R00-M0-N3-J06-U01. */
    private static final Pattern BGL_NODE = Pattern.compile(
            "\\bR\\d{2}-M\\d-N\\d-[A-Z0-9:.-]+\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern JTAG_UNIT = Pattern.compile("\\bJ\\d{2}-U\\d{2}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX = Pattern.compile("0x[0-9a-fA-F]+\\b");
    private static final Pattern IP_ADDRESS = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern DATETIME = Pattern.compile(
            "\\b\\d{4}[-.]\\d{2}[-.]\\d{2}(?:-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d+)?\\b"
    );

    private static final Pattern PATH_INSIDE_PARENTHESES = Pattern.compile("(?<=\\()/[^)\\s]+(?=\\))");
    private static final Pattern PATH_GENERAL = Pattern.compile("(?<![A-Za-z0-9_<])/(?:[^\\s:)]+/)*[^\\s:)]+(?=\\s|:|\\)|$)");
    private static final Pattern FLOAT_NUMBER = Pattern.compile("\\b\\d+\\.\\d+\\b");
    private static final Pattern INTEGER_NUMBER = Pattern.compile("\\b\\d+\\b");

    private BglTemplateExtractor() {
        // Utility class.
    }

    public static BglTemplate extract(LogBglEntryDto dto) {
        return extract(dto, false);
    }

    public static BglTemplate extract(LogBglEntryDto dto, boolean includeMetadataInTemplateKey) {
        if (dto == null) {
            return new BglTemplate("bgl|message=", "", "message_template=");
        }

        String category = normalizeField(dto.getCategory());
        String component = normalizeField(dto.getComponent());
        String severity = normalizeField(dto.getSeverity());
        String normalizedMessage = normalizeMessage(dto.getMessage());

        String templateKey;
        if (includeMetadataInTemplateKey) {
            templateKey = ("BGL|category=" + category
                    + "|component=" + component
                    + "|severity=" + severity
                    + "|message=" + normalizedMessage).toLowerCase(Locale.ROOT);
        } else {
            templateKey = ("BGL|message=" + normalizedMessage).toLowerCase(Locale.ROOT);
        }

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
        normalized = JTAG_UNIT.matcher(normalized).replaceAll("<UNIT>");
        normalized = IP_ADDRESS.matcher(normalized).replaceAll("<IP>");
        normalized = HEX.matcher(normalized).replaceAll("<HEX>");
        normalized = DATETIME.matcher(normalized).replaceAll("<DATE>");

        /* Paths must be normalized before numbers so /p/gb1/.../2183 becomes one stable <PATH>. */
        normalized = PATH_INSIDE_PARENTHESES.matcher(normalized).replaceAll("<PATH>");
        normalized = PATH_GENERAL.matcher(normalized).replaceAll("<PATH>");

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
