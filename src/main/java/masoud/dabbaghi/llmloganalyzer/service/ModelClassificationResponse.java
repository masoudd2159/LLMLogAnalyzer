package masoud.dabbaghi.llmloganalyzer.service;

public record ModelClassificationResponse(
        String label,
        String rawOutput,
        boolean valid
) {

    public static ModelClassificationResponse valid(String label, String rawOutput) {
        return new ModelClassificationResponse(label, rawOutput, true);
    }

    public static ModelClassificationResponse invalid(String rawOutput) {
        return new ModelClassificationResponse("INVALID", rawOutput, false);
    }
}