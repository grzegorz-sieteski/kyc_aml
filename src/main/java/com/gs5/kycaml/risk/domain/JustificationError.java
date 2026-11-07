package com.gs5.kycaml.risk.domain;

/** Every reason a text is not a justification (BR-34). No variant carries the text. */
public sealed interface JustificationError {

    record Missing() implements JustificationError { }

    record TooShort(int minLength, int actualLength) implements JustificationError { }

    record TooLong(int maxLength, int actualLength) implements JustificationError { }

    record ControlCharacter(int codePoint, int index) implements JustificationError { }
}
