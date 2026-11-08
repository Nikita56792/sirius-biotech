# Event repository contract

Package: `org.sirius.biotech.data`. Plain Java 8; no Android dependencies,
Gradle integration, UI changes, or network access.

## Public API

```java
public interface EventRepository {
    List<Session> sessions();
    List<Person> people();
    List<Post> posts();
    List<Partner> partners();
    boolean authenticate(String code);
    boolean demoMode();
    String apiStatus();
}
```

`Session`, `Person`, `Post`, and `Partner` are public static final nested types
of `EventRepository`. All fields are public final Strings, initialized by public
constructors in this order:

- `Session(String id, String title, String time, String room, String track, String speaker, String description)`
- `Person(String id, String name, String role, String organization)`
- `Post(String title, String body)`
- `Partner(String name, String description)`

`public final class DemoRepository implements EventRepository` has a public
no-argument constructor. Instantiate it through the interface:

```java
EventRepository repository = new DemoRepository();
```

## Offline semantics

- Returns non-null, stable, unmodifiable lists of immutable model objects:
  eight sessions, six people, three posts, and three partners.
- All people and organizations are fictional demo fixtures, not confirmed
  speakers or partners. Session times and rooms are explicitly demo placeholders.
- The event year is 2027; dates are TBD. No specific event date is asserted.
- `authenticate` returns true only for the exact String `"123456"`. Null,
  whitespace, other digits, and all other inputs return false. It does not trim
  or normalize input, establish a session, create an account, or persist state.
  The code is a public demo convenience, not a security mechanism.
- `demoMode()` always returns true. `apiStatus()` is a human-readable offline
  status message, not a machine-readable connectivity check.
- All calls are synchronous and in-memory. There are no permissions, network
  requests, file reads/writes, clocks, or external dependencies.

## Backend seam

Consumers should depend on `EventRepository`, not on the fixture implementation.
A future backend implementation can map responses into these model types and
provide real authentication semantics. This interface does not yet define
asynchronous loading, error handling, token storage, or transport behavior; those
must be designed explicitly before adding a network implementation. Do not put
blocking network calls on the Android main thread or silently fall back to demo
authentication in production.
