package com.airbnb.skipper.testutils;

import java.util.Objects;
import java.util.Optional;

/**
 * A lightweight, OSS-safe request-context payload for Skipper tests.
 *
 * <p>Skipper treats the request context as a fully opaque {@code Any?} — the engine reads nothing
 * from it (see {@code IWorkflowFactory} / {@code RawRequestContextMiddleware}). The retained core
 * tests only need a concrete object to carry through the engine and, for a few propagation tests, a
 * thread-local so action / signal / compensation handlers can read back the value that was
 * installed for the current invocation.
 *
 * <p>This is the OSS test tree's own request-context type. It exposes the minimal surface the
 * retained tests need — {@code userId}, an optional accessor, an immutable builder, and a {@link
 * ThreadLocal} holder — with no dependency on any external auth / identity library.
 *
 * <p>Pair this with {@link TestRequestContextMiddleware} (installs / clears the thread-local around
 * each invocation) and {@link TestRequestContextSerde} (round-trips the payload across persistence)
 * to exercise the generic context-propagation path end-to-end.
 */
public final class TestRequestContext {

  private static final ThreadLocal<TestRequestContext> CURRENT = new ThreadLocal<>();

  private final String userId;

  private TestRequestContext(String userId) {
    this.userId = userId;
  }

  /** The user id carried by this context, or {@code null} if none was set. */
  public String getUserId() {
    return userId;
  }

  /** The user id as an {@link Optional}, mirroring the accessor the retained tests used. */
  public Optional<String> getUserIdOptional() {
    return Optional.ofNullable(userId);
  }

  /** Returns a new builder seeded with this context's values. */
  public Builder toBuilder() {
    return new Builder().userId(userId);
  }

  /** Returns a fresh builder. */
  public static Builder builder() {
    return new Builder();
  }

  // ── Thread-local holder (used by TestRequestContextMiddleware and propagation tests) ──

  /**
   * Returns the context installed on the current thread, or {@code null} if none is set. Handlers
   * running inside an action / signal / compensation read this to assert the context that the
   * middleware installed for the invocation.
   */
  public static TestRequestContext getCurrentRequestContext() {
    return CURRENT.get();
  }

  /** Installs {@code ctx} on the current thread (may be {@code null} to clear). */
  public static void setCurrentRequestContext(TestRequestContext ctx) {
    if (ctx == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(ctx);
    }
  }

  /** Clears any context installed on the current thread. */
  public static void clearAllRequestContext() {
    CURRENT.remove();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TestRequestContext)) {
      return false;
    }
    return Objects.equals(userId, ((TestRequestContext) o).userId);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(userId);
  }

  @Override
  public String toString() {
    return "TestRequestContext{userId=" + userId + '}';
  }

  /** Immutable builder for {@link TestRequestContext}. */
  public static final class Builder {
    private String userId;

    public Builder userId(String userId) {
      this.userId = userId;
      return this;
    }

    public TestRequestContext build() {
      return new TestRequestContext(userId);
    }
  }
}
