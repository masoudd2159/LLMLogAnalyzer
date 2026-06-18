package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.dto.LogBglEntryDto;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Converts raw BGL log entries into stable templates.
 *
 * Key improvements for large BGL runs:
 * - normalize node identifiers more aggressively, including IO node forms such as R63-M1-N0-I:J18-U11;
 * - normalize Unix paths inside parentheses and after words such as chdir/loading;
 * - use a message-template cache key by default so the same semantic template is reused across severity/category variants;
 * - keep category/component/severity in the LLM input, but do not force them into the cache key unless explicitly configured.
 */
public final class BglTemplateExtractor {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Covers compute and I/O node ids, e.g. R02-M1-N0-C:J12-U11 and R63-M1-N0-I:J18-U11.
     */
    private static final Pattern BGL_NODE = Pattern.compile(
            "\\bR\\d{2}-M\\d-N\\d(?:-[A-Z])?(?::J\\d{2}-U\\d{2}|-J\\d{2})?\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern HEX = Pattern.compile("0x[0-9a-fA-F]+\\b");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern DATETIME = Pattern.compile(
            "\\b\\d{4}[-.]\\d{2}[-.]\\d{2}(?:-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d+)?\\b"
    );

    /**
     * Handles paths inside parentheses, for example chdir(/p/gb1/...).
     */
    private static final Pattern CHDIR_PATH = Pattern.compile(
            "chdir\\([^)]*\\)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * More general Unix path normalization. Avoids swallowing trailing ':' or ')'.
     */
    private static final Pattern UNIX_PATH = Pattern.compile(
            "(?<![A-Za-z0-9_<])/(?:[^\\s)\\]:]+/)*[^\\s)\\]:]+"
    );

    private static final Pattern FLOAT_NUMBER = Pattern.compile("\\b\\d+\\.\\d+\\b");
    private static final Pattern INTEGER_NUMBER = Pattern.compile("\\b\\d+\\b");
    private static final Pattern LONG_ALNUM_ID = Pattern.compile("\\b[A-Fa-f0-9]{8,}\\b");

    private BglTemplateExtractor() {
        /* Utility class. */
    }

    public static BglTemplate extract(LogBglEntryDto dto) {
        return extract(dto, false);
    }

    public static BglTemplate extract(LogBglEntryDto dto, boolean includeMetadataInCacheKey) {
        if (dto == null) {
            return new BglTemplate("bgl|message=", "", "message_template=");
        }

        String category = normalizeField(dto.getCategory());
        String component = normalizeField(dto.getComponent());
        String severity = normalizeField(dto.getSeverity());
        String normalizedMessage = normalizeMessage(dto.getMessage());

        String templateKey;
        if (includeMetadataInCacheKey) {
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
        normalized = CHDIR_PATH.matcher(normalized).replaceAll("chdir(<PATH>)");
        normalized = BGL_NODE.matcher(normalized).replaceAll("<NODE>");
        normalized = IPV4.matcher(normalized).replaceAll("<IP>");
        normalized = HEX.matcher(normalized).replaceAll("<HEX>");
        normalized = DATETIME.matcher(normalized).replaceAll("<DATE>");
        normalized = UNIX_PATH.matcher(normalized).replaceAll("<PATH>");
        normalized = FLOAT_NUMBER.matcher(normalized).replaceAll("<NUM>");
        normalized = INTEGER_NUMBER.matcher(normalized).replaceAll("<NUM>");
        normalized = LONG_ALNUM_ID.matcher(normalized).replaceAll("<ID>");
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
