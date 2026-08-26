---
title: Errors & retries
description: Retry strategies, the exception classifier, and Skipper's error types.
section: Reference
order: 32
---

See [Error Handling](/docs/error-handling/) for how these fit together.

## Retry strategies &amp; classification

| Type | Description |
|---|---|
| `FixedRetryStrategy(delay, maxRetries)` | Constant delay between a fixed number of retries. |
| `ExponentialRetryStrategy(initialDelay, maxRetries, multiplier, maxDelay)` | Exponentially increasing delay, capped at `maxDelay`. |
| `PersistentRetryStrategy.of(strategy)` | Wraps any strategy so the workflow parks instead of failing once retries are exhausted. |
| `ExceptionClassifier` / `DefaultExceptionClassifier` | Decides whether a thrown exception is retryable. Extend the default to add your own cases. |

## Error types

| Type | Description |
|---|---|
| `SkipperError` | Base type for Skipper's own errors. |
| `RetryableError` | Throw to mark a failure as retryable. |
| `NonRetryableError` | Throw to fail the workflow without retrying. |
| `RetriesExhaustedError` | Surfaced to callers when a `PersistentRetryStrategy` is exhausted. |
| `ApplicationError` | Serializable wrapper Skipper converts foreign exceptions into for persistence. |
