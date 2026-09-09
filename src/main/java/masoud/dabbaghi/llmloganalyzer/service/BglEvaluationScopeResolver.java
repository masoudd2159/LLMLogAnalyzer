package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.evaluation.BglEvaluationScope;

/** Resolves the authoritative evaluation scope from the completed dataset preflight. */
public final class BglEvaluationScopeResolver {
    private BglEvaluationScopeResolver() {
    }

    public static BglEvaluationPlan resolve(BglDatasetPreflightReport preflight,
                                            boolean callerProvidedLimit,
                                            Long requestedLimit) {
        if (preflight == null) {
            throw new IllegalArgumentException("Dataset preflight report is required");
        }
        if (preflight.rawLines() <= 0) {
            throw new IllegalStateException("BGL preflight found zero dataset lines; inference was not started");
        }

        if (!callerProvidedLimit) {
            requireCompleteParserCoverage(preflight);
            return new BglEvaluationPlan(
                    BglEvaluationScope.FULL_DATASET,
                    true,
                    preflight.rawLines(),
                    null,
                    preflight.rawLines()
            );
        }

        if (requestedLimit == null || requestedLimit <= 0) {
            throw new IllegalArgumentException("Explicit BGL_MAX_RECORDS must be a positive integer");
        }
        if (requestedLimit > preflight.rawLines()) {
            throw new IllegalArgumentException(
                    "Requested BGL_MAX_RECORDS=" + requestedLimit
                            + " exceeds preflight.rawLines=" + preflight.rawLines()
                            + "; the value will not be silently clamped"
            );
        }
        if (requestedLimit == preflight.rawLines()) {
            requireCompleteParserCoverage(preflight);
            return new BglEvaluationPlan(
                    BglEvaluationScope.FULL_DATASET,
                    true,
                    preflight.rawLines(),
                    requestedLimit,
                    requestedLimit
            );
        }
        return new BglEvaluationPlan(
                BglEvaluationScope.LIMITED_FIRST_N,
                false,
                preflight.rawLines(),
                requestedLimit,
                requestedLimit
        );
    }

    private static void requireCompleteParserCoverage(BglDatasetPreflightReport preflight) {
        if (preflight.parseErrors() != 0 || preflight.parsedLines() != preflight.rawLines()) {
            throw new IllegalStateException(
                    "Full-dataset thesis evaluation requires zero BGL parser errors."
                            + System.lineSeparator() + "Raw lines: " + preflight.rawLines()
                            + System.lineSeparator() + "Parsed lines: " + preflight.parsedLines()
                            + System.lineSeparator() + "Parse errors: " + preflight.parseErrors()
                            + System.lineSeparator() + "Hybrid inference was not started."
            );
        }
    }
}
