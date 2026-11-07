package com.gs5.kycaml.risk.domain;

/** Every reason a text is not a customer number (BR-06). No variant carries the text. */
public sealed interface CustomerNumberError {

    record Missing() implements CustomerNumberError { }

    record TooLong(int maxLength, int actualLength) implements CustomerNumberError { }

    record ControlCharacter(int codePoint, int index) implements CustomerNumberError { }
}
