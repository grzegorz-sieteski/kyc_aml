package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.gs5.kycaml.risk.domain.RiskFixtures.rated;
import static org.assertj.core.api.Assertions.assertThat;

/** Raw input becomes a command only through parse, and the first failure is the one returned (command 1). */
class EvaluateCustomerRiskCommandParseTest {

    private static final RawRatedCountry DE_LOW = rated("DE", "LOW");

    /** Every argument valid; a test replaces the one it is about. */
    private static RawEvaluateCustomerRisk raw(
            String number, String profile, RawRatedCountry residence, List<RawRatedCountry> citizenships,
            RawRatedCountry birth, String login, String channel, Boolean productRequest, String media,
            Boolean sanctions, Boolean terrorist, Boolean forged) {
        return new RawEvaluateCustomerRisk(number, profile, residence, citizenships, birth, login, channel,
                productRequest, media, sanctions, terrorist, forged);
    }

    private static RawEvaluateCustomerRisk valid() {
        return raw("C-1000", "POLITICALLY_EXPOSED", rated("FR", "FATF_GREY_LIST"),
                List.of(rated("DE", "LOW"), rated("TR", "FATF_GREY_LIST")), rated("PL", "LOW"),
                "AE", "REMOTE", true, "UNCONFIRMED_FINANCIAL_CRIME", false, true, false);
    }

    private static Result<EvaluateCustomerRiskCommand, EvaluateCustomerRiskError> failing(Object error) {
        return Result.failure((EvaluateCustomerRiskError) error);
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    @DisplayName("a complete, valid request becomes a command carrying parsed values")
    void shouldParseAValidRequest() {
        var command = EvaluateCustomerRiskCommand.parse(valid()).toOptional().orElseThrow();

        assertThat(command.customerNumber().value()).isEqualTo("C-1000");
        assertThat(command.profileClass()).isEqualTo(CustomerProfileClass.POLITICALLY_EXPOSED);
        assertThat(command.residence().code().value()).isEqualTo("FR");
        assertThat(command.residence().riskClass()).isEqualTo(GeographicRiskClass.FATF_GREY_LIST);
        assertThat(command.citizenships()).extracting(c -> c.code().value()).containsExactly("DE", "TR");
        assertThat(command.birth().code().value()).isEqualTo("PL");
        assertThat(command.loginCountry().value()).isEqualTo("AE");
        assertThat(command.channel()).isEqualTo(OnboardingChannel.REMOTE);
        assertThat(command.highRiskProductRequest()).isTrue();
        assertThat(command.adverseMedia()).isEqualTo(AdverseMediaClass.UNCONFIRMED_FINANCIAL_CRIME);
        assertThat(command.criticalFacts()).isEqualTo(new CriticalFacts(false, true, false));
    }

    @Test
    @DisplayName("a command is confidential: its toString shows no input (BR-43)")
    void shouldNeverPrintTheCommand() {
        var command = EvaluateCustomerRiskCommand.parse(valid()).toOptional().orElseThrow();

        assertThat(command.toString()).doesNotContain("POLITICALLY").doesNotContain("FR").doesNotContain("REMOTE");
        assertThat(valid().toString()).doesNotContain("POLITICALLY").doesNotContain("C-1000");
    }

    // ----------------------------------------------------------------- one refusal each

    @Test
    @DisplayName("an absent or empty customer number is missing (BR-06)")
    void shouldRefuseAMissingCustomerNumber() {
        var base = valid();
        assertThat(EvaluateCustomerRiskCommand.parse(raw(null, base.profileClass(), base.residence(),
                base.citizenships(), base.birth(), base.loginCountry(), base.channel(), base.highRiskProductRequest(),
                base.adverseMediaClass(), base.sanctionsMatch(), base.terroristLink(), base.forgedIdentity())))
                .isEqualTo(failing(new EvaluateCustomerRiskError.CustomerNumberMissing()));
        assertThat(EvaluateCustomerRiskCommand.parse(raw("", base.profileClass(), base.residence(),
                base.citizenships(), base.birth(), base.loginCountry(), base.channel(), base.highRiskProductRequest(),
                base.adverseMediaClass(), base.sanctionsMatch(), base.terroristLink(), base.forgedIdentity())))
                .isEqualTo(failing(new EvaluateCustomerRiskError.CustomerNumberMissing()));
    }

    @Test
    @DisplayName("a customer number over 64 code points, or with a control character, is refused with the numbers of the cause (BR-06)")
    void shouldRefuseABadCustomerNumber() {
        var base = valid();
        assertThat(EvaluateCustomerRiskCommand.parse(raw("n".repeat(65), base.profileClass(), base.residence(),
                base.citizenships(), base.birth(), base.loginCountry(), base.channel(), base.highRiskProductRequest(),
                base.adverseMediaClass(), base.sanctionsMatch(), base.terroristLink(), base.forgedIdentity())))
                .isEqualTo(failing(new EvaluateCustomerRiskError.CustomerNumberTooLong(64, 65)));
        assertThat(EvaluateCustomerRiskCommand.parse(raw("ab\tc", base.profileClass(), base.residence(),
                base.citizenships(), base.birth(), base.loginCountry(), base.channel(), base.highRiskProductRequest(),
                base.adverseMediaClass(), base.sanctionsMatch(), base.terroristLink(), base.forgedIdentity())))
                .isEqualTo(failing(new EvaluateCustomerRiskError.CustomerNumberControlCharacter(0x09, 2)));
    }

    @Test
    @DisplayName("a profile class that is absent, unknown or in the wrong case is not recognised (BR-07)")
    void shouldRefuseAnUnrecognisedProfileClass() {
        for (var profile : new String[]{null, "", "COMPANY", "individual_employed"}) {
            var base = valid();
            assertThat(EvaluateCustomerRiskCommand.parse(raw(base.customerNumber(), profile, base.residence(),
                    base.citizenships(), base.birth(), base.loginCountry(), base.channel(),
                    base.highRiskProductRequest(), base.adverseMediaClass(), base.sanctionsMatch(),
                    base.terroristLink(), base.forgedIdentity())))
                    .isEqualTo(failing(new EvaluateCustomerRiskError.ProfileClassNotRecognised()));
        }
    }

    @Test
    @DisplayName("a country that is absent or not an ISO alpha-2 code is refused, naming which country (BR-08)")
    void shouldNameTheInvalidCountry() {
        var base = valid();
        assertThat(EvaluateCustomerRiskCommand.parse(raw(base.customerNumber(), base.profileClass(), null,
                base.citizenships(), base.birth(), base.loginCountry(), base.channel(), base.highRiskProductRequest(),
                base.adverseMediaClass(), base.sanctionsMatch(), base.terroristLink(), base.forgedIdentity())))
                .isEqualTo(failing(new EvaluateCustomerRiskError.CountryInvalid(CountryField.RESIDENCE)));
        assertThat(EvaluateCustomerRiskCommand.parse(raw(base.customerNumber(), base.profileClass(),
                rated("XX", "LOW"), base.citizenships(), base.birth(), base.loginCountry(), base.channel(),
                base.highRiskProductRequest(), base.adverseMediaClass(), base.sanctionsMatch(),
                base.terroristLink(), base.forgedIdentity())))
                .isEqualTo(failing(new EvaluateCustomerRiskError.CountryInvalid(CountryField.RESIDENCE)));
        assertThat(EvaluateCustomerRiskCommand.parse(raw(base.customerNumber(), base.profileClass(),
                base.residence(), List.of(rated("DE", "LOW"), rated("de", "LOW")), base.birth(),
                base.loginCountry(), base.channel(), base.highRiskProductRequest(), base.adverseMediaClass(),
                base.sanctionsMatch(), base.terroristLink(), base.forgedIdentity())))
                .isEqualTo(failing(new EvaluateCustomerRiskError.CountryInvalid(CountryField.CITIZENSHIP)));
        assertThat(EvaluateCustomerRiskCommand.parse(raw(base.customerNumber(), base.profileClass(),
                base.residence(), base.citizenships(), rated("ZZ", "LOW"), base.loginCountry(), base.channel(),
                base.highRiskProductRequest(), base.adverseMediaClass(), base.sanctionsMatch(),
                base.terroristLink(), base.forgedIdentity())))
                .isEqualTo(failing(new EvaluateCustomerRiskError.CountryInvalid(CountryField.BIRTH)));
        assertThat(EvaluateCustomerRiskCommand.parse(raw(base.customerNumber(), base.profileClass(),
                base.residence(), base.citizenships(), base.birth(), null, base.channel(),
                base.highRiskProductRequest(), base.adverseMediaClass(), base.sanctionsMatch(),
                base.terroristLink(), base.forgedIdentity())))
                .isEqualTo(failing(new EvaluateCustomerRiskError.CountryInvalid(CountryField.LOGIN)));
    }

    @Test
    @DisplayName("no citizenship, absent or empty, is refused (BR-08)")
    void shouldRefuseNoCitizenship() {
        var base = valid();
        assertThat(EvaluateCustomerRiskCommand.parse(raw(base.customerNumber(), base.profileClass(),
                base.residence(), null, base.birth(), base.loginCountry(), base.channel(),
                base.highRiskProductRequest(), base.adverseMediaClass(), base.sanctionsMatch(),
                base.terroristLink(), base.forgedIdentity())))
                .isEqualTo(failing(new EvaluateCustomerRiskError.NoCitizenship()));
        assertThat(EvaluateCustomerRiskCommand.parse(raw(base.customerNumber(), base.profileClass(),
                base.residence(), List.of(), base.birth(), base.loginCountry(), base.channel(),
                base.highRiskProductRequest(), base.adverseMediaClass(), base.sanctionsMatch(),
                base.terroristLink(), base.forgedIdentity())))
                .isEqualTo(failing(new EvaluateCustomerRiskError.NoCitizenship()));
    }

    @Test
    @DisplayName("a geographic risk class that is absent or unknown is refused, naming which country (BR-09)")
    void shouldNameTheCountryWithABadRiskClass() {
        var base = valid();
        assertThat(EvaluateCustomerRiskCommand.parse(raw(base.customerNumber(), base.profileClass(),
                rated("FR", null), base.citizenships(), base.birth(), base.loginCountry(), base.channel(),
                base.highRiskProductRequest(), base.adverseMediaClass(), base.sanctionsMatch(),
                base.terroristLink(), base.forgedIdentity())))
                .isEqualTo(failing(new EvaluateCustomerRiskError.GeographicClassInvalid(CountryField.RESIDENCE)));
        assertThat(EvaluateCustomerRiskCommand.parse(raw(base.customerNumber(), base.profileClass(),
                base.residence(), List.of(rated("DE", "LOW"), rated("TR", "GREY")), base.birth(),
                base.loginCountry(), base.channel(), base.highRiskProductRequest(), base.adverseMediaClass(),
                base.sanctionsMatch(), base.terroristLink(), base.forgedIdentity())))
                .isEqualTo(failing(new EvaluateCustomerRiskError.GeographicClassInvalid(CountryField.CITIZENSHIP)));
        assertThat(EvaluateCustomerRiskCommand.parse(raw(base.customerNumber(), base.profileClass(),
                base.residence(), base.citizenships(), rated("PL", "low"), base.loginCountry(), base.channel(),
                base.highRiskProductRequest(), base.adverseMediaClass(), base.sanctionsMatch(),
                base.terroristLink(), base.forgedIdentity())))
                .isEqualTo(failing(new EvaluateCustomerRiskError.GeographicClassInvalid(CountryField.BIRTH)));
    }

    @Test
    @DisplayName("an absent or unknown onboarding channel is not recognised (BR-10)")
    void shouldRefuseAnUnrecognisedChannel() {
        var base = valid();
        assertThat(EvaluateCustomerRiskCommand.parse(raw(base.customerNumber(), base.profileClass(),
                base.residence(), base.citizenships(), base.birth(), base.loginCountry(), "PHONE",
                base.highRiskProductRequest(), base.adverseMediaClass(), base.sanctionsMatch(),
                base.terroristLink(), base.forgedIdentity())))
                .isEqualTo(failing(new EvaluateCustomerRiskError.ChannelNotRecognised()));
    }

    @Test
    @DisplayName("an absent product request is missing (BR-10)")
    void shouldRefuseAMissingProductRequest() {
        var base = valid();
        assertThat(EvaluateCustomerRiskCommand.parse(raw(base.customerNumber(), base.profileClass(),
                base.residence(), base.citizenships(), base.birth(), base.loginCountry(), base.channel(), null,
                base.adverseMediaClass(), base.sanctionsMatch(), base.terroristLink(), base.forgedIdentity())))
                .isEqualTo(failing(new EvaluateCustomerRiskError.ProductRequestMissing()));
    }

    @Test
    @DisplayName("an absent or unknown adverse media class is not recognised (BR-11)")
    void shouldRefuseAnUnrecognisedAdverseMediaClass() {
        var base = valid();
        assertThat(EvaluateCustomerRiskCommand.parse(raw(base.customerNumber(), base.profileClass(),
                base.residence(), base.citizenships(), base.birth(), base.loginCountry(), base.channel(),
                base.highRiskProductRequest(), "SOME", base.sanctionsMatch(), base.terroristLink(),
                base.forgedIdentity())))
                .isEqualTo(failing(new EvaluateCustomerRiskError.AdverseMediaClassNotRecognised()));
    }

    @Test
    @DisplayName("an absent critical fact is missing, naming which, and they are judged in a fixed order (BR-12)")
    void shouldNameTheMissingCriticalFact() {
        var base = valid();
        assertThat(EvaluateCustomerRiskCommand.parse(raw(base.customerNumber(), base.profileClass(),
                base.residence(), base.citizenships(), base.birth(), base.loginCountry(), base.channel(),
                base.highRiskProductRequest(), base.adverseMediaClass(), null, base.terroristLink(),
                base.forgedIdentity())))
                .isEqualTo(failing(new EvaluateCustomerRiskError.CriticalFactMissing(CriticalFactField.SANCTIONS_MATCH)));
        assertThat(EvaluateCustomerRiskCommand.parse(raw(base.customerNumber(), base.profileClass(),
                base.residence(), base.citizenships(), base.birth(), base.loginCountry(), base.channel(),
                base.highRiskProductRequest(), base.adverseMediaClass(), base.sanctionsMatch(), null, null)))
                .isEqualTo(failing(new EvaluateCustomerRiskError.CriticalFactMissing(CriticalFactField.TERRORIST_LINK)));
        assertThat(EvaluateCustomerRiskCommand.parse(raw(base.customerNumber(), base.profileClass(),
                base.residence(), base.citizenships(), base.birth(), base.loginCountry(), base.channel(),
                base.highRiskProductRequest(), base.adverseMediaClass(), base.sanctionsMatch(),
                base.terroristLink(), null)))
                .isEqualTo(failing(new EvaluateCustomerRiskError.CriticalFactMissing(CriticalFactField.FORGED_IDENTITY)));
    }

    // ------------------------------------------------------------- the order of judgement

    @Test
    @DisplayName("when several fields are wrong, the first in the catalogue's order is the one refused")
    void shouldReturnTheFirstFailureInCatalogueOrder() {
        var bad = rated("XX", "nope");
        // customer number before profile class
        assertThat(EvaluateCustomerRiskCommand.parse(raw("", "COMPANY", bad, List.of(), bad, null, "PHONE", null,
                "SOME", null, null, null)))
                .isEqualTo(failing(new EvaluateCustomerRiskError.CustomerNumberMissing()));
        // profile class before any country
        assertThat(EvaluateCustomerRiskCommand.parse(raw("C-1", "COMPANY", bad, List.of(), bad, null, "PHONE", null,
                "SOME", null, null, null)))
                .isEqualTo(failing(new EvaluateCustomerRiskError.ProfileClassNotRecognised()));
        // countries in the order residence, citizenship, birth, login
        assertThat(EvaluateCustomerRiskCommand.parse(raw("C-1", "INDIVIDUAL_EMPLOYED", bad, List.of(bad), bad, null,
                "PHONE", null, "SOME", null, null, null)))
                .isEqualTo(failing(new EvaluateCustomerRiskError.CountryInvalid(CountryField.RESIDENCE)));
        assertThat(EvaluateCustomerRiskCommand.parse(raw("C-1", "INDIVIDUAL_EMPLOYED", DE_LOW, List.of(bad), bad,
                null, "PHONE", null, "SOME", null, null, null)))
                .isEqualTo(failing(new EvaluateCustomerRiskError.CountryInvalid(CountryField.CITIZENSHIP)));
        assertThat(EvaluateCustomerRiskCommand.parse(raw("C-1", "INDIVIDUAL_EMPLOYED", DE_LOW, List.of(DE_LOW), bad,
                null, "PHONE", null, "SOME", null, null, null)))
                .isEqualTo(failing(new EvaluateCustomerRiskError.CountryInvalid(CountryField.BIRTH)));
        // every country before no-citizenship
        assertThat(EvaluateCustomerRiskCommand.parse(raw("C-1", "INDIVIDUAL_EMPLOYED", DE_LOW, List.of(), bad, "DE",
                "PHONE", null, "SOME", null, null, null)))
                .isEqualTo(failing(new EvaluateCustomerRiskError.CountryInvalid(CountryField.BIRTH)));
        // no-citizenship before any geographic class
        assertThat(EvaluateCustomerRiskCommand.parse(raw("C-1", "INDIVIDUAL_EMPLOYED", rated("DE", "nope"), List.of(),
                DE_LOW, "DE", "PHONE", null, "SOME", null, null, null)))
                .isEqualTo(failing(new EvaluateCustomerRiskError.NoCitizenship()));
        // every country before any geographic class
        assertThat(EvaluateCustomerRiskCommand.parse(raw("C-1", "INDIVIDUAL_EMPLOYED", rated("DE", "nope"),
                List.of(DE_LOW), rated("ZZ", "LOW"), "DE", "PHONE", null, "SOME", null, null, null)))
                .isEqualTo(failing(new EvaluateCustomerRiskError.CountryInvalid(CountryField.BIRTH)));
        // geographic classes before the channel
        assertThat(EvaluateCustomerRiskCommand.parse(raw("C-1", "INDIVIDUAL_EMPLOYED", rated("DE", "nope"),
                List.of(DE_LOW), DE_LOW, "DE", "PHONE", null, "SOME", null, null, null)))
                .isEqualTo(failing(new EvaluateCustomerRiskError.GeographicClassInvalid(CountryField.RESIDENCE)));
        // channel, then product request, then adverse media, then the critical facts
        assertThat(EvaluateCustomerRiskCommand.parse(raw("C-1", "INDIVIDUAL_EMPLOYED", DE_LOW, List.of(DE_LOW),
                DE_LOW, "DE", "PHONE", null, "SOME", null, null, null)))
                .isEqualTo(failing(new EvaluateCustomerRiskError.ChannelNotRecognised()));
        assertThat(EvaluateCustomerRiskCommand.parse(raw("C-1", "INDIVIDUAL_EMPLOYED", DE_LOW, List.of(DE_LOW),
                DE_LOW, "DE", "BRANCH", null, "SOME", null, null, null)))
                .isEqualTo(failing(new EvaluateCustomerRiskError.ProductRequestMissing()));
        assertThat(EvaluateCustomerRiskCommand.parse(raw("C-1", "INDIVIDUAL_EMPLOYED", DE_LOW, List.of(DE_LOW),
                DE_LOW, "DE", "BRANCH", false, "SOME", null, null, null)))
                .isEqualTo(failing(new EvaluateCustomerRiskError.AdverseMediaClassNotRecognised()));
        assertThat(EvaluateCustomerRiskCommand.parse(raw("C-1", "INDIVIDUAL_EMPLOYED", DE_LOW, List.of(DE_LOW),
                DE_LOW, "DE", "BRANCH", false, "NONE", null, null, null)))
                .isEqualTo(failing(new EvaluateCustomerRiskError.CriticalFactMissing(CriticalFactField.SANCTIONS_MATCH)));
    }

    private static RawEvaluateCustomerRisk withCitizenships(java.util.List<RawRatedCountry> citizenships) {
        var raw = RiskFixtures.validRaw();
        return new RawEvaluateCustomerRisk(raw.customerNumber(), raw.profileClass(), raw.residence(), citizenships,
                raw.birth(), raw.loginCountry(), raw.channel(), raw.highRiskProductRequest(), raw.adverseMediaClass(),
                raw.sanctionsMatch(), raw.terroristLink(), raw.forgedIdentity());
    }

    @Test
    @DisplayName("R-27: 10 citizenships parse, 11 are TooManyCitizenships(10, 11) (D-10)")
    void shouldRefuseMoreThanTenCitizenships() {
        var de = RiskFixtures.rated("DE", "LOW");

        assertThat(EvaluateCustomerRiskCommand.parse(withCitizenships(java.util.Collections.nCopies(10, de))).isSuccess())
                .isTrue();
        assertThat(EvaluateCustomerRiskCommand.parse(withCitizenships(java.util.Collections.nCopies(11, de))))
                .isEqualTo(com.gs5.kycaml.shared.kernel.Result.failure(
                        new EvaluateCustomerRiskError.TooManyCitizenships(10, 11)));
    }

    @Test
    @DisplayName("R-27: an invalid citizenship country is refused before the count (catalogue rows 9, 11)")
    void shouldJudgeTheCountryBeforeTheCount() {
        var citizenships = new java.util.ArrayList<>(java.util.Collections.nCopies(10, RiskFixtures.rated("DE", "LOW")));
        citizenships.add(RiskFixtures.rated("XX", "LOW"));

        assertThat(EvaluateCustomerRiskCommand.parse(withCitizenships(citizenships)))
                .isEqualTo(com.gs5.kycaml.shared.kernel.Result.failure(
                        new EvaluateCustomerRiskError.CountryInvalid(CountryField.CITIZENSHIP)));
    }
}
