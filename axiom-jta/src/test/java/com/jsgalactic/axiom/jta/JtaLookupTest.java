package com.jsgalactic.axiom.jta;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsgalactic.axiom.Axiom;
import com.jsgalactic.axiom.context.Context;
import com.jsgalactic.axiom.context.Middleware;
import com.jsgalactic.axiom.test.TestClient;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

/**
 * Uses only reflection and JDK types to reach the JTA adapter, the way an integration that must not
 * compile against {@code axiom-jta} would. This test deliberately imports no class from the
 * adapter.
 */
class JtaLookupTest {
  @Test
  void discoversLookupByServiceLoaderAndCallsItWithJdkTypes() throws Exception {
    var tm = new FakeTransactionManager();
    Class<?> jtaType = Class.forName("com.jsgalactic.axiom.jta.Jta");
    Object builder = jtaType.getMethod("builder", Object.class).invoke(null, tm);
    Object jta = builder.getClass().getMethod("build").invoke(builder);
    Middleware boundary = (Middleware) jtaType.getMethod("transaction").invoke(jta);
    Class<?> contextType = Class.forName("com.jsgalactic.axiom.jta.TransactionContext");
    Method contextOf = jtaType.getMethod("context", Context.class);
    Method mark = contextType.getMethod("setRollbackOnly");

    Class<?> lookupType = Class.forName("com.jsgalactic.axiom.jta.spi.JtaTransactionLookup");
    Object lookup = ServiceLoader.load(lookupType).iterator().next();
    Method active = lookupType.getMethod("active", Object.class);
    Method status = lookupType.getMethod("status", Object.class);
    Method rollbackOnly = lookupType.getMethod("rollbackOnly", Object.class);

    var app = Axiom.create();
    app.get(
        "/lookup",
        ctx -> {
          assertThat((boolean) active.invoke(lookup, ctx)).isTrue();
          assertThat((String) status.invoke(lookup, ctx)).isEqualTo("ACTIVE");
          assertThat((boolean) rollbackOnly.invoke(lookup, ctx)).isFalse();
          Object transaction = ((Optional<?>) contextOf.invoke(null, ctx)).orElseThrow();
          mark.invoke(transaction);
          assertThat((boolean) rollbackOnly.invoke(lookup, ctx)).isTrue();
          return ctx.text("ok");
        },
        boundary);

    try (var client = TestClient.start(app)) {
      assertThat(client.get("/lookup").status()).isEqualTo(200);
    }

    assertThat(tm.rollbacks()).isEqualTo(1);
    assertThat((boolean) active.invoke(lookup, new Object())).isFalse();
    assertThat((String) status.invoke(lookup, new Object())).isEqualTo("INACTIVE");
  }
}
