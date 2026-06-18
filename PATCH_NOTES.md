# LLMLogAnalyzer V8 Fix Patch

## What changed

1. Fixed the `ciod: LOGIN chdir(...) failed: Input/output error` case.
    - Added it as a high-confidence anomaly in `BglTemplateGuard`.
    - Added it as a strong anomaly signal in `BglTemplateValidationService`.
    - Removed the over-broad `ciod: LOGIN chdir` normal validation rule.
    - Kept only `ciod: LOGIN chdir(...) failed: No such file or directory` as known-normal.

2. Updated prompt version:
    - `BGL_TEMPLATE_AWARE_FINAL_V8_TEMPLATE_CACHE_VALIDATED`
    - Added explicit disambiguation:
        - `ciod: LOGIN chdir(...) failed: No such file or directory => 0`
        - `ciod: LOGIN chdir(...) failed: Input/output error => 1`

3. Updated charts:
    - Colorful per-bar palette.
    - Larger chart output size.
    - Cleaner background/grid/axis styling.
    - Item labels displayed on bars.

4. Updated README:
    - Replaced old prompt-only explanation with the current template-cache hybrid method.
    - Added error propagation control explanation.
    - Added guard/cache/validation architecture.
    - Added runtime optimization example based on your BGL_2k run.

## How to apply

Copy the files in this ZIP over your repository root.

Then clear old records before testing:

```javascript
db.log_evaluations.deleteMany({})
```

Run again:

```bash
mvn test
mvn spring-boot:run
```

Then regenerate charts through your existing charts profile/endpoint.
