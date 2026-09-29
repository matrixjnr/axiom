package example;

import io.axiom.Axiom;
import io.axiom.http.Request;

/** Runs the public programming model without opening a network listener. */
public final class Main {
    private Main() {}

    /**
     * Runs the in-memory Hello World example.
     *
     * @param args unused
     * @throws Exception if the handler fails
     */
    public static void main(String[] args) throws Exception {
        try (var app = Axiom.create()) {
            app.get("/", ctx -> "Hello, world!");
            app.start();
            System.out.println(app.handle(Request.get("/")).body());
        }
    }
}
