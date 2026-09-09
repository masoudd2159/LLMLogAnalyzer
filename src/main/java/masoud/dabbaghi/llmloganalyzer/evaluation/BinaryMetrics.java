package masoud.dabbaghi.llmloganalyzer.evaluation;

/** Shared, zero-safe binary classification formulas used by every thesis report. */
public record BinaryMetrics(
        long truePositive,
        long trueNegative,
        long falsePositive,
        long falseNegative,
        double accuracy,
        double precision,
        double recall,
        double f1,
        double specificity,
        double falsePositiveRate,
        double falseNegativeRate,
        double negativePredictiveValue,
        double balancedAccuracy,
        double mcc
) {
    public static BinaryMetrics from(long tp, long tn, long fp, long fn) {
        double accuracy = divide(tp + tn, tp + tn + fp + fn);
        double precision = divide(tp, tp + fp);
        double recall = divide(tp, tp + fn);
        double specificity = divide(tn, tn + fp);
        double fpr = divide(fp, fp + tn);
        double fnr = divide(fn, fn + tp);
        double npv = divide(tn, tn + fn);
        double f1 = divide(2.0 * precision * recall, precision + recall);
        double denominator = Math.sqrt((double) (tp + fp) * (tp + fn) * (tn + fp) * (tn + fn));
        double mcc = denominator == 0.0 ? 0.0 : ((double) tp * tn - (double) fp * fn) / denominator;
        return new BinaryMetrics(tp, tn, fp, fn, accuracy, precision, recall, f1,
                specificity, fpr, fnr, npv, (recall + specificity) / 2.0, mcc);
    }

    /** Undefined zero-denominator ratios are reported as 0 and never NaN/Infinity. */
    public static double divide(double numerator, double denominator) {
        return denominator == 0.0 ? 0.0 : numerator / denominator;
    }
}
