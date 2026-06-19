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
    private static final Pattern EXIT_CODE = Pattern.compile("(?i)(\\bexit\\s+code\\s+)(-?\\d+)\\b");
    private static final Pattern TRAILING_INTEGER = Pattern.compile("^(.*?)(-?\\d+)\\s*$");
    private static final Pattern FLOAT = Pattern.compile("\\b\\d+\\.\\d+\\b");
    private static final Pattern INTEGER = Pattern.compile("\\b\\d+\\b");

    private static final List<String> SEMANTIC_STATUS_PREFIXES = List.of(
            "critical input interrupt enable",
            "problem state (",
            "store operation",
            "instruction address space",
            "instruction plb error",
            "data read plb error",
            "data write plb error",
            "tlb error",
            "d-cache search parity error",
            "memory manager address parity error",
            "memory manager / command manager address parity",
            "program interrupt: illegal instruction",
            "program interrupt: privileged instruction",
            "program interrupt: trap instruction",
            "close edram pages as soon as possible",
            "disable all access to cache directory",
            "capture first directory uncorrectable error address",
            "capture first edram uncorrectable error address",
            "capture first ddr uncorrectable error address",
            "uncorrectable error detected in directory",
            "uncorrectable error detected in edram bank",
            "uncorrectable error detected in external ddr",
            "memory manager uncorrectable error",
            "uncorrectable error",
            "parity error in read queue plb",
            "machine check: i-fetch",
            "imprecise machine check",
            "data store interrupt caused by dcbf",
            "data store interrupt caused by icbi"
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
        value = preserveExitCode(value);
        value = preserveTrailingStatus(value);
        value = FLOAT.matcher(value).replaceAll("<NUM>");
        value = INTEGER.matcher(value).replaceAll("<NUM>");
        return SPACE.matcher(value).replaceAll(" ").trim();
    }

    private static String preserveExitCode(String value) {
        Matcher matcher = EXIT_CODE.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(
                    result,
                    Matcher.quoteReplacement(matcher.group(1) + marker(matcher.group(2)))
            );
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String preserveTrailingStatus(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        boolean semantic = SEMANTIC_STATUS_PREFIXES.stream().anyMatch(lower::startsWith);
        if (!semantic) {
            return value;
        }

        Matcher matcher = TRAILING_INTEGER.matcher(value);
        if (!matcher.matches()) {
            return value;
        }
        return matcher.group(1) + marker(matcher.group(2));
    }

    private static String marker(String number) {
        try {
            return Long.parseLong(number) == 0L ? "<ZERO>" : "<NON_ZERO>";
        } catch (NumberFormatException ignored) {
            return "<NUM>";
        }
    }

    private static String field(String value) {
        return safe(value).trim().toUpperCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
