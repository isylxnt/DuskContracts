package dev.isylxnt.duskcontracts.localization;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

final class MessageFormattingTest {
    @Test
    void convertsHexAndLegacyAmpersandColorsToMiniMessage() {
        assertThat(LegacyFormatting.normalize("&#9863E7⏵ DuskContracts &8| "))
                .isEqualTo("<#9863E7>⏵ DuskContracts <dark_gray>| ");
    }

    @Test
    void leavesNativeMiniMessageFormattingAvailable() {
        assertThat(LegacyFormatting.normalize("<gold>Reward</gold>"))
                .isEqualTo("<gold>Reward</gold>");
    }

    @Test
    void convertsBracePlaceholdersToMiniMessageTags() {
        assertThat(LegacyFormatting.normalize("Invalid: {reason}; id={contract_id}", java.util.List.of("reason", "contract_id")))
                .isEqualTo("Invalid: <reason>; id=<contract_id>");
    }

    @Test
    void bundledDiagnosticsUseMiniMessageNewlinesInsteadOfLiteralEscapes() throws Exception {
        for (String resource : java.util.List.of("/lang/messages_en.yml", "/lang/messages_es.yml")) {
            try (var input = MessageFormattingTest.class.getResourceAsStream(resource)) {
                assertThat(input).isNotNull();
                String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(yaml).contains("admin.doctor:", "<newline>");
                assertThat(yaml).doesNotContain("\\n<gray>");
            }
        }
    }
}
