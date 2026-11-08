package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Raw input becomes a decision command only through parse (command 2). */
class RecordComplianceDecisionCommandParseTest {

    private static final String REASON = "Analyst reviewed the alert and the documents";

    private static Result<RecordComplianceDecisionCommand, RecordComplianceDecisionError> parse(
            String number, String kind, String justification, Long version) {
        return RecordComplianceDecisionCommand.parse(
                new RawRecordComplianceDecision(number, kind, justification, version));
    }

    @Test
    @DisplayName("a valid request becomes a command with a parsed customer, kind and version")
    void shouldParseAValidRequest() {
        var command = parse("C-1000", "FREEZE_ASSETS", REASON, 5L).toOptional().orElseThrow();

        assertThat(command.customerNumber().value()).isEqualTo("C-1000");
        assertThat(command.kind()).isEqualTo(DecisionKind.FREEZE_ASSETS);
        assertThat(command.versionViewed()).isEqualTo(new RiskProfileVersion(5L));
        assertThat(command.justification().toOptional().map(Justification::value)).contains(REASON);
    }

    @Test
    @DisplayName("an absent, unknown or lower-case kind is not recognised, even when the customer number is also wrong (BR-37)")
    void shouldJudgeTheKindFirst() {
        for (var kind : new String[]{null, "", "UNFREEZE", "freeze_assets"}) {
            assertThat(parse("", kind, REASON, 1L))
                    .isEqualTo(Result.failure(new RecordComplianceDecisionError.DecisionKindNotRecognised()));
        }
    }

    @Test
    @DisplayName("a text that cannot be a customer number names no customer: it is customer not found (BR-04)")
    void shouldTreatAnImpossibleNumberAsNotFound() {
        for (var number : new String[]{null, "", "n".repeat(65), "ab\tc"}) {
            assertThat(parse(number, "FREEZE_ASSETS", REASON, 1L))
                    .isEqualTo(Result.failure(new RecordComplianceDecisionError.CustomerNotFound()));
        }
    }

    @Test
    @DisplayName("the justification is parsed but not judged here: the customer is judged first (BR-37)")
    void shouldKeepTheJustificationAsAResult() {
        var command = parse("C-1000", "FREEZE_ASSETS", "short", 1L).toOptional().orElseThrow();

        assertThat(command.justification()).isEqualTo(Result.failure(new JustificationError.TooShort(20, 5)));
        assertThat(parse("C-1000", "FREEZE_ASSETS", null, 1L).toOptional().orElseThrow().justification())
                .isEqualTo(Result.failure(new JustificationError.Missing()));
    }

    @Test
    @DisplayName("a missing version is carried as version 0, which the use case refuses as not valid (BR-33, D-12)")
    void shouldTreatAMissingVersionAsZero() {
        var command = parse("C-1000", "CLEAR_FALSE_POSITIVE", REASON, null).toOptional().orElseThrow();

        assertThat(command.versionViewed()).isEqualTo(new RiskProfileVersion(0L));
    }

    @Test
    @DisplayName("a decision command is confidential: its toString shows no input (BR-43)")
    void shouldNeverPrintTheCommand() {
        var command = parse("C-1000", "FREEZE_ASSETS", REASON, 5L).toOptional().orElseThrow();

        assertThat(command.toString()).doesNotContain("FREEZE").doesNotContain("C-1000").doesNotContain("Analyst");
        assertThat(new RawRecordComplianceDecision("C-1000", "FREEZE_ASSETS", REASON, 5L).toString())
                .doesNotContain("Analyst").doesNotContain("FREEZE");
    }
}
