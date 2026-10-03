package com.jsgalactic.axiom.validation.jakarta;

import jakarta.validation.MessageInterpolator;
import java.util.Locale;

/**
 * Returns message templates unchanged. Axiom never sends provider messages to clients, so there
 * is nothing to interpolate, and skipping interpolation means neither Expression Language nor
 * parameter substitution can ever evaluate text from a constraint, a custom violation template or
 * a validated value.
 */
final class LiteralMessageInterpolator implements MessageInterpolator {
    @Override public String interpolate(String messageTemplate, Context context) {
        return messageTemplate;
    }

    @Override public String interpolate(String messageTemplate, Context context, Locale locale) {
        return messageTemplate;
    }
}
