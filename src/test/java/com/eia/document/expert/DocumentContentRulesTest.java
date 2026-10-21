package com.eia.document.expert;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentContentRulesTest {
    @Test
    void emptyContentIsA400RuleViolation() {
        assertThatThrownBy(() -> DocumentContentRules.validateSize(new byte[0]))
                .isInstanceOf(DocumentRuleException.class)
                .extracting(ex -> ((DocumentRuleException) ex).code())
                .isEqualTo(DocumentContentRules.EMPTY_CONTENT);
        assertThatThrownBy(() -> DocumentContentRules.validateSize(null))
                .isInstanceOf(DocumentRuleException.class)
                .extracting(ex -> ((DocumentRuleException) ex).code())
                .isEqualTo(DocumentContentRules.EMPTY_CONTENT);
    }

    @Test
    void exactlyTenMebibytesIsAcceptedAndOneByteMoreIs413() {
        DocumentContentRules.validateSize(new byte[DocumentContentRules.MAX_CONTENT_BYTES]);

        assertThatThrownBy(() -> DocumentContentRules.validateSize(
                new byte[DocumentContentRules.MAX_CONTENT_BYTES + 1]))
                .isInstanceOf(FileTooLargeException.class)
                .hasMessageContaining("maximum");
    }

    @Test
    void decodesUrlEncodedAndPlusSeparatedFileNames() {
        assertThat(DocumentContentRules.decodeFileName("Q3%20report%20%E2%82%AC.pdf"))
                .isEqualTo("Q3 report €.pdf");
        assertThat(DocumentContentRules.decodeFileName("Q3+report.pdf"))
                .isEqualTo("Q3 report.pdf");
        assertThat(DocumentContentRules.decodeFileName(null)).isNull();
    }

    @Test
    void rejectsMalformedOrUnsafeFileNames() {
        assertThatThrownBy(() -> DocumentContentRules.decodeFileName("bad%ZZname"))
                .isInstanceOf(DocumentRuleException.class)
                .extracting(ex -> ((DocumentRuleException) ex).code())
                .isEqualTo(DocumentContentRules.INVALID_FILE_NAME);

        assertThatThrownBy(() -> DocumentContentRules.requireFileName(null))
                .isInstanceOf(DocumentRuleException.class)
                .extracting(ex -> ((DocumentRuleException) ex).code())
                .isEqualTo(DocumentContentRules.FILE_NAME_REQUIRED);

        assertThatThrownBy(() -> DocumentContentRules.requireFileName("   "))
                .isInstanceOf(DocumentRuleException.class)
                .extracting(ex -> ((DocumentRuleException) ex).code())
                .isEqualTo(DocumentContentRules.FILE_NAME_REQUIRED);

        assertThatThrownBy(() -> DocumentContentRules.requireFileName("bad\r\nname.pdf"))
                .isInstanceOf(DocumentRuleException.class)
                .extracting(ex -> ((DocumentRuleException) ex).code())
                .isEqualTo(DocumentContentRules.INVALID_FILE_NAME);

        assertThatThrownBy(() -> DocumentContentRules.requireFileName("x".repeat(256)))
                .isInstanceOf(DocumentRuleException.class)
                .extracting(ex -> ((DocumentRuleException) ex).code())
                .isEqualTo(DocumentContentRules.FILE_NAME_TOO_LONG);
    }

    @Test
    void stripsDirectoryComponentsFromTheDisplayName() {
        assertThat(DocumentContentRules.requireFileName("/tmp/report.pdf")).isEqualTo("report.pdf");
        assertThat(DocumentContentRules.requireFileName("C:\\temp\\report.pdf")).isEqualTo("report.pdf");
        assertThat(DocumentContentRules.requireFileName("  report.pdf  ")).isEqualTo("report.pdf");
    }

    @Test
    void defaultsMissingContentTypeAndBoundsTheStoredValue() {
        assertThat(DocumentContentRules.requireContentType(null))
                .isEqualTo(DocumentContentRules.DEFAULT_CONTENT_TYPE);
        assertThat(DocumentContentRules.requireContentType("  "))
                .isEqualTo(DocumentContentRules.DEFAULT_CONTENT_TYPE);
        assertThat(DocumentContentRules.requireContentType(" application/pdf "))
                .isEqualTo("application/pdf");
        assertThat(DocumentContentRules.requireContentType("application/pdf;charset=UTF-8"))
                .isEqualTo("application/pdf");
        assertThat(DocumentContentRules.requireContentType("TEXT/PLAIN"))
                .isEqualTo("text/plain");

        assertThatThrownBy(() -> DocumentContentRules.requireContentType(
                "application/" + "x".repeat(129)))
                .isInstanceOf(DocumentRuleException.class)
                .extracting(ex -> ((DocumentRuleException) ex).code())
                .isEqualTo(DocumentContentRules.CONTENT_TYPE_TOO_LONG);

        assertThatThrownBy(() -> DocumentContentRules.requireContentType("not-a-mime"))
                .isInstanceOf(DocumentRuleException.class)
                .extracting(ex -> ((DocumentRuleException) ex).code())
                .isEqualTo(DocumentContentRules.INVALID_CONTENT_TYPE);

        assertThatThrownBy(() -> DocumentContentRules.requireContentType("text/plain\r\nx"))
                .isInstanceOf(DocumentRuleException.class)
                .extracting(ex -> ((DocumentRuleException) ex).code())
                .isEqualTo(DocumentContentRules.INVALID_CONTENT_TYPE);
    }

    @Test
    void hashesTheExactBytesAsLowercaseSha256() {
        assertThat(DocumentContentRules.sha256Hex("hello".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
                .hasSize(64);
    }
}
