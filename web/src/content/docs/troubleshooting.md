---
title: Troubleshooting
description: Common errors when building Skipper workflows and how to resolve them.
section: Guides
order: 20
---

A few issues come up often when getting started. Here's how to diagnose and fix them.

## My integration test times out

A workflow test that never completes usually means the scheduler isn't running or the
workflow is stuck waiting.

- Make sure the scheduler is started. If you extend `WorkflowTest`, this happens
  automatically. Otherwise call `runtime.skipperSchedulerManager.get().start()` on your
  `SkipperRuntime` before invoking workflows.
- Scroll up in the test logs for exceptions thrown during execution.
- Call `printEvents()` (provided by the test base) to print the workflow's event history —
  far easier to read than raw logs for understanding where it got stuck.

## "Unable to find a suitable serializer for X"

This means a value can't be serialized for persistence. It's most common with plain
POJOs/data classes. Check that:

1. **The top-level type is not generic.** `List<X>`, `Map<K, V>`, `Optional<X>` and the like
   are not allowed as top-level types. Wrap them in a simple non-generic class that has the
   collection as a field.
2. **`equals`/`hashCode` are defined.** Use a Kotlin `data class`, or Lombok `@Value` /
   `@Data` in Java.
3. **Jackson annotations are correct** if you've customized serialization.

As a last resort, if you're certain a type is serializable, you can annotate it to bypass
the checks — but be ready for serialization errors at runtime if it isn't.

## `InaccessibleObjectException: module java.base does not "opens java.lang"` when an action fails

On JDK 16 and newer, Skipper **0.5.0 and earlier** could not persist a failed action: the scheduler
logged `failed to serialize object: com.airbnb.skipper.RetryableError` with that cause, retried the
task every 30 seconds through its unexpected-error path instead of your retry strategy, and never
reached compensation. This is fixed in 0.5.1, which persists errors without reflecting into
`java.base`. If you are pinned to 0.5.0, start the JVM with

```
--add-opens java.base/java.lang=ALL-UNNAMED
```

for the service and for your test task.

## A workflow behaves unpredictably across resumes

This is almost always a **determinism** violation. Make sure the workflow method does not
read the clock, generate randomness, or perform I/O directly — move all of that into
[actions](/docs/core-concepts/) or a `checkpoint`. Also avoid catching `Throwable` or
`Error` around action calls, which interferes with Skipper's internal control flow.

## A workflow is stuck in `RETRIES_EXHAUSTED`

This is expected when using a `PersistentRetryStrategy` — the workflow is waiting for you to
fix the underlying issue and re-execute it. See
**[Error Handling](/docs/error-handling/)** and
**[Instance Management](/docs/instance-management/)**.

## Still stuck?

Open a discussion or issue on GitHub — see the **[Community](/community/)** page.
