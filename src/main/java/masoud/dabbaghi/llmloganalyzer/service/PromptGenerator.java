package masoud.dabbaghi.llmloganalyzer.service;

import java.util.List;

public class PromptGenerator {

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
              invalid executable, program image too big, or debugger died
            
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
            
            DECISION PROCEDURE
            1. Determine whether the current line is a primary event or only a diagnostic field.
            2. Look for explicit system, node, kernel/runtime, storage, network, mount, or job impact.
            3. Check whether the condition was corrected, retried, user-caused, or environment-caused.
            4. Apply the exact BGL distinctions above.
            5. If explicit system-level impact is absent and the line only reports internal state,
               return 0. Do not invent impact and do not use class frequency.
            """;

    private PromptGenerator() {
    }

    public static List<PromptSpec> bglPromptExperiments() {
        return List.of(new PromptSpec(
                PromptExperiment.TEMPLATE_AWARE_FINAL,
                "BGL_TEMPLATE_AWARE_FINAL_V17_MACHINE_CHECK_FIELDS",
                BGL_TEMPLATE_AWARE_FINAL_PROMPT
        ));
    }

    public static PromptSpec finalBglPrompt() {
        return bglPromptExperiments().get(0);
    }
}
