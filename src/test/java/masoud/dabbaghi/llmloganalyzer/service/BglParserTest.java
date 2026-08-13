package masoud.dabbaghi.llmloganalyzer.service;

import masoud.dabbaghi.llmloganalyzer.dto.LogBglEntryDto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BglParserTest {

    @Test
    void parsesCanonicalBglLineWithSecondLocation() {
        String line = "- 1117838570 2005.06.03 R02-M1-N0-C:J12-U11 "
                + "2005-06-03-15.42.50.363779 R02-M1-N0-C:J12-U11 "
                + "RAS KERNEL INFO instruction cache parity error corrected";

        LogBglEntryDto dto = BglParser.parseLine(line);

        assertEquals("R02-M1-N0-C:J12-U11", dto.getLocation2());
        assertEquals("RAS", dto.getCategory());
        assertEquals("KERNEL", dto.getComponent());
        assertEquals("INFO", dto.getSeverity());
        assertEquals("instruction cache parity error corrected", dto.getMessage());
    }

    @Test
    void parsesValidBglVariantWithoutSecondLocation() {
        String line = "- 1119415930 2005.06.21 - 2005-06-21-21.52.10.214285 "
                + "RAS KERNEL FATAL Kill job 20251 timed out. Block freed.";

        LogBglEntryDto dto = BglParser.parseLine(line);

        assertNull(dto.getLocation2());
        assertEquals("RAS", dto.getCategory());
        assertEquals("KERNEL", dto.getComponent());
        assertEquals("FATAL", dto.getSeverity());
        assertEquals("Kill job 20251 timed out. Block freed.", dto.getMessage());
    }

    @Test
    void rejectsMalformedLine() {
        assertNull(BglParser.parseLine("not a BGL record"));
    }

    @Test
    void parsesValidBglLineWithEmptyMessage() {
        String line = "- 1120866514 2005.07.08 R02-M1-N8-C:J13-U01 "
                + "2005-07-08-16.48.34.004234 R02-M1-N8-C:J13-U01 RAS KERNEL FATAL";

        LogBglEntryDto dto = BglParser.parseLine(line);

        assertEquals("FATAL", dto.getSeverity());
        assertEquals("", dto.getMessage());
    }

    @Test
    void parsesComponentNamesContainingUnderscores() {
        String line = "- 1123019408 2005.08.02 UNKNOWN_LOCATION "
                + "2005-08-02-14.50.08.391673 UNKNOWN_LOCATION NULL SERV_NET WARNING "
                + "DeclareServiceNetworkCharacteristics has been run but the DB is not empty";

        LogBglEntryDto dto = BglParser.parseLine(line);

        assertEquals("NULL", dto.getCategory());
        assertEquals("SERV_NET", dto.getComponent());
        assertEquals("WARNING", dto.getSeverity());
    }

    @Test
    void recoversRowWhoseSecondLocationContainsSpaces() {
        String line = "- 1133447861 2005.12.01 - 2005-12-01-06.37.41.417709 "
                + "time for a single instance of a correctable ddr. RAS KERNEL INFO "
                + "0 microseconds spent in the rbs signal handler during 0 calls.";

        LogBglEntryDto dto = BglParser.parseLine(line);

        assertEquals("time for a single instance of a correctable ddr.", dto.getLocation2());
        assertEquals("RAS", dto.getCategory());
        assertEquals("KERNEL", dto.getComponent());
        assertEquals("INFO", dto.getSeverity());
    }
}
