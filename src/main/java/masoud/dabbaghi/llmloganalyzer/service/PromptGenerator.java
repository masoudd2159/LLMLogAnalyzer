package masoud.dabbaghi.llmloganalyzer.service;

import java.util.List;

public class PromptGenerator {

    /**
     * General instructions for the prompt-only experiment.
     *
     * <p>This prompt intentionally contains no deterministic Rule Guard mappings,
     * dataset-derived label examples, or exact BGL event-to-label decisions.</p>
     */
    public static final String BGL_PROMPT_ONLY_PROMPT = """
            You are Qwen3.5:35B operating as a conservative binary classifier for one
            Blue Gene/L (BGL) high-performance-computing log record at a time.

            OBJECTIVE AND LABELS
            Classify the current record using only the observable evidence in that record.
            normal = routine operation, informational or diagnostic state, corrected or retryable
            condition, or a user/environment condition without explicit system failure
            anomaly = explicit evidence that a system component, node, kernel/runtime, storage,
            network, mount, control channel, or job failed, became unavailable, or terminated

            GENERAL CLASSIFICATION INSTRUCTIONS
            - Evaluate only the current record; do not assume context from neighboring records.
            - Treat category, component, severity, and message as observable features.
            - Do not classify from severity or a single keyword alone.
            - Require explicit operational impact before predicting anomaly.
            - Do not use class frequency or invent evidence that is absent from the record.
            - If explicit system-level impact is absent, predict normal.

            OUTPUT CONTRACT
            Return exactly one compact JSON object and no Markdown or surrounding text:
            {"prediction":"normal|anomaly","confidence":0.0,"reason":"short evidence-based explanation","category":"hardware|software|network|storage|job|diagnostic|environment|unknown"}

            prediction must be exactly normal or anomaly. confidence must be a JSON number from
            0.0 through 1.0. reason must be at most one short sentence and cite only observable
            evidence in this record. category must be exactly one listed value. Do not reveal private
            chain-of-thought, analysis, hidden reasoning, or alternative answers.
            """;

    /**
     * Used by the hybrid experiment after the deterministic Rule Guard returns no decision.
     */
    public static final String BGL_TEMPLATE_AWARE_FINAL_PROMPT = """
            You are Qwen3.5:35B operating as a conservative binary classifier for one
            Blue Gene/L (BGL) high-performance-computing log record at a time.

            OBJECTIVE AND LABELS
            normal = diagnostic, corrected, retryable, user-program, or environment condition
            anomaly = explicit system, node, kernel/runtime, storage, network, mount, or job failure

            OUTPUT CONTRACT
            Return exactly one compact JSON object and no Markdown or surrounding text:
            {"prediction":"normal|anomaly","confidence":0.0,"reason":"short evidence-based explanation","category":"hardware|software|network|storage|job|diagnostic|environment|unknown"}

            prediction must be exactly normal or anomaly. confidence must be a JSON number from
            0.0 through 1.0. reason must be at most one short sentence and must cite only observable
            evidence in this record. category must be exactly one listed value. Do not reveal private
            chain-of-thought, analysis, hidden reasoning, or alternative answers.

            EVIDENCE THRESHOLD
            Anomaly requires explicit evidence in the current line that a system component,
            node, kernel/runtime, storage, network, mount, control channel, or job actually
            failed, became unavailable, or terminated.

            Do not classify by severity or keywords alone. Category=RAS, severity=FATAL,
            and words such as error, failed, interrupt, parity, illegal, trap, privileged,
            uncorrectable, timeout, unavailable, or machine check are not sufficient by themselves.

            PRIMARY EVENT VS DIAGNOSTIC FIELD
            BGL reports often contain one primary event followed by standalone diagnostic fields.
            Classify only the current line. Do not transfer failure impact from a neighboring line.

            A primary event line reports an actual interrupt, panic, termination, crash,
            communication break, mount failure, node failure, job failure, or unrecovered operation.

            A diagnostic field only reports a register, status bit, enable flag, counter,
            address, syndrome, configuration value, or captured hardware state. A diagnostic field
            is normal even when its numeric value is non-zero and even when its text contains
            machine check, parity error, uncorrectable, or FATAL.

            ALWAYS PREDICT NORMAL FOR THESE STANDALONE BGL DIAGNOSTIC FIELDS
            - machine check status register: <HEX>
            - machine check enable ... <NUM>
            - i-cache parity error ... <NUM>
            - imprecise machine check ... <NUM>
            - capture first EDRAM parity error address ... <NUM>
            - instruction address: <ADDRESS>
            - instruction address space ... <NUM>
            - critical input interrupt enable ... <NUM>
            - problem state (0=sup,1=usr) ... <NUM>
            - store operation ... <NUM>
            - instruction, data-read, or data-write PLB error ... 0
            - TLB/parity/memory-manager status field ending in a numeric/register value
            - standalone numeric register or address dump

            The five machine-check/parity patterns above are fields, not primary failure events.
            Their values describe internal state only. A non-zero field value does not independently
            prove that the node, kernel, job, or system failed.

            ALSO PREDICT NORMAL
            - program interrupt: illegal instruction ... 0
            - program interrupt: privileged instruction ... 0
            - program interrupt: trap instruction ... 0
            - program interrupt: fp cr update
            - machine check: i-fetch ... 0
            - data store interrupt caused by dcbf/icbi ... 0
            - corrected or detected-and-corrected error
            - operation explicitly retrying
            - missing file, bad file descriptor, permission denied, exec format error,
              invalid executable, program image too big, debugger died, or
              node-map creation reporting Block device required

            PREDICT ANOMALY FOR EXPLICIT SYSTEM FAILURE
            - data storage interrupt
            - machine check interrupt as the primary event line
            - data TLB error interrupt
            - kernel panic, kernel termination, node crash, or node failure
            - ciod node-map creation: Cannot allocate memory
            - ciod node-map creation: No child processes
            - mailbox/control stream closed, broken, or unreadable
            - Lustre mount FAILED or Input/output error
            - non-zero service/process exit code
            - explicit unrecovered memory, storage, network, hardware, runtime, or job failure

            CRITICAL DISTINCTIONS
            data storage interrupt = anomaly
            data store interrupt caused by dcbf/icbi ... 0 = normal

            machine check interrupt = anomaly
            machine check status/register/enable field = normal
            machine check: i-fetch ... 0 = normal
            imprecise machine check ... <NUM> diagnostic field = normal

            No child processes during node-map creation = anomaly
            Bad file descriptor during node-map creation = normal
            Block device required during node-map creation = normal

            DECISION PROCEDURE
            1. Determine whether the current line is a primary event or only a diagnostic field.
            2. Look for explicit system, node, kernel/runtime, storage, network, mount, or job impact.
            3. Check whether the condition was corrected, retried, user-caused, or environment-caused.
            4. Apply the exact BGL distinctions above.
            5. If explicit system-level impact is absent and the line only reports internal state,
               predict normal. Do not invent impact and do not use class frequency.
            """;

    private PromptGenerator() {
    }

    private static PromptSpec hybridPrompt() {
        return new PromptSpec(
                PromptExperiment.TEMPLATE_AWARE_FINAL,
                "BGL_QWEN35_35B_JSON_V2",
                BGL_TEMPLATE_AWARE_FINAL_PROMPT
        );
    }

    private static PromptSpec promptOnly() {
        return new PromptSpec(
                PromptExperiment.TEMPLATE_AWARE_PROMPT_ONLY,
                "BGL_QWEN35_35B_PROMPT_ONLY_JSON_V1",
                BGL_PROMPT_ONLY_PROMPT
        );
    }

    public static List<PromptSpec> bglPromptExperiments() {
        return List.of(hybridPrompt(), promptOnly());
    }

    /** Kept for backward compatibility; the no-argument selection is the hybrid fallback prompt. */
    public static PromptSpec finalBglPrompt() {
        return hybridPrompt();
    }

    /** Selects one of the two thesis experiments without changing shared model configuration. */
    public static PromptSpec finalBglPrompt(boolean guardEnabled) {
        return guardEnabled ? hybridPrompt() : promptOnly();
    }
}
