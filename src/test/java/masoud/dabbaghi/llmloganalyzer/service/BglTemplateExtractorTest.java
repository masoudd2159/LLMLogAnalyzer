package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.dto.LogBglEntryDto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BglTemplateExtractorTest {

    @Test
    void sameMessageWithDifferentNodeAndNumbersProducesSameTemplateKey() {
        LogBglEntryDto first = baseDto()
                .setMessage("R02-M1-N0-C:J12-U11 machine check: i-fetch at 0x00004be0");

        LogBglEntryDto second = baseDto()
                .setMessage("R77-M0-N4-C:J18-U01 machine check: i-fetch at 0xABCDEF12");

        BglTemplate firstTemplate = BglTemplateExtractor.extract(first);
        BglTemplate secondTemplate = BglTemplateExtractor.extract(second);

        assertEquals(firstTemplate.templateKey(), secondTemplate.templateKey());
        assertTrue(firstTemplate.normalizedMessage().contains("<NODE>"));
        assertTrue(firstTemplate.normalizedMessage().contains("<HEX>"));
    }

    @Test
    void correctedAndUncorrectedMessagesDoNotCollapseIntoSameTemplate() {
        LogBglEntryDto corrected = baseDto()
                .setMessage("ddr errors detected and corrected at 0x00001234");

        LogBglEntryDto uncorrected = baseDto()
                .setMessage("uncorrected ECC memory error at 0x00001234");

        assertNotEquals(
                BglTemplateExtractor.extract(corrected).templateKey(),
                BglTemplateExtractor.extract(uncorrected).templateKey()
        );
    }

    private LogBglEntryDto baseDto() {
        return new LogBglEntryDto()
                .setCategory("KERNEL")
                .setComponent("KERNEL")
                .setSeverity("FATAL")
                .setLabel("-");
    }
}
