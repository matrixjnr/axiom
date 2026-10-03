package com.jsgalactic.axiom.validation.jakarta;

import java.lang.annotation.Annotation;

/** Derives violation codes from constraint annotation names, for example NotBlank to not_blank. */
final class ConstraintCodes {
    private static final int MAX_CODE = 64;
    private static final ClassValue<String> CODES = new ClassValue<>() {
        @Override protected String computeValue(Class<?> type) {
            return snakeCase(type.getSimpleName());
        }
    };

    private ConstraintCodes() {}

    static String of(Class<? extends Annotation> type) {
        return CODES.get(type);
    }

    private static String snakeCase(String name) {
        var code = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            var c = name.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                var previousLower = i > 0 && isLowerOrDigit(name.charAt(i - 1));
                var acronymEnd = i > 0 && isUpper(name.charAt(i - 1))
                        && i + 1 < name.length() && isLowerOrDigit(name.charAt(i + 1));
                if ((previousLower || acronymEnd) && !code.isEmpty() && code.charAt(code.length() - 1) != '_') {
                    code.append('_');
                }
                code.append((char) (c + ('a' - 'A')));
            } else if (isLowerOrDigit(c)) {
                code.append(c);
            } else if (c == '_' && !code.isEmpty() && code.charAt(code.length() - 1) != '_') {
                code.append('_');
            }
        }
        while (!code.isEmpty() && !(code.charAt(0) >= 'a' && code.charAt(0) <= 'z')) {
            code.deleteCharAt(0);
        }
        if (code.length() > MAX_CODE) {
            code.setLength(MAX_CODE);
        }
        while (!code.isEmpty() && code.charAt(code.length() - 1) == '_') {
            code.setLength(code.length() - 1);
        }
        return code.isEmpty() ? "invalid" : code.toString();
    }

    private static boolean isUpper(char c) {
        return c >= 'A' && c <= 'Z';
    }

    private static boolean isLowerOrDigit(char c) {
        return c >= 'a' && c <= 'z' || c >= '0' && c <= '9';
    }
}
