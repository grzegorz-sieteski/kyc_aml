package com.gs5.kycaml.risk.domain;

/**
 * The jurisdictions whose booking centres are in scope: the United States, the United Kingdom and
 * Germany under EU rules (D-06). Each carries the ISO code of the booking centre's country, which
 * decides who is a non-resident (BR-21).
 */
public enum Jurisdiction {

    US("US"),
    GB("GB"),
    DE("DE");

    private final String countryCode;

    Jurisdiction(String countryCode) {
        this.countryCode = countryCode;
    }

    public String countryCode() {
        return countryCode;
    }
}
