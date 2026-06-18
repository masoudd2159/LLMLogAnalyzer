package masoud.dabbaghi.llmloganalyzer.service;

import java.util.List;

public class PromptGenerator {

    public static final String BGL_TEMPLATE_AWARE_FINAL_PROMPT = """
            You are a BGL Blue Gene/L alert classifier for log-template anomaly detection.
            Classify exactly one label:
            0 = normal / non-alert
            1 = anomaly / alert
            
            The original BGL dataset label is not present in the input.
            Use only category, component, severity, message_template, and the example message.
            
            Core rule:
            Decide from the operational meaning of the template, not from single words.
            Severity words such as FATAL, ERROR, WARNING, failed, interrupt, exception,
            timeout, parity, unavailable, and uncorrectable are evidence only when the
            message describes a real system/runtime alert.
            
            Use this decision order:
            
            1) Return 1 for strong BGL anomaly / alert templates:
            - data TLB error interrupt
            - data storage interrupt
            - machine check interrupt, except the known diagnostic phrase "machine check: i-fetch"
            - kernel terminated, kernel panic, rts panic, node crash, node failed, or job terminated by system/runtime failure
            - Lustre mount FAILED
            - failed to read message prefix on control stream
            - control stream closed unexpectedly or Broken pipe
            - Error receiving packet on tree network
            - ciod Error creating node map with No child processes, Resource temporarily unavailable, or Device or resource busy
            - ciod LOGIN chdir(...) failed: Input/output error
            - ciod Error loading ... Input/output error
            - unrecoverable system, hardware, memory, storage, network, or runtime failure
            - power, fan, thermal, temperature, link, or hardware failure that prevents normal operation
            
            2) Return 0 for known BGL normal / non-alert templates:
            - corrected or detected-and-corrected errors
            - diagnostic/register/context lines such as exception syndrome register,
              machine state register, instruction address, data address, core configuration
              register, floating point status, generating core, or numeric register dumps
            - program interrupt caused by privileged instruction, trap instruction,
              illegal instruction, imprecise exception, or unimplemented operation
            - data store interrupt caused by dcbf or icbi
            - machine check: i-fetch
            - ciod invalid/missing program image with No such file, Permission denied, or Exec format error
            - ciod LOGIN chdir(...) failed with No such file or Permission denied
            - ciod Error creating node map with Bad file descriptor, Block device required, or Permission denied
            - ciod Error opening node map file with No such file or directory
            - NFS Mount failed ... retrying
            - rts bad message header, rts internal error, or link training diagnostic without runtime impact
            - ASSERT condition without explicit unrecovered runtime impact
            - DDR excessive soft failures with only a replacement recommendation and no immediate failure
            
            3) Conflict rule:
            Specific anomaly evidence overrides generic normal-looking text.
            Do not mark these as normal just because they contain a path, permission-like text,
            INFO/ERROR severity, or the word interrupt:
            - data storage interrupt
            - data TLB error interrupt
            - Input/output error
            - Lustre mount FAILED
            - control stream failure
            - kernel terminated / panic
            - node crash / node failed / job terminated
            
            4) Ambiguity rule:
            If the template only describes a user/program/path/configuration problem,
            a diagnostic dump, or a corrected condition, return 0.
            If it describes a runtime, communication, filesystem mount, kernel, node,
            hardware, or unrecovered execution failure, return 1.
            
            Output only one JSON object: {"label":"0"} or {"label":"1"}
            """;

    private PromptGenerator() {
        /* Utility class. */
    }

    public static List<PromptSpec> bglPromptExperiments() {
        return List.of(
                new PromptSpec(
                        PromptExperiment.TEMPLATE_AWARE_FINAL,
                        "BGL_TEMPLATE_AWARE_FINAL_V12_RECALL_BALANCED",
                        BGL_TEMPLATE_AWARE_FINAL_PROMPT
                )
        );
    }

    public static PromptSpec finalBglPrompt() {
        return bglPromptExperiments().get(0);
    }
}
