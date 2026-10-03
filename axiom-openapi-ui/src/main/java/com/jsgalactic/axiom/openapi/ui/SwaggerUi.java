package com.jsgalactic.axiom.openapi.ui;

import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.error.NotFoundException;
import com.jsgalactic.axiom.http.Response;
import com.jsgalactic.axiom.routing.Route;
import com.jsgalactic.axiom.routing.RouteDoc;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Serves Swagger UI from the application itself, pointing at the application's own OpenAPI or
 * Swagger document. Nothing is loaded from a CDN or any other origin: the UI files come from the
 * {@code org.webjars:swagger-ui} artifact on the class path, are read once when the UI is
 * registered and are served from memory.
 *
 * <pre>{@code
 * SwaggerUi.builder().spec("OpenAPI 3.1", "/openapi.json").build().register(app);   // GET /docs
 * }</pre>
 *
 * <p><b>Served routes</b> (under the configured path, {@code /docs} by default):
 * <ul>
 * <li>{@code GET /docs} and {@code GET /docs/}: the HTML page. It has no inline script; it loads
 * the files below from its own origin.</li>
 * <li>{@code GET /docs/swagger-initializer.js}: the generated start-up script that names your
 * document URL(s).</li>
 * <li>{@code GET /docs/assets/<ui version>/<file>}: the bundled UI files, an exact allow-list
 * ({@code swagger-ui.css}, {@code swagger-ui-bundle.js}, {@code swagger-ui-standalone-preset.js},
 * {@code index.css}, two favicons). Any other name, including anything that looks like a path
 * traversal, answers 404; no file system or class path path is ever built from a request.</li>
 * </ul>
 * The page and the script are revalidated ({@code Cache-Control: no-cache}, strong {@code ETag},
 * 304 on a match); assets live under a versioned path and are {@code immutable}. Every response
 * carries a strict {@code Content-Security-Policy} (scripts and connections only from the same
 * origin, no framing), {@code X-Content-Type-Options: nosniff} and {@code Referrer-Policy:
 * no-referrer}. The document URLs must therefore be same-origin paths such as
 * {@code /openapi.json}. {@code HEAD} and {@code OPTIONS} are answered by the router like for any
 * route.
 *
 * <p>The UI is a front end for your API description. Whether to expose it publicly is the
 * application's choice: nothing is served until {@link #register} is called, and it takes the same
 * middleware as any route, so register the document routes and the UI with the same policy.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class SwaggerUi {
    private static final int STREAM_THRESHOLD = 512 * 1024;
    private static final String BASE = "META-INF/resources/webjars/swagger-ui/";
    private static final String POM = "META-INF/maven/org.webjars/swagger-ui/pom.properties";
    private static final Pattern PATH = Pattern.compile("(/[A-Za-z0-9._~-]+)+");
    private static final Pattern SPEC_URL = Pattern.compile("/[A-Za-z0-9._~/-]*");
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Map<String, String> ASSETS = Map.of(
            "swagger-ui.css", "text/css; charset=utf-8",
            "swagger-ui-bundle.js", "text/javascript; charset=utf-8",
            "swagger-ui-standalone-preset.js", "text/javascript; charset=utf-8",
            "index.css", "text/css; charset=utf-8",
            "favicon-16x16.png", "image/png",
            "favicon-32x32.png", "image/png");
    /** Scripts, connections and everything else only from this origin; no framing, no base tag. */
    static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; font-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; "
            + "frame-ancestors 'none'";

    private final String path;
    private final String title;
    private final List<String[]> specs;

    private SwaggerUi(Builder b) {
        this.path = b.path;
        this.title = b.title;
        this.specs = List.copyOf(b.specs);
    }

    /**
     * Starts a configuration.
     *
     * @return a builder
     */
    public static Builder builder() { return new Builder(); }

    /**
     * Returns the path the UI is served under.
     *
     * @return the path, for example {@code /docs}
     */
    public String path() { return path; }

    /**
     * Registers the UI routes. The UI files are read from the class path now, so a missing
     * artifact stops startup instead of failing a request. Routes are hidden from generated
     * documents.
     *
     * @param app application still configuring
     * @param middleware middleware for every UI route, for example an authentication policy
     * @return the route serving the page
     * @throws IllegalStateException if the Swagger UI artifact is not on the class path
     */
    public Route register(Application app, Middleware... middleware) {
        Objects.requireNonNull(app, "app");
        var version = version();
        var assetPrefix = "assets/" + version + "/";
        var files = new LinkedHashMap<String, Served>();
        for (var asset : ASSETS.entrySet()) {
            files.put(assetPrefix + asset.getKey(), new Served(asset.getValue(), read(version, asset.getKey()),
                    "public, max-age=31536000, immutable"));
        }
        files.put("swagger-initializer.js", new Served("text/javascript; charset=utf-8",
                initializer().getBytes(StandardCharsets.UTF_8), "no-cache"));
        var page = new Served("text/html; charset=utf-8", page(assetPrefix).getBytes(StandardCharsets.UTF_8), "no-cache");

        var root = app.get(path, ctx -> serve(ctx, page), middleware);
        var rest = app.get(path + "/*rest", ctx -> {
            var name = ctx.path("rest");
            if (name.isEmpty()) {
                return serve(ctx, page);
            }
            var served = FILE_NAME.matcher(name).matches() ? files.get(name) : null;
            if (served == null) {
                throw new NotFoundException("not_found");
            }
            return serve(ctx, served);
        }, middleware);
        app.describe(root, RouteDoc.empty().hidden());
        app.describe(rest, RouteDoc.empty().hidden());
        return root;
    }

    /** Names that may be looked up: relative, no dot segments, no encoding, no backslash. */
    private static final Pattern FILE_NAME = Pattern.compile(
            "(assets/[0-9][A-Za-z0-9._-]*/" + FILE.pattern() + "|" + FILE.pattern() + ")");

    private record Served(String contentType, byte[] body, String cacheControl, String etag) {
        Served(String contentType, byte[] body, String cacheControl) {
            this(contentType, body, cacheControl, digest(body));
        }
    }

    private static Response serve(Context ctx, Served served) {
        var etag = served.etag();
        if (matches(ctx, etag)) {
            return secured(Response.of(304, null), served);
        }
        // Bodies over the in-memory response limit (1 MiB) are streamed; they carry no Content-Length.
        var body = served.body();
        var response = body.length > STREAM_THRESHOLD
                ? Response.stream(200, served.contentType(), body.length, out -> out.write(body))
                : Response.of(200, body).withHeader("Content-Type", served.contentType());
        return secured(response, served);
    }

    private static Response secured(Response response, Served served) {
        return response.withHeader("Cache-Control", served.cacheControl()).withHeader("ETag", served.etag())
                .withHeader("Content-Security-Policy", CSP).withHeader("X-Content-Type-Options", "nosniff")
                .withHeader("Referrer-Policy", "no-referrer");
    }

    private static boolean matches(Context ctx, String etag) {
        return ctx.header("If-None-Match").map(value -> {
            for (var candidate : value.split(",")) {
                var trimmed = candidate.trim();
                if (trimmed.equals("*") || trimmed.equals(etag)) {
                    return true;
                }
            }
            return false;
        }).orElse(false);
    }

    private static String digest(byte[] body) {
        try {
            return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body), 0, 16) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String version() {
        try (InputStream in = SwaggerUi.class.getClassLoader().getResourceAsStream(POM)) {
            if (in == null) {
                throw new IllegalStateException("The org.webjars:swagger-ui artifact is not on the class path");
            }
            var properties = new Properties();
            properties.load(in);
            var version = properties.getProperty("version");
            if (version == null || !FILE.matcher(version).matches()) {
                throw new IllegalStateException("Unreadable Swagger UI version");
            }
            return version;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the Swagger UI artifact", e);
        }
    }

    private static byte[] read(String version, String file) {
        try (InputStream in = SwaggerUi.class.getClassLoader().getResourceAsStream(BASE + version + "/" + file)) {
            if (in == null) {
                throw new IllegalStateException("Swagger UI file missing from the artifact: " + file);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the Swagger UI file " + file, e);
        }
    }

    private String page(String assetPrefix) {
        var assets = path + "/" + assetPrefix;
        return "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"UTF-8\">\n<title>" + escapeHtml(title)
                + "</title>\n<link rel=\"stylesheet\" href=\"" + assets + "swagger-ui.css\">\n"
                + "<link rel=\"stylesheet\" href=\"" + assets + "index.css\">\n"
                + "<link rel=\"icon\" type=\"image/png\" href=\"" + assets + "favicon-32x32.png\" sizes=\"32x32\">\n"
                + "<link rel=\"icon\" type=\"image/png\" href=\"" + assets + "favicon-16x16.png\" sizes=\"16x16\">\n"
                + "</head>\n<body>\n<div id=\"swagger-ui\"></div>\n"
                + "<script src=\"" + assets + "swagger-ui-bundle.js\"></script>\n"
                + "<script src=\"" + assets + "swagger-ui-standalone-preset.js\"></script>\n"
                + "<script src=\"" + path + "/swagger-initializer.js\"></script>\n</body>\n</html>\n";
    }

    private String initializer() {
        var config = new StringBuilder();
        if (specs.size() == 1) {
            config.append("    url: ").append(js(specs.get(0)[1])).append(",\n");
        } else {
            var entries = new ArrayList<String>();
            for (var spec : specs) {
                entries.add("{url: " + js(spec[1]) + ", name: " + js(spec[0]) + "}");
            }
            config.append("    urls: [").append(String.join(", ", entries)).append("],\n");
            config.append("    \"urls.primaryName\": ").append(js(specs.get(0)[0])).append(",\n");
        }
        return "window.onload = function () {\n  window.ui = SwaggerUIBundle({\n" + config
                + "    dom_id: \"#swagger-ui\",\n    deepLinking: true,\n"
                + "    presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],\n"
                + "    plugins: [SwaggerUIBundle.plugins.DownloadUrl],\n"
                + "    layout: \"StandaloneLayout\"\n  });\n};\n";
    }

    private static String js(String text) {
        var out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Configures a {@link SwaggerUi}. Not thread-safe; build once at startup. */
    public static final class Builder {
        private String path = "/docs";
        private String title = "API documentation";
        private final List<String[]> specs = new ArrayList<>();

        private Builder() { }

        /**
         * Sets where the UI is served.
         *
         * @param path absolute path without a trailing slash and without parameters, default {@code /docs}
         * @return this builder
         */
        public Builder path(String path) {
            if (!PATH.matcher(Objects.requireNonNull(path, "path")).matches()) {
                throw new IllegalArgumentException("The path must be absolute, without a trailing slash, parameters or wildcards");
            }
            this.path = path;
            return this;
        }

        /**
         * Sets the page title.
         *
         * @param title text shown in the browser tab
         * @return this builder
         */
        public Builder title(String title) {
            this.title = Objects.requireNonNull(title, "title");
            return this;
        }

        /**
         * Adds a document the UI can display. With several, the UI offers a selector and starts
         * with the first.
         *
         * @param name label, for example {@code OpenAPI 3.1}
         * @param url same-origin path of the document, for example {@code /openapi.json}
         * @return this builder
         * @throws IllegalArgumentException if the URL is not a same-origin path (a scheme, host or
         *         {@code //} would be refused by the page's Content-Security-Policy)
         */
        public Builder spec(String name, String url) {
            if (Objects.requireNonNull(name, "name").isBlank() || name.length() > 64) {
                throw new IllegalArgumentException("A spec name is 1 to 64 characters");
            }
            if (!SPEC_URL.matcher(Objects.requireNonNull(url, "url")).matches() || url.startsWith("//")) {
                throw new IllegalArgumentException("A spec URL must be a same-origin path such as /openapi.json");
            }
            specs.add(new String[] {name, url});
            return this;
        }

        /**
         * Builds the configuration.
         *
         * @return an immutable {@link SwaggerUi}
         * @throws IllegalStateException if no spec was added
         */
        public SwaggerUi build() {
            if (specs.isEmpty()) {
                throw new IllegalStateException("Add at least one spec");
            }
            return new SwaggerUi(this);
        }
    }
}
