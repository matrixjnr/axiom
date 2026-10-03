import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.application.Application;
import com.jsgalactic.axiom.context.BodyValidator;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.error.NotFoundException;
import com.jsgalactic.axiom.error.Violation;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class TasksApi {
    public record NewTask(String title) {}
    public record Task(String id, String title) {}

    static final BodyValidator<NewTask> VALID = task -> task.title() == null || task.title().isBlank()
            ? List.of(new Violation("title", "required")) : List.of();
    static final Middleware NO_SNIFF = (ctx, next) -> next.run().withHeader("X-Content-Type-Options", "nosniff");

    static Application create() {
        var tasks = new ConcurrentHashMap<String, Task>();
        var ids = new AtomicLong();
        var app = Axiom.create();
        app.error(NoSuchElementException.class, (ctx, e) -> { throw new NotFoundException("task_not_found"); });
        app.group("/tasks", api -> {
            api.use(NO_SNIFF);
            api.post("", ctx -> {                                  // POST /tasks, 422 on a blank title
                var task = new Task(Long.toString(ids.incrementAndGet()), ctx.validatedBody(NewTask.class, VALID).title());
                tasks.put(task.id(), task);
                return ctx.status(201).json(task).withLocation("/tasks/" + task.id());
            });
            api.get("", ctx -> {                                   // GET /tasks?q=milk
                var text = ctx.query("q").orElse("");
                return ctx.json(tasks.values().stream().filter(t -> t.title().contains(text)).toList());
            });
            api.get("/:id", ctx -> ctx.json(Optional.ofNullable(tasks.get(ctx.path("id"))).orElseThrow()));
        });
        return app;
    }

    public static void main(String[] args) throws Exception {
        create().listen(8080).termination().toCompletableFuture().join();
    }
}
