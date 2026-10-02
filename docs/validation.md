# Validation

Two optional modules check values in handlers and report failures as the
**422** problem responses described in [errors](errors.md):

- `axiom-validation`: the `Validator` interface, `Validation.require`, and an
  annotation-free rule builder. It depends on `axiom-core` only.
- `axiom-validation-jakarta`: an adapter that runs Jakarta Validation
  annotations through Hibernate Validator. Jakarta and Hibernate types stay
  inside this module.

Neither module changes core. Validation is an explicit call in the handler.

## Validators and `Validation.require`

```java
public interface Validator<T> {
    List<Violation> validate(T value);
    default Validator<T> and(Validator<? super T> other) { ... }
}
```

A validator returns an empty list for a valid value, or core
`io.axiom.error.Violation(field, code)` values. Implementations must be
thread-safe and must not build fields or codes from the checked value.
`Validation.require(validator, value)` returns the value when it is valid and
otherwise throws core's `ValidationException`, so the client receives:

```json
{"status":422,"code":"validation_failed","requestId":"q3J0bW9yZS1yYW5k-1a",
 "violations":[{"field":"items[2].sku","code":"pattern"},{"field":"email","code":"email"}]}
```

```java
app.post("/orders", ctx -> {
    var order = Validation.require(ORDER, ctx.body(Order.class));
    return ctx.status(201).json(orders.create(order));
});
```

The response contains only field paths and codes. Messages, invalid values,
map keys and exception text never reach it. `and` runs validators in order,
drops duplicate violations and stops once the cap is reached.

## Field paths

`FieldPath` builds the paths: `name`, `address.postcode`, `items[2].sku`. A
property segment is an ASCII letter or `_` followed by ASCII letters, digits,
`_` or `-`. Indexes are zero-based. A check on the whole value is reported as
`_root`. Paths come from developer-defined names and element positions, never
from client input.

## Rules without annotations

```java
import static io.axiom.validation.Rule.*;

static final Validator<Address> ADDRESS = Rules.of(Address.class)
        .field("street", Address::street, notBlank(), maxLength(80))
        .field("postcode", Address::postcode, notNull(), pattern("[0-9]{5}", 5));

static final Validator<Order> ORDER = Rules.of(Order.class)
        .field("customer", Order::customer, notBlank(), maxLength(80))
        .field("email", Order::email, notNull(), email())
        .field("priority", Order::priority, range(1, 5))
        .field("address", Order::address, notNull())
        .nested("address", Order::address, ADDRESS)
        .field("items", Order::items, notNull(), size(1, 50))
        .eachNested("items", Order::items, ITEM)
        .each("tags", Order::tags, notBlank(), maxLength(20))
        .check("end", order -> !order.end().isBefore(order.start()), "before_start")
        .check(order -> order.items() == null || order.items().size() <= order.limit(), "too_many_items");
```

| Rule | Code | Accepts `null` | Notes |
| --- | --- | --- | --- |
| `notNull()` | `not_null` | no | |
| `notBlank()` | `not_blank` | no | Text with at least one non-whitespace character |
| `notEmpty()` | `not_empty` | no | Text, collections, maps and arrays; other values pass |
| `length(min, max)`, `minLength`, `maxLength` | `size` | yes | Unicode code points, inclusive |
| `size(min, max)` | `size` | yes | Collection element count, inclusive |
| `min(n)`, `max(n)`, `range(min, max)` | `min`, `max`, `range` | yes | Exact comparison for integers, `BigInteger` and `BigDecimal`, and for finite `double`/`float` values; NaN fails |
| `pattern(regex)`, `pattern(regex, maxInputLength)` | `pattern` | yes | Whole-input match, see below |
| `email()` | `email` | yes | Syntax only, see below |
| `oneOf(values...)` | `one_of` | yes | Exact string match |
| `Rule.check(predicate, code)` | custom | yes | Predicate sees only non-null values |
| `Rule.checkNullable(predicate, code)` | custom | predicate decides | |

Any rule can take another code with `withCode("required")`. Codes use the core
syntax `[a-z][a-z0-9_.-]{0,63}` and are checked when the rule is created, as
are field names when a field is added.

Behavior of `Rules`:

- Every method returns a new instance. Partly built rule sets can be shared,
  extended and used from any number of threads.
- Steps run in declaration order. Each field reports only the first rule it
  fails, so `notNull(), email()` reports `not_null` for a missing value.
- `nested`, `each` and `eachNested` skip a `null` object or list, and
  `eachNested` skips `null` elements. Require presence explicitly with
  `field(name, accessor, notNull())` or `each(name, accessor, notNull())`.
- Paths from nested validators are prefixed: a nested `_root` violation is
  reported at the nested field itself, for example `items[3]`.
- Validating `null` reports `not_null` at `_root`.
- `include(validator)` adds any other validator, such as the Jakarta adapter.
- Exceptions thrown by accessors, predicates or nested validators propagate
  unchanged and become a generic 500 over HTTP.

## Jakarta Validation adapter

```java
record Item(@NotBlank String sku, @Min(1) int quantity) {}
record Order(@NotBlank @Size(max = 80) String customer, @Email String email,
             @NotNull @Valid Address address, List<@Valid @NotNull Item> items,
             Map<String, @NotBlank String> labels) {}

static final JakartaValidation VALIDATION = JakartaValidation.create();
var order = Validation.require(VALIDATION, command);
```

Applications declare `jakarta.validation:jakarta.validation-api` themselves so
they can use the annotations. `axiom-validation-jakarta` brings Hibernate
Validator on the runtime classpath. Its public API is the `JakartaValidation`
class, which uses only JDK and Axiom types.

- **Mapping.** Each constraint violation becomes a `Violation`. The field is
  the property path, with indexes for list and array elements
  (`items[1].sku`, `tags[0]`). Map keys and set positions are left out, so
  client-chosen keys are never echoed. The code is the annotation's simple
  name in snake case: `NotBlank` becomes `not_blank`, `DecimalMin` becomes
  `decimal_min` and `URL` becomes `url`. Custom constraints follow the same rule.
  Class-level constraints are reported at the object's path, or at `_root` for
  the validated value. Results are sorted by field and code, deduplicated and
  capped at 100.
- **Records and cascading.** Constraints on record components apply to the
  component's field. `@Valid` cascades into nested records. For containers,
  put it on the type argument (`List<@Valid Item>`). Hibernate Validator
  deprecates `@Valid List<Item>` and logs a warning for it.
- **Groups.** `JakartaValidation.create(Publishing.class)` checks only those
  groups. With no argument, it checks the default group.
- **Lifecycle.** Create one instance at startup and share it: it is
  thread-safe, and bootstrapping is relatively expensive. Close it when the
  application stops. `validate` fails with `IllegalStateException` after close.
- **Failures.** If the provider fails while validating, `validate` throws
  `InternalServerErrorException`, which becomes a 500 with code
  `internal_server_error`. This happens, for example, when a constraint is
  declared on an unsupported type or a custom `ConstraintValidator` throws.
  The provider exception is attached as the cause for logs and is never sent
  to clients.

## Security notes

**Message interpolation and Expression Language.** Hibernate Validator's
default message interpolation evaluates Jakarta Expression Language in
templates (`${...}`). A custom `ConstraintValidator` that puts client input
into `buildConstraintViolationWithTemplate` then lets a client run expressions
on the server. The adapter closes this path in several ways:

- It installs an interpolator that returns templates unchanged, so nothing
  is ever interpolated.
- It sets both Hibernate Expression Language feature levels to `NONE`.
- It ignores `META-INF/validation.xml`, so classpath configuration cannot
  restore the default interpolator.
- It ships no Expression Language implementation.
- It never reads messages or invalid values when building violations.

Tests send `${1+1}` and `#{...}` both in constraint messages and in values,
and check that the text comes back unevaluated and never appears in a response.

**Regular expressions.** Java's regex engine backtracks, so patterns such as
`(a+)+b` can take exponential time on crafted input. `pattern` compiles the
expression once, when the rule is created. Input longer than the limit fails
without being matched: the limit is 1000 characters by default, and
`pattern(regex, maxInputLength)` sets a smaller one. Keep expressions simple,
set the limit close to the longest valid value, and never build expressions
from client input. The same advice applies to Jakarta `@Pattern`, which has no
input cap. Pair it with `@Size(max = ...)`.

**E-mail.** `email()` checks syntax only and uses no regular expression. It
requires one `@`, then a local part of 1 to 64 printable ASCII characters
other than spaces and `"(),:;<>[\]`. The domain needs at least two
dot-separated labels made of ASCII letters, digits and inner hyphens, and the
whole address is at most 254 characters. Quoted local parts, IP literals and
internationalized addresses are rejected. It does not check that an address
exists. Jakarta `@Email` follows Hibernate Validator's own, more permissive
syntax.

## Limits

| Limit | Value |
| --- | --- |
| Violations per value | 100 (`Validation.MAX_VIOLATIONS`, also enforced by `ValidationException`) |
| Field path length | 256 characters (`FieldPath.MAX_LENGTH`). Longer paths stop at the last ancestor that fits |
| Element index | 999,999,999. Larger indexes are reported at the collection's path |
| `pattern` input | 1000 characters by default |
| `email` input | 254 characters |
| Code | `[a-z][a-z0-9_.-]{0,63}` |

Validation runs on the request's virtual thread and counts against the request
deadline. Bodies are already bounded by `maxRequestBody` (see [bodies](bodies.md)),
which also bounds collection sizes.

## Not covered yet

- `Context` integration such as `ctx.validatedBody(Type.class)`. This needs a
  hook in core and will follow the middleware work. `Validation.require` keeps
  working when that lands.
- Service-loaded default validators. Instances are created explicitly.
- OpenAPI or JSON Schema generation from rules or annotations.
- Route type metadata: declaring request and response types on routes, which
  would let tools and documentation read them.
- Validation of path parameters, query parameters and headers, method
  parameters and return values (Jakarta executable validation), and
  localized messages.
