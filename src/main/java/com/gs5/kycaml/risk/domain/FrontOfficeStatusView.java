package com.gs5.kycaml.risk.domain;

/** What branch and call-centre staff see (query 2): the customer number and a neutral status. */
public record FrontOfficeStatusView(String customerNumber, FrontOfficeStatus status, String message) { }
