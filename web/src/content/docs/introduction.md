---
title: Introduction
description: Skipper is a lightweight, embeddable workflow engine that brings durable execution to any JVM service.
section: Getting Started
order: 1
---

Skipper lets you write complex, long-running business processes as ordinary Java or
Kotlin code — and guarantees they run to completion in spite of failures. There is no
separate cluster to operate and no new datastore to manage: Skipper is a **library you
embed directly in your service**, running in the same process as your application or as a
sidecar.

## Durable execution

The core guarantee Skipper provides is **durable execution**: once a workflow starts, it
is guaranteed to reach a terminal state, even across process crashes, deploys, and
transient downstream failures. You write the business logic; Skipper handles retries,
failure classification, and persistence of progress — so your code stays free of
low-level error-handling plumbing.

This makes Skipper a good fit for processes that today are spread across queues, cron
jobs, and hand-rolled state machines: payments and transfers, order fulfilment, multi-step
approvals, provisioning, and any saga that must not be left half-finished.

## What makes Skipper different

Skipper was designed around a specific set of principles:

- **Succinct ergonomics.** A workflow is a class; an action is a method. The programming
  model is small and gets out of your way.
- **No new single point of failure.** Skipper runs inside your service, so it does not add
  a shared cluster that every team depends on.
- **Reuse your existing storage.** Skipper persists to the same kind of datastore your
  service already uses (for example MySQL), behind pluggable interfaces — so it avoids
  introducing a new critical dependency.
- **Self-serve.** It is simple enough to adopt without a dedicated platform team to operate
  it.
- **Stay out of the way of performance.** Skipper should not cap your host system's
  throughput or scalability.

If you have considered a workflow engine such as Temporal but did not want to run and
operate a cluster, Skipper covers many of the same use cases as an embedded library.

## Is Skipper a good fit?

Skipper is likely a good fit if:

- You need **durable execution** — the process must run to a terminal state despite failures.
- Your process **coordinates multiple steps**, long waits, or human approvals.
- You want workflow durability **without operating a separate cluster**.

Skipper is probably **not** the right tool if:

- Your process does not require durable execution.
- Your service is not on the JVM.

## Where to next

- **[Quickstart](/docs/quickstart/)** — install Skipper and run a workflow.
- **[Core Concepts](/docs/core-concepts/)** — workflows, actions, determinism, and state.
- **[Your First Workflow](/docs/first-workflow/)** — build and test one end to end.
