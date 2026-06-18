# LLMLogAnalyzer - Template Cache + LLM Validation Patch

این پچ روش پیشنهادی را به پروژه اضافه می‌کند:

```text
Raw BGL Log
   ↓
BglTemplateExtractor
   ↓
Template Cache lookup
   ↓
Cache hit? → reuse previous result without LLM
   ↓
Guard hit? → deterministic result + cache
   ↓
New/ambiguous template? → LLM
   ↓
BglTemplateValidationService
   ↓
Only valid and non-suspicious LLM results are cached
```

## فایل‌های جدید

- `service/BglTemplate.java`
- `service/BglTemplateExtractor.java`
- `service/BglCachedClassification.java`
- `service/BglTemplateClassificationCache.java`
- `service/BglTemplateValidationResult.java`
- `service/BglTemplateValidationService.java`
- `test/service/BglTemplateExtractorTest.java`
- `test/service/BglTemplateValidationServiceTest.java`

## فایل‌های تغییر کرده

- `service/BglParser.java`
- `service/PromptGenerator.java`
- `evaluation/BglDecisionSource.java`
- `evaluation/LogEvaluation.java`
- `resources/application.properties`

## نکته مهم درباره خطای LLM

اگر LLM برای یک template جواب مشکوک بدهد، نتیجه برای همان لاگ در MongoDB ذخیره می‌شود، اما وارد cache نمی‌شود. بنابراین آن جواب اشتباه برای همیشه روی همه لاگ‌های مشابه تکرار نمی‌شود.

فیلدهای جدید MongoDB برای تحلیل پایان‌نامه:

- `templateKey`
- `normalizedTemplate`
- `cacheHit`
- `cacheSource`
- `cacheable`
- `validationStatus`
- `validationReason`

## تنظیمات جدید application.properties

```properties
bgl.classification.template-cache.enabled=true
bgl.classification.template-guard.enabled=true
bgl.classification.cache-only-validated-llm-results=true
```

## تستی که انجام شد

به دلیل اینکه محیط اجرا به GitHub/Maven Central دسترسی شبکه مستقیم نداشت و `mvn` هم نصب نبود، بیلد کامل Spring Boot/Maven اجرا نشد. اما من منطق اصلی مستقل از Spring را با `javac` تست کردم:

- دو لاگ با node/hex متفاوت به یک templateKey تبدیل شدند.
- `corrected` و `uncorrected` اشتباهاً یکی نشدند.
- خروجی مشکوک LLM برای templateهای قوی anomaly وارد cache نشد.
- خروجی معتبر غیرمتعارض cacheable شد.

نتیجه تست محلی:

```text
OK: template extractor and validation tests passed
```

## روش اعمال

محتویات این پوشه را روی پروژه اصلی کپی کن. مسیرها دقیقاً مطابق مسیرهای پروژه هستند. بعد اجرا کن:

```bash
mvn test
mvn spring-boot:run
```

یا اگر می‌خواهی فقط parsing را اجرا کنی، profile مربوط به parser را فعال کن.
