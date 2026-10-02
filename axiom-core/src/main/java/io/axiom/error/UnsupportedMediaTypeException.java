package io.axiom.error;

import java.io.Serial;

/** A request body whose content type or charset no installed codec accepts (415). */
public class UnsupportedMediaTypeException extends AxiomException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with code {@code unsupported_media_type}. */
    public UnsupportedMediaTypeException() {
        this("unsupported_media_type");
    }

    /**
     * Creates the exception with a specific code, such as {@code missing_content_type}.
     *
     * @param code safe machine-readable code
     */
    public UnsupportedMediaTypeException(String code) {
        super(415, code);
    }
}
