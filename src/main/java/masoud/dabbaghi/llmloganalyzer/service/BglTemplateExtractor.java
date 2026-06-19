package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.dto.LogBglEntryDto;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BglTemplateExtractor {

    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern NODE = Pattern.compile("\\bR\\d{2}-M\\d-N\\d-[A-Z0-9:.-]+\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNIT = Pattern.compile("\\bJ\\d{2}-U\\d{2}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX = Pattern.compile("0x[0-9a-fA-F]+\\b");
    private static final Pattern IP = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern DATE = Pattern.compile("\\b\\d{4}[-.]\\d{2}[-.]\\d{2}(?:-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d+)?\\b");
    private static final Pattern PATH_IN_PARENS = Pattern.compile("(?<=\\()/[^)\\s]+(?=\\))");
    private static final Pattern PATH = Pattern.compile("(?<![A-Za-z0-9_<])/(?:[^\\s:)]+/)*[^\\s:)]+(?=\\s|:|\\)|$)");
    private static final Pattern FLOAT = Pattern.compile("\\b\\d+\\.\\d+\\b");
    private static final Pattern INTEGER = Pattern.compile("\\b\\d+\\b");

    private static final List<Pattern> SEMANTIC_NUMBER_PATTERNS = List.of(
            Pattern.compile("(?i)(\\bexit\\s+code\\s+)(-?\\d+)\\b"),
            Pattern.compile(
                    "(?i)(\\b(?:critical\\s+input\\s+interrupt\\s+enable"
                            + "|problem\\s+state\\s*\\([^)]*\\)"
                            + "|store\\s+operation"
                            + "|instruction\\s+plb\\s+error"
                            + "|data\\s+write\\s+plb\\s+error"
                            + "|tlb\\s+error"
                            + "|d-cache\\s+search\\s+parity\\s+error"
                            + "|close\\s+edram\\s+pages\\s+as\\s+soon\\s+as\\s+possible"
                            + "|disable\\s+all\\s+access\\s+to\\s+cache\\s+directory"
                            + "|capture\\s+first\\s+(?:directory|edram|ddr)\\s+uncorrectable\\s+error\\s+address"
                            + "|uncorrectable\\s+error\\s+detected\\s+in\\s+(?:directory\\s+\\d+|edram\\s+bank\\s+\\d+|external\\s+ddr)"
                            + "|memory\\s+manager\\s+uncorrectable\\s+error"
                            + "|uncorrectable\\s+error"
                            + "|parity\\s+error\\s+in\\s+read\\s+queue\\s+plb"
                            + "|machine\\s+check:\\s*i-fetch"
                            + "|imprecise\\s+machine\\s+check"
                            + "|data\\s+store\\s+interrupt\\s+caused\\s+by\\s+(?:dcbf|icbi))"
                            + "\\.*\\s*)(-?\\d+)\\s*$"
            )
    );

    private BglTemplateExtractor() {
    }

    public static BglTemplate extract(LogBglEntryDto dto) {
        return extract(dto, true);
    }

    public static BglTemplate extract(LogBglEntryDto dto, boolean includeMetadata) {
        if (dto == null) {
            return new BglTemplate("bgl|message=", "", "message_template=");
        }

        String category = field(dto.getCategory());
        String component = field(dto.getComponent());
        String severity = field(dto.getSeverity());
        String message = normalizeMessage(dto.getMessage());

        String key = includeMetadata
                ? ("BGL|category=" + category + "|component=" + component + "|severity=" + severity + "|message=" + message).toLowerCase(Locale.ROOT)
                : ("BGL|message=" + message).toLowerCase(Locale.ROOT);

        String input = """
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
                category, component, severity, message,
                safe(dto.getCategory()), safe(dto.getComponent()), safe(dto.getSeverity()), safe(dto.getMessage())
        );

        return new BglTemplate(key, message, input);
    }

    public static String normalizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }

        String value = message.trim();
        value = NODE.matcher(value).replaceAll("<NODE>");
        value = UNIT.matcher(value).replaceAll("<UNIT>");
        value = IP.matcher(value).replaceAll("<IP>");
        value = HEX.matcher(value).replaceAll("<HEX>");
        value = DATE.matcher(value).replaceAll("<DATE>");
        value = PATH_IN_PARENS.matcher(value).replaceAll("<PATH>");
        value = PATH.matcher(value).replaceAll("<PATH>");
        value = preserveSemanticNumbers(value);
        value = FLOAT.matcher(value).replaceAll("<NUM>");
        value = INTEGER.matcher(value).replaceAll("<NUM>");
        return SPACE.matcher(value).replaceAll(" ").trim();
    }

    private static String preserveSemanticNumbers(String value) {
        String result = value;
        for (Pattern pattern : SEMANTIC_NUMBER_PATTERNS) {
            result = replaceSemanticNumber(result, pattern);
        }
        return result;
    }

    private static String replaceSemanticNumber(String value, Pattern pattern) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String marker = isZero(matcher.group(2)) ? "<ZERO>" : "<NON_ZERO>";
            matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(1) + marker));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static boolean isZero(String value) {
        try {
            return Long.parseLong(value) == 0L;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String field(String value) {
        return safe(value).trim().toUpperCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
