package com.gs5.kycaml.risk.domain;

import com.gs5.kycaml.shared.kernel.Result;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A request to evaluate a customer's risk. It carries parsed values only. The booking centre comes
 * from the caller and the moment from the clock, never from here.
 */
public record EvaluateCustomerRiskCommand(
        CustomerNumber customerNumber,
        CustomerProfileClass profileClass,
        RatedCountry residence,
        List<RatedCountry> citizenships,
        RatedCountry birth,
        CountryCode loginCountry,
        OnboardingChannel channel,
        boolean highRiskProductRequest,
        AdverseMediaClass adverseMedia,
        CriticalFacts criticalFacts) {

    /** The most citizenships one evaluation may carry (BR-08, D-10). */
    public static final int MAX_CITIZENSHIPS = 10;

    /**
     * Judges the fields in the order of the specification's catalogue: customer number, profile
     * class, each country (residence, citizenships, birth, login), no citizenship, too many
     * citizenships, each geographic class, channel, product request, adverse media, then each
     * critical fact. The first failure is the one returned.
     */
    public static Result<EvaluateCustomerRiskCommand, EvaluateCustomerRiskError> parse(
            RawEvaluateCustomerRisk raw) {
        return parseNumber(raw.customerNumber()).flatMap(number ->
                Result.<CustomerProfileClass, EvaluateCustomerRiskError>fromOptional(
                                CustomerProfileClass.parse(raw.profileClass()),
                                EvaluateCustomerRiskError.ProfileClassNotRecognised::new)
                        .flatMap(profile -> Geography.parse(raw).flatMap(geography ->
                                Tail.parse(raw).map(tail -> new EvaluateCustomerRiskCommand(
                                        number, profile, geography.residence(), geography.citizenships(),
                                        geography.birth(), geography.login(), tail.channel(),
                                        tail.highRiskProductRequest(), tail.adverseMedia(),
                                        tail.criticalFacts())))));
    }

    /** Confidential input (BR-43): never printed. */
    @Override
    public String toString() {
        return "EvaluateCustomerRiskCommand[***]";
    }

    private static Result<CustomerNumber, EvaluateCustomerRiskError> parseNumber(@Nullable String text) {
        return CustomerNumber.parse(text).mapError(error -> switch (error) {
            case CustomerNumberError.Missing _ -> new EvaluateCustomerRiskError.CustomerNumberMissing();
            case CustomerNumberError.TooLong(var max, var actual) ->
                    new EvaluateCustomerRiskError.CustomerNumberTooLong(max, actual);
            case CustomerNumberError.ControlCharacter(var codePoint, var index) ->
                    new EvaluateCustomerRiskError.CustomerNumberControlCharacter(codePoint, index);
        });
    }

    /** The countries of an evaluation, judged as the catalogue orders them. */
    private record Geography(RatedCountry residence, List<RatedCountry> citizenships, RatedCountry birth,
                             CountryCode login) {

        static Result<Geography, EvaluateCustomerRiskError> parse(RawEvaluateCustomerRisk raw) {
            var residenceCode = codeOf(raw.residence());
            if (residenceCode.isEmpty()) {
                return Result.failure(new EvaluateCustomerRiskError.CountryInvalid(CountryField.RESIDENCE));
            }
            var citizenshipCodes = new ArrayList<CountryCode>();
            var rawCitizenships = raw.citizenships() == null ? List.<RawRatedCountry>of() : raw.citizenships();
            for (var rawCitizenship : rawCitizenships) {
                var code = codeOf(rawCitizenship);
                if (code.isEmpty()) {
                    return Result.failure(new EvaluateCustomerRiskError.CountryInvalid(CountryField.CITIZENSHIP));
                }
                citizenshipCodes.add(code.get());
            }
            var birthCode = codeOf(raw.birth());
            if (birthCode.isEmpty()) {
                return Result.failure(new EvaluateCustomerRiskError.CountryInvalid(CountryField.BIRTH));
            }
            var loginCode = CountryCode.parse(raw.loginCountry());
            if (loginCode.isEmpty()) {
                return Result.failure(new EvaluateCustomerRiskError.CountryInvalid(CountryField.LOGIN));
            }
            if (rawCitizenships.isEmpty()) {
                return Result.failure(new EvaluateCustomerRiskError.NoCitizenship());
            }
            if (rawCitizenships.size() > MAX_CITIZENSHIPS) {
                return Result.failure(new EvaluateCustomerRiskError.TooManyCitizenships(
                        MAX_CITIZENSHIPS, rawCitizenships.size()));
            }

            var residenceClass = classOf(raw.residence());
            if (residenceClass.isEmpty()) {
                return Result.failure(new EvaluateCustomerRiskError.GeographicClassInvalid(CountryField.RESIDENCE));
            }
            var citizenships = new ArrayList<RatedCountry>();
            for (var index = 0; index < rawCitizenships.size(); index++) {
                var citizenshipClass = classOf(rawCitizenships.get(index));
                if (citizenshipClass.isEmpty()) {
                    return Result.failure(
                            new EvaluateCustomerRiskError.GeographicClassInvalid(CountryField.CITIZENSHIP));
                }
                citizenships.add(new RatedCountry(citizenshipCodes.get(index), citizenshipClass.get()));
            }
            var birthClass = classOf(raw.birth());
            if (birthClass.isEmpty()) {
                return Result.failure(new EvaluateCustomerRiskError.GeographicClassInvalid(CountryField.BIRTH));
            }
            return Result.success(new Geography(
                    new RatedCountry(residenceCode.get(), residenceClass.get()),
                    List.copyOf(citizenships),
                    new RatedCountry(birthCode.get(), birthClass.get()),
                    loginCode.get()));
        }

        private static Optional<CountryCode> codeOf(@Nullable RawRatedCountry country) {
            return country == null ? Optional.empty() : CountryCode.parse(country.country());
        }

        private static Optional<GeographicRiskClass> classOf(@Nullable RawRatedCountry country) {
            return country == null ? Optional.empty() : GeographicRiskClass.parse(country.riskClass());
        }
    }

    /** The channel, the product request, the adverse media class and the critical facts. */
    private record Tail(OnboardingChannel channel, boolean highRiskProductRequest, AdverseMediaClass adverseMedia,
                        CriticalFacts criticalFacts) {

        static Result<Tail, EvaluateCustomerRiskError> parse(RawEvaluateCustomerRisk raw) {
            var channel = OnboardingChannel.parse(raw.channel());
            if (channel.isEmpty()) {
                return Result.failure(new EvaluateCustomerRiskError.ChannelNotRecognised());
            }
            var productRequest = raw.highRiskProductRequest();
            if (productRequest == null) {
                return Result.failure(new EvaluateCustomerRiskError.ProductRequestMissing());
            }
            var media = AdverseMediaClass.parse(raw.adverseMediaClass());
            if (media.isEmpty()) {
                return Result.failure(new EvaluateCustomerRiskError.AdverseMediaClassNotRecognised());
            }
            var sanctions = raw.sanctionsMatch();
            if (sanctions == null) {
                return Result.failure(new EvaluateCustomerRiskError.CriticalFactMissing(CriticalFactField.SANCTIONS_MATCH));
            }
            var terrorist = raw.terroristLink();
            if (terrorist == null) {
                return Result.failure(new EvaluateCustomerRiskError.CriticalFactMissing(CriticalFactField.TERRORIST_LINK));
            }
            var forged = raw.forgedIdentity();
            if (forged == null) {
                return Result.failure(new EvaluateCustomerRiskError.CriticalFactMissing(CriticalFactField.FORGED_IDENTITY));
            }
            return Result.success(new Tail(channel.get(), productRequest, media.get(),
                    new CriticalFacts(sanctions, terrorist, forged)));
        }
    }
}
