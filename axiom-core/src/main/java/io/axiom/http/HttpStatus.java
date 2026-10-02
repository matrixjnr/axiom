package io.axiom.http;

/**
 * Status codes used by Axiom applications and runtimes, with their RFC 9110 reason phrases.
 * Plain {@code int} constants keep {@link Response} and handler code free of wrapper types.
 */
public final class HttpStatus {
    /** 200 OK. */ public static final int OK = 200;
    /** 201 Created; pair with a Location header. */ public static final int CREATED = 201;
    /** 202 Accepted. */ public static final int ACCEPTED = 202;
    /** 204 No Content; never carries a body. */ public static final int NO_CONTENT = 204;
    /** 205 Reset Content; never carries a body. */ public static final int RESET_CONTENT = 205;
    /** 301 Moved Permanently. */ public static final int MOVED_PERMANENTLY = 301;
    /** 302 Found. */ public static final int FOUND = 302;
    /** 303 See Other. */ public static final int SEE_OTHER = 303;
    /** 304 Not Modified; never carries a body. */ public static final int NOT_MODIFIED = 304;
    /** 307 Temporary Redirect. */ public static final int TEMPORARY_REDIRECT = 307;
    /** 308 Permanent Redirect. */ public static final int PERMANENT_REDIRECT = 308;
    /** 400 Bad Request. */ public static final int BAD_REQUEST = 400;
    /** 401 Unauthorized. */ public static final int UNAUTHORIZED = 401;
    /** 403 Forbidden. */ public static final int FORBIDDEN = 403;
    /** 404 Not Found. */ public static final int NOT_FOUND = 404;
    /** 405 Method Not Allowed. */ public static final int METHOD_NOT_ALLOWED = 405;
    /** 406 Not Acceptable. */ public static final int NOT_ACCEPTABLE = 406;
    /** 408 Request Timeout. */ public static final int REQUEST_TIMEOUT = 408;
    /** 409 Conflict. */ public static final int CONFLICT = 409;
    /** 410 Gone. */ public static final int GONE = 410;
    /** 411 Length Required. */ public static final int LENGTH_REQUIRED = 411;
    /** 412 Precondition Failed. */ public static final int PRECONDITION_FAILED = 412;
    /** 413 Content Too Large. */ public static final int CONTENT_TOO_LARGE = 413;
    /** 414 URI Too Long. */ public static final int URI_TOO_LONG = 414;
    /** 415 Unsupported Media Type. */ public static final int UNSUPPORTED_MEDIA_TYPE = 415;
    /** 417 Expectation Failed. */ public static final int EXPECTATION_FAILED = 417;
    /** 422 Unprocessable Content. */ public static final int UNPROCESSABLE_CONTENT = 422;
    /** 428 Precondition Required. */ public static final int PRECONDITION_REQUIRED = 428;
    /** 429 Too Many Requests. */ public static final int TOO_MANY_REQUESTS = 429;
    /** 431 Request Header Fields Too Large. */ public static final int REQUEST_HEADER_FIELDS_TOO_LARGE = 431;
    /** 500 Internal Server Error. */ public static final int INTERNAL_SERVER_ERROR = 500;
    /** 501 Not Implemented. */ public static final int NOT_IMPLEMENTED = 501;
    /** 502 Bad Gateway. */ public static final int BAD_GATEWAY = 502;
    /** 503 Service Unavailable. */ public static final int SERVICE_UNAVAILABLE = 503;
    /** 504 Gateway Timeout. */ public static final int GATEWAY_TIMEOUT = 504;
    /** 505 HTTP Version Not Supported. */ public static final int HTTP_VERSION_NOT_SUPPORTED = 505;

    private HttpStatus() {}

    /**
     * Returns the RFC 9110 reason phrase for a status listed in this class, or a generic phrase
     * for its class (for example "Client Error") otherwise.
     *
     * @param status final status, 200 through 599
     * @return reason phrase
     * @throws IllegalArgumentException if the status is not a final status
     */
    public static String reasonPhrase(int status) {
        Response.validateStatus(status);
        return switch (status) {
            case OK -> "OK";
            case CREATED -> "Created";
            case ACCEPTED -> "Accepted";
            case NO_CONTENT -> "No Content";
            case RESET_CONTENT -> "Reset Content";
            case MOVED_PERMANENTLY -> "Moved Permanently";
            case FOUND -> "Found";
            case SEE_OTHER -> "See Other";
            case NOT_MODIFIED -> "Not Modified";
            case TEMPORARY_REDIRECT -> "Temporary Redirect";
            case PERMANENT_REDIRECT -> "Permanent Redirect";
            case BAD_REQUEST -> "Bad Request";
            case UNAUTHORIZED -> "Unauthorized";
            case FORBIDDEN -> "Forbidden";
            case NOT_FOUND -> "Not Found";
            case METHOD_NOT_ALLOWED -> "Method Not Allowed";
            case NOT_ACCEPTABLE -> "Not Acceptable";
            case REQUEST_TIMEOUT -> "Request Timeout";
            case CONFLICT -> "Conflict";
            case GONE -> "Gone";
            case LENGTH_REQUIRED -> "Length Required";
            case PRECONDITION_FAILED -> "Precondition Failed";
            case CONTENT_TOO_LARGE -> "Content Too Large";
            case URI_TOO_LONG -> "URI Too Long";
            case UNSUPPORTED_MEDIA_TYPE -> "Unsupported Media Type";
            case EXPECTATION_FAILED -> "Expectation Failed";
            case UNPROCESSABLE_CONTENT -> "Unprocessable Content";
            case PRECONDITION_REQUIRED -> "Precondition Required";
            case TOO_MANY_REQUESTS -> "Too Many Requests";
            case REQUEST_HEADER_FIELDS_TOO_LARGE -> "Request Header Fields Too Large";
            case INTERNAL_SERVER_ERROR -> "Internal Server Error";
            case NOT_IMPLEMENTED -> "Not Implemented";
            case BAD_GATEWAY -> "Bad Gateway";
            case SERVICE_UNAVAILABLE -> "Service Unavailable";
            case GATEWAY_TIMEOUT -> "Gateway Timeout";
            case HTTP_VERSION_NOT_SUPPORTED -> "HTTP Version Not Supported";
            default -> status < 300 ? "Success" : status < 400 ? "Redirection"
                    : status < 500 ? "Client Error" : "Server Error";
        };
    }

    /**
     * Returns the default machine-readable error code for a status: its reason phrase in
     * lowercase with spaces replaced by underscores, for example {@code not_found}.
     *
     * @param status final status
     * @return safe error code
     */
    public static String defaultCode(int status) {
        return reasonPhrase(status).toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
    }
}
