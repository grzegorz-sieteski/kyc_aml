package com.gs5.kycaml.risk.domain;

/**
 * Every reason evaluating a customer's risk can be refused, except the two the access gate decides
 * (caller not identified, role). Exactly the specification's catalogue for command 1, plus
 * {@link BookingCentreUnknown}, which is the same refusal as "caller not identified" reached from
 * the use case when the caller's booking centre has no policy (BR-01, fail closed).
 *
 * <p>No variant carries a score, a track or the customer number (BR-41, BR-43).
 */
public sealed interface EvaluateCustomerRiskError {

    record CustomerNumberMissing() implements EvaluateCustomerRiskError { }

    record CustomerNumberTooLong(int maxLength, int actualLength) implements EvaluateCustomerRiskError { }

    record CustomerNumberControlCharacter(int codePoint, int index) implements EvaluateCustomerRiskError { }

    record ProfileClassNotRecognised() implements EvaluateCustomerRiskError { }

    record CountryInvalid(CountryField field) implements EvaluateCustomerRiskError { }

    record NoCitizenship() implements EvaluateCustomerRiskError { }

    record GeographicClassInvalid(CountryField field) implements EvaluateCustomerRiskError { }

    record ChannelNotRecognised() implements EvaluateCustomerRiskError { }

    record ProductRequestMissing() implements EvaluateCustomerRiskError { }

    record AdverseMediaClassNotRecognised() implements EvaluateCustomerRiskError { }

    record CriticalFactMissing(CriticalFactField field) implements EvaluateCustomerRiskError { }

    record RiskProfileChangedConcurrently() implements EvaluateCustomerRiskError { }

    record BookingCentreUnknown() implements EvaluateCustomerRiskError { }
}
