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
            You are a conservative binary classifier for Blue Gene/L (BGL) HPC log lines.
            
            OUTPUT
            Return only {"label":"0"} or {"label":"1"}.
            0 = normal, diagnostic, corrected, retryable, user-program, or environment issue
            1 = explicit system-level anomaly
            
            EVIDENCE THRESHOLD
            Label 1 requires explicit evidence in the current line that a system component,
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
            is label 0 even when its numeric value is non-zero and even when its text contains
            machine check, parity error, uncorrectable, or FATAL.
            
            ALWAYS LABEL 0 FOR THESE STANDALONE BGL DIAGNOSTIC FIELDS
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
            
            ALSO LABEL 0
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
            
            LABEL 1 FOR EXPLICIT SYSTEM FAILURE
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
            EXACT ANOMALY RULES — LABEL 1
            ================================================================
            
            Label 1 for these exact standalone events:
            
            - data storage interrupt
            - machine check interrupt
            
            Label 1 for ciod node-map creation failures containing:
            
            - Cannot allocate memory
            - No child processes
            
            Label 1 for mailbox or control communication failures containing:
            
            - mailboxMonitor::serviceMailboxes and socket closed
            - failed to read message prefix on control stream
            - control stream closed unexpectedly
            - broken pipe
            
            Label 1 when the message starts with:
            
            - DDR machine check register:
            
            Label 1 for a non-zero process or service exit code.
            
            Examples:
            
            - exit code <NON_ZERO>
            - exit code 1
            - exit code -1
            - exit code 127
            
            Label 1 for:
            
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
            => label 1
            
            ================================================================
            EXACT NORMAL RULES — LABEL 0
            ================================================================
            
            Label 0 for these exact BGL messages:
            
            - RTS internal error
            - can not get assembly information for node card
            - node card is not fully functional
            - nodecard is not fully functional
            - ciod: pollControlDescriptors: detected the debugger died.
            
            Label 0 for node-map creation containing:
            
            - Bad file descriptor
            - Block device required
            
            Important distinction:
            
            - Cannot allocate memory => label 1
            - No child processes => label 1
            - Bad file descriptor => label 0
            - Block device required => label 0
            
            Label 0 for messages starting with:
            
            - RTS tree/torus link training failed:
            - RTS: bad message header:
            
            Label 0 for:
            
            - machine check: i-fetch ending in 0, 0x0, or <ZERO>
            - data store interrupt caused by dcbf ending in 0, 0x0, or <ZERO>
            - data store interrupt caused by icbi ending in 0, 0x0, or <ZERO>
            
            Label 0 for these program-interrupt messages when their final
            status is 0, 0x0, or <ZERO>:
            
            - program interrupt: illegal instruction
            - program interrupt: privileged instruction
            - program interrupt: trap instruction
            
            ================================================================
            STANDALONE DIAGNOSTIC CONTINUATION FIELDS — LABEL 0
            ================================================================
            
            Label 0 when the complete message starts with one of the following
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
            REGISTER DUMPS — LABEL 0
            ================================================================
            
            Label 0 when the complete message is only a sequence of register
            index/value pairs.
            
            Examples:
            
            - 0:00000000 1:81000000
            - 1:12345678 2:87654321
            - <NUM>:<HEX> <NUM>:<HEX>
            
            ================================================================
            CORRECTED AND USER-ENVIRONMENT CONDITIONS — LABEL 0
            ================================================================
            
            Label 0 when the complete message explicitly says:
            
            - detected and corrected
            - corrected error
            
            Do not use this rule if the same current line explicitly reports
            an additional unrecovered system failure.
            
            Label 0 for:
            
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
            => label 0
            
            ciod: LOGIN chdir(<PATH>) failed: Input/output error
            => label 1
            
            Label 0 when an NFS mount failure explicitly says it is retrying.
            
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
            => {"label":"1"}
            
            machine check status register: <HEX>
            => {"label":"0"}
            
            data storage interrupt
            => {"label":"1"}
            
            data store interrupt caused by dcbf ... <ZERO>
            => {"label":"0"}
            
            ciod: Error creating node map ... No child processes
            => {"label":"1"}
            
            ciod: Error creating node map ... Bad file descriptor
            => {"label":"0"}

            ciod: Error creating node map ... Block device required
            => {"label":"0"}
            
            ciod: LOGIN chdir(<PATH>) failed: Input/output error
            => {"label":"1"}
            
            ciod: LOGIN chdir(<PATH>) failed: No such file or directory
            => {"label":"0"}
            
            FINAL REQUIREMENT
            
            Return exactly one JSON object:
            
            {"label":"0"}
            
            or:
            
            {"label":"1"}
            
            Do not return explanations or additional text.
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
        return new PromptSpec(PromptExperiment.TEMPLATE_AWARE_FINAL, "BGL_TEMPLATE_AWARE_FINAL_V18_NODE_MAP_BLOCK_DEVICE", BGL_TEMPLATE_AWARE_FINAL_PROMPT);
    }

    private static PromptSpec guardEmbeddedPrompt() {
        return new PromptSpec(PromptExperiment.TEMPLATE_AWARE_GUARD_RULES_EMBEDDED, "BGL_PROMPT_ONLY_GUARD_RULES_EMBEDDED_V2_NODE_MAP_BLOCK_DEVICE", BGL_TEMPLATE_AWARE_GUARD_EMBEDDED_PROMPT);
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
