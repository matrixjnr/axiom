import com.jsgalactic.axiom.Axiom;

public class Hello {
    public static void main(String[] args) throws Exception {
        try (var app = Axiom.create()) {
            app.get("/", ctx -> "Hello, world!");
            app.listen(8080).termination().toCompletableFuture().join();
        }
    }
}
