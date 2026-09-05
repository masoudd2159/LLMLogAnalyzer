package masoud.dabbaghi.llmloganalyzer.service;

import java.util.List;

public class PromptGenerator {

    /**
     * Used by the hybrid experiment:
     * <p>
     * Template Guard
     * ↓
     * LLM fallback
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
            data storage interrupt = 1
            data store interrupt caused by dcbf/icbi ... 0 = 0
            
            machine check interrupt = 1
            machine check status/register/enable field = 0
            machine check: i-fetch ... 0 = 0
            imprecise machine check ... <NUM> diagnostic field = 0
            
            No child processes during node-map creation = 1
            Bad file descriptor during node-map creation = 0
            Block device required during node-map creation = 0
            
            DECISION PROCEDURE
            1. Determine whether the current line is a primary event or only a diagnostic field.
            2. Look for explicit system, node, kernel/runtime, storage, network, mount, or job impact.
            3. Check whether the condition was corrected, retried, user-caused, or environment-caused.
            4. Apply the exact BGL distinctions above.
            5. If explicit system-level impact is absent and the line only reports internal state,
               return 0. Do not invent impact and do not use class frequency.
            """;

    /**
     * Extra knowledge copied from BglTemplateGuard.
     * <p>
     * This prompt is selected only when the deterministic Guard is disabled.
     */
    private static final String BGL_GUARD_RULES_KNOWLEDGE = """
            
            ================================================================
            PROMPT-ONLY GUARD RULES
            ================================================================
            
            The deterministic BGL template guard is disabled for this experiment.
            
            You must apply the following rules yourself. These rules have higher
            priority than general semantic reasoning.
            
            Apply explicit anomaly rules before explicit normal rules.
            
            ================================================================
            EXACT ANOMALY RULES
            ================================================================
            
            Predict anomaly for these exact standalone events:
            
            - data storage interrupt
            - machine check interrupt
            
            Predict anomaly for ciod node-map creation failures containing:
            
            - Cannot allocate memory
            - No child processes
            
            Predict anomaly for mailbox or control communication failures containing:
            
            - mailboxMonitor::serviceMailboxes and socket closed
            - failed to read message prefix on control stream
            - control stream closed unexpectedly
            - broken pipe
            
            Predict anomaly when the message starts with:
            
            - DDR machine check register:
            
            Predict anomaly for a non-zero process or service exit code.
            
            Examples:
            
            - exit code <NON_ZERO>
            - exit code 1
            - exit code -1
            - exit code 127
            
            Predict anomaly for:
            
            - data TLB error interrupt
            - machine check interrupt as a primary event
            - kernel terminated
            - kernel panic
            - RTS panic
            - Lustre mount failed
            - error receiving packet on tree network
            - Input/output error
            
            Important example:
            
            ciod: LOGIN chdir(<PATH>) failed: Input/output error
            => anomaly
            
            ================================================================
            EXACT NORMAL RULES
            ================================================================
            
            Predict normal for these exact BGL messages:
            
            - RTS internal error
            - can not get assembly information for node card
            - node card is not fully functional
            - nodecard is not fully functional
            - ciod: pollControlDescriptors: detected the debugger died.
            
            Predict normal for node-map creation containing:
            
            - Bad file descriptor
            - Block device required
            
            Important distinction:
            
            - Cannot allocate memory => anomaly
            - No child processes => anomaly
            - Bad file descriptor => normal
            - Block device required => normal
            
            Predict normal for messages starting with:
            
            - RTS tree/torus link training failed:
            - RTS: bad message header:
            
            Predict normal for:
            
            - machine check: i-fetch ending in 0, 0x0, or <ZERO>
            - data store interrupt caused by dcbf ending in 0, 0x0, or <ZERO>
            - data store interrupt caused by icbi ending in 0, 0x0, or <ZERO>
            
            Predict normal for these program-interrupt messages when their final
            status is 0, 0x0, or <ZERO>:
            
            - program interrupt: illegal instruction
            - program interrupt: privileged instruction
            - program interrupt: trap instruction
            
            ================================================================
            STANDALONE DIAGNOSTIC CONTINUATION FIELDS — NORMAL
            ================================================================
            
            Predict normal when the complete message starts with one of the following
            diagnostic field names and ends in a numeric or register value:
            
            - critical input interrupt enable
            - problem state (
            - store operation
            - instruction address:
            - instruction address space
            - instruction PLB error
            - data read PLB error
            - data write PLB error
            - TLB error
            - d-cache search parity error
            - memory manager address parity error
            - memory manager / command manager address parity
            - close EDRAM pages as soon as possible
            - disable all access to cache directory
            - capture first directory uncorrectable error address
            - capture first EDRAM uncorrectable error address
            - capture first DDR uncorrectable error address
            - uncorrectable error detected in directory
            - uncorrectable error detected in EDRAM bank
            - uncorrectable error detected in external DDR
            - memory manager uncorrectable error
            - uncorrectable error
            - parity error in read queue PLB
            - DDR failing address register:
            - DDR failing data registers:
            
            Valid trailing values include:
            
            - decimal numbers
            - hexadecimal values
            - <NUM>
            - <ZERO>
            - <NON_ZERO>
            - <HEX>
            
            These are diagnostic fields, not independent primary failure events.
            
            ================================================================
            REGISTER DUMPS — NORMAL
            ================================================================
            
            Predict normal when the complete message is only a sequence of register
            index/value pairs.
            
            Examples:
            
            - 0:00000000 1:81000000
            - 1:12345678 2:87654321
            - <NUM>:<HEX> <NUM>:<HEX>
            
            ================================================================
            CORRECTED AND USER-ENVIRONMENT CONDITIONS — NORMAL
            ================================================================
            
            Predict normal when the complete message explicitly says:
            
            - detected and corrected
            - corrected error
            
            Do not use this rule if the same current line explicitly reports
            an additional unrecovered system failure.
            
            Predict normal for:
            
            - program image too big
            - program interrupt: fp cr update
            - No such file or directory
            - Permission denied
            - Exec format error
            - invalid executable
            - missing program image
            - debugger died
            
            Important distinction:
            
            ciod: LOGIN chdir(<PATH>) failed: No such file or directory
            => normal
            
            ciod: LOGIN chdir(<PATH>) failed: Input/output error
            => anomaly
            
            Predict normal when an NFS mount failure explicitly says it is retrying.
            
            ================================================================
            CONFLICT RESOLUTION ORDER
            ================================================================
            
            When more than one signal appears, apply this priority:
            
            1. Exact primary anomaly rule
            2. Exact anomaly distinction
            3. Exact normal or diagnostic distinction
            4. Corrected, retrying, user-program, or environment condition
            5. General system-impact reasoning
            
            Examples:
            
            machine check interrupt
            => {"prediction":"anomaly","confidence":1.0,"reason":"Primary machine check interrupt.","category":"hardware"}
            
            machine check status register: <HEX>
            => {"prediction":"normal","confidence":1.0,"reason":"Standalone diagnostic register field.","category":"diagnostic"}
            
            data storage interrupt
            => {"prediction":"anomaly","confidence":1.0,"reason":"Explicit data storage interrupt.","category":"storage"}
            
            data store interrupt caused by dcbf ... <ZERO>
            => {"prediction":"normal","confidence":1.0,"reason":"Zero-valued diagnostic condition.","category":"diagnostic"}
            
            ciod: Error creating node map ... No child processes
            => {"prediction":"anomaly","confidence":0.99,"reason":"Node-map creation explicitly failed.","category":"job"}
            
            ciod: Error creating node map ... Bad file descriptor
            => {"prediction":"normal","confidence":0.99,"reason":"Known user-environment node-map condition.","category":"environment"}

            ciod: Error creating node map ... Block device required
            => {"prediction":"normal","confidence":0.99,"reason":"Known user-environment node-map condition.","category":"environment"}
            
            ciod: LOGIN chdir(<PATH>) failed: Input/output error
            => {"prediction":"anomaly","confidence":0.99,"reason":"Explicit storage input/output failure.","category":"storage"}
            
            ciod: LOGIN chdir(<PATH>) failed: No such file or directory
            => {"prediction":"normal","confidence":0.99,"reason":"Missing user path is an environment condition.","category":"environment"}
            
            FINAL REQUIREMENT
            
            Return exactly one JSON object matching the four-field output contract.
            Put the concise justification only in reason; do not return any other text.
            """;

    /**
     * Used when:
     * <p>
     * bgl.classification.template-guard.enabled=false
     */
    public static final String BGL_TEMPLATE_AWARE_GUARD_EMBEDDED_PROMPT = BGL_TEMPLATE_AWARE_FINAL_PROMPT + BGL_GUARD_RULES_KNOWLEDGE;

    private PromptGenerator() {
    }

    private static PromptSpec hybridPrompt() {
        return new PromptSpec(PromptExperiment.TEMPLATE_AWARE_FINAL, "BGL_QWEN35_35B_JSON_V1", BGL_TEMPLATE_AWARE_FINAL_PROMPT);
    }

    private static PromptSpec guardEmbeddedPrompt() {
        return new PromptSpec(PromptExperiment.TEMPLATE_AWARE_GUARD_RULES_EMBEDDED, "BGL_QWEN35_35B_GUARD_EMBEDDED_JSON_V1", BGL_TEMPLATE_AWARE_GUARD_EMBEDDED_PROMPT);
    }

    public static List<PromptSpec> bglPromptExperiments() {
        return List.of(hybridPrompt(), guardEmbeddedPrompt());
    }

    /**
     * Kept for backward compatibility.
     */
    public static PromptSpec finalBglPrompt() {
        return hybridPrompt();
    }

    /**
     * true:
     * deterministic Guard + original prompt
     * <p>
     * false:
     * no deterministic Guard + Guard knowledge inside the prompt
     */
    public static PromptSpec finalBglPrompt(boolean guardEnabled) {
        return guardEnabled ? hybridPrompt() : guardEmbeddedPrompt();
    }
}
