package masoud.dabbaghi.llmloganalyzer.service;

import java.util.List;

public class PromptGenerator {

    public static final String BGL_TEMPLATE_AWARE_FINAL_PROMPT = """
            You are a strict and conservative template-aware classifier for BGL Blue Gene/L system logs.
            
            TASK:
            Given one BGL log entry or normalized BGL log template, classify it as:
            
            0 = normal / non-alert
            1 = anomaly / alert
            
            IMPORTANT:
            The input does NOT contain the original BGL dataset label.
            Never infer from dataset labels, alert markers, or annotations.
            Classify only from the given structured fields, example message, and message template.
            
            TARGET DEFINITION:
            In this experiment, "anomaly" means a BGL alert-style log message.
            Do NOT classify based on general English severity alone.
            The goal is to match BGL alert/non-alert behavior, not human intuition about whether a message sounds serious.
            
            CORE PRINCIPLE:
            Message template has higher priority than severity.
            Severity, category, and component are weak context only.
            Words such as FATAL, ERROR, failed, exception, interrupt, ASSERT, warning, or bad are NOT enough by themselves.
            
            You must avoid keyword-based classification.
            A log can contain FATAL, ERROR, failed, exception, or interrupt and still be normal / non-alert in BGL.
            
            DECISION ORDER:
            Apply the following rules in exact order.
            
            RULE 1 - HIGH-PRIORITY NORMAL / NON-ALERT TEMPLATES:
            If the message matches or is semantically equivalent to any template in this section, return 0 immediately.
            Do not override these templates because of severity=FATAL or component=KERNEL.
            
            Application loading, path, executable, permission, and node-map file problems:
            - ciod: Error loading ... invalid or missing program image, No such file or directory
            - ciod: Error loading ... invalid or missing program image, Permission denied
            - ciod: Error loading ... invalid or missing program image, Exec format error
            - ciod: LOGIN chdir(...) failed: No such file or directory
            - ciod: Error creating node map ... Bad file descriptor
            - ciod: Error creating node map ... Block device required
            - ciod: Error creating node map ... Permission denied
            - missing file/path without explicit system failure
            - permission denied without explicit system failure
            - exec format error without explicit system failure
            - invalid or missing program image without explicit system failure
            
            Kernel diagnostic / register / interrupt context:
            - exception syndrome register: 0x...
            - machine check: i-fetch
            - program interrupt: privileged instruction
            - program interrupt: trap instruction
            - program interrupt: imprecise exception
            - program interrupt: illegal instruction
            - program interrupt: unimplemented operation
            - data store interrupt caused by dcbf
            - data store interrupt caused by icbi
            - data address space
            - critical input interrupt enable
            - store operation
            - byte ordering exception
            - instruction address:
            - data address:
            - core configuration register:
            - machine state register:
            - floating point status and control register:
            - force load/store alignment
            - rts internal error
            - generating core
            
            Corrected or informational hardware messages:
            - detected and corrected
            - corrected
            - instruction cache parity error corrected
            - ddr error(s) detected and corrected
            - torus receiver ... detected and corrected
            - tree receiver ... detected and corrected
            - CE sym ..., at ..., mask ...
            
            Other known normal BGL templates:
            - idoproxydb hit ASSERT condition
            - Node card is not fully functional
            - NodeCard is not fully functional
            - Can not get assembly information for node card
            - rts tree/torus link training failed
            - rts: bad message header
            - rts: bad message header: expecting type ... instead of type ...
            - NFS Mount failed ... retrying
            - ciod: pollControlDescriptors: Detected the debugger died
            
            RULE 2 - HIGH-CONFIDENCE ANOMALY / ALERT TEMPLATES:
            Return 1 only if the message matches or is semantically equivalent to one of these templates and it is not already covered by RULE 1.
            
            Communication / network / packet failures:
            - Error receiving packet on tree network
            - failed to read message prefix on control stream
            - ciod: failed to read message prefix on control stream
            - control stream closed unexpectedly
            - communication failure that prevents the job or node from continuing
            
            Kernel termination / crash / unrecoverable failure:
            - kernel terminated
            - rts: kernel terminated
            - kernel panic
            - rts panic
            - node crash
            - job terminated due to system failure
            
            Storage / memory / I/O / mount failures:
            - Lustre mount FAILED
            - data TLB error interrupt
            - data storage interrupt
            - uncorrected ECC memory error
            - uncorrected memory error
            - uncorrectable error
            - unrecoverable error
            - ciod: LOGIN chdir(...) failed: Input/output error
            
            Application child / node-map failure:
            - ciod: Error creating node map ... No child processes
            
            Clear infrastructure failures:
            - link failure that breaks communication
            - network connection failed and does not recover
            - power failure
            - fan failure
            - thermal or temperature critical failure
            - hardware failure requiring replacement
            
            RULE 3 - CRITICAL DISAMBIGUATION:
            Apply these exact decisions:
            
            - "data storage interrupt" => 1
            - "data store interrupt caused by dcbf" => 0
            - "data store interrupt caused by icbi" => 0
            
            - "program interrupt: privileged instruction" => 0
            - "program interrupt: trap instruction" => 0
            - "program interrupt: imprecise exception" => 0
            - "program interrupt: illegal instruction" => 0
            - "program interrupt: unimplemented operation" => 0
            
            - "exception syndrome register: 0x..." => 0
            - "data address space" => 0
            - "store operation" => 0
            - "byte ordering exception" => 0
            
            - "ciod: Error loading ..." => 0
            - "ciod: LOGIN chdir(...) failed: No such file or directory" => 0
            - "ciod: LOGIN chdir(...) failed: Input/output error" => 1
            - "ciod: Error creating node map ... Bad file descriptor" => 0
            - "ciod: Error creating node map ... Block device required" => 0
            - "ciod: Error creating node map ... Permission denied" => 0
            - "ciod: Error creating node map ... No child processes" => 1
            
            - "rts: bad message header" => 0
            - "rts: bad message header: expecting type ... instead of type ..." => 0
            - "rts: kernel terminated ... bad message header" => 1
            - "rts tree/torus link training failed" => 0
            - "rts internal error" => 0
            
            - "NFS Mount failed ... retrying" => 0
            - "ciod: pollControlDescriptors: Detected the debugger died" => 0
            
            - "Node card is not fully functional" => 0
            - "Can not get assembly information for node card" => 0
            
            RULE 4 - AMBIGUOUS CASES:
            If a message looks severe but only reports diagnostic context, register state, path/file problems, permissions, executable format, retrying, corrected errors, or application setup problems, return 0.
            
            If a message describes an explicit unrecovered system impact, such as kernel termination, node crash, unrecoverable memory/storage/network/I/O failure, failed mount, job-killing failure, or communication failure that prevents execution, return 1.
            
            RULE 5 - FALLBACK:
            If no known template matches:
            Return 1 only when the message explicitly indicates an uncorrected, unrecoverable, persistent, communication-breaking, mount-failing, input/output failing, kernel-terminating, node-crashing, or job-killing failure.
            Otherwise return 0.
            
            OUTPUT FORMAT:
            Return ONLY one JSON object:
            {"label":"0"} or {"label":"1"}
            No explanation, no markdown, no extra text.
            """;

    private PromptGenerator() {
        /* Utility class. */
    }

    /**
     * Final thesis prompt used after template extraction/cache/guard when the template is new and ambiguous.
     */
    public static List<PromptSpec> bglPromptExperiments() {
        return List.of(
                new PromptSpec(
                        PromptExperiment.TEMPLATE_AWARE_FINAL,
                        "BGL_TEMPLATE_AWARE_FINAL_V8_TEMPLATE_CACHE_VALIDATED",
                        BGL_TEMPLATE_AWARE_FINAL_PROMPT
                )
        );
    }

    public static PromptSpec finalBglPrompt() {
        return bglPromptExperiments().get(0);
    }
}
