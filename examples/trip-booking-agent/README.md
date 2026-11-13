# Trip-booking agent

An AI agent built as a Skipper workflow: plain Java 17, Gradle, a file-backed SQLite store. It takes a
free-text request ("a long weekend in Lisbon under $1,500"), plans a trip, waits for the traveler to
approve it, and books the flight, the hotel and the car.

The point of the example is the split between the two kinds of step:

| Inference (the model proposes) | Deterministic (the workflow disposes) |
| --- | --- |
| Read the request into a destination and dates | Search the provider for offers |
| Pick one offer per leg | Refuse any pick that is unknown, changes a booked leg, or is over budget |
| Pick a replacement when an offer sells out | Park for the traveler's approval, with an expiry |
| | Book each leg, and cancel what was booked if the trip cannot be completed |

The model never touches the provider. It returns a structured proposal, `ItineraryValidator` checks it,
and only a proposal that passes is acted on. A refused proposal goes back to the model with the reason.

```bash
./gradlew build                  # compiles and runs the tests (19 tests, a few seconds, no network)
./gradlew run                    # serves the playground and the admin UI until Ctrl-C
./gradlew run --args="--demo"    # runs three scripted trips, prints what happened, and exits
```

Neither needs an API key: the default planner is scripted, so the same input gives the same result.

## Playing with it

`./gradlew run` serves a playground at <http://localhost:8090/>:

- **Plan a trip.** Type a request and a budget. The trip appears with the planner's proposal, the
  itinerary the validator refused on the way there, and Approve and Decline buttons.
- **Force a replan.** Mark the proposed hotel sold out in the inventory panel, then approve. The
  flight is booked, the hotel is not, and the agent picks another that fits around the flight.
- **Force a rollback.** Sell out every hotel the budget allows (`HT-BOUTIQUE` and `HT-HOSTEL` at
  $1,500), then approve. No replacement fits, the trip is abandoned, and the ledger shows the flight
  booked and then cancelled.
- **Starve it.** A budget under $800 fits no combination; the planner gets three attempts and the trip
  is declined without booking anything.
- **Restart it.** Stop the process while a trip is waiting for approval and start it again. The trip
  is still listed and can still be approved, because the proposal lives in `trips.db`. (The fake
  provider's sold-out switches and ledger are in memory and reset.)

Each trip links to its timeline in Skipper's admin UI, served at
<http://localhost:8090/skipper/admin/>: every planner call, the search, the approval timer, the
`decide` signal, each booking, and any compensation. Click a step to see what the planner was given
and what it answered.

Both pages listen on the loopback interface only, because the admin UI can cancel, retry and delete
instances. Set `TRIP_AGENT_PORT` to move them off 8090.

## The scripted demo

`./gradlew run --args="--demo"` runs three trips without a browser:

```
planner: scripted (set ANTHROPIC_API_KEY to use Claude)

trip-61619ead-booked -> WAITING, stage=AWAITING_APPROVAL
  proposal: FL-DIRECT + HT-BOUTIQUE + CAR-SUV (the best-rated combination within budget)
trip-61619ead-booked -> COMPLETED, BOOKED [cnf-1, cnf-2, cnf-3] for $1,490
  refused FL-DIRECT + HT-PALACE + CAR-SUV: total $1,910 exceeds the budget $1,500 by $410
  accepted FL-DIRECT + HT-BOUTIQUE + CAR-SUV at $1,490

trip-61619ead-replanned -> WAITING, stage=AWAITING_APPROVAL
  proposal: FL-DIRECT + HT-BOUTIQUE + CAR-SUV (the best-rated combination within budget)
trip-61619ead-replanned -> COMPLETED, BOOKED [cnf-4, cnf-5, cnf-6] for $1,220
  ...
  HOTEL HT-BOUTIQUE is sold out, replanning around [FLIGHT]
  refused FL-DIRECT + HT-PALACE + CAR-SUV: total $1,910 exceeds the budget $1,500 by $410
  accepted FL-DIRECT + HT-HOSTEL + CAR-SUV at $1,220

trip-61619ead-abandoned -> WAITING, stage=AWAITING_APPROVAL
  proposal: FL-DIRECT + HT-BOUTIQUE + CAR-SUV (the best-rated combination within budget)
trip-61619ead-abandoned -> COMPENSATION_COMPLETED
  ...
  HOTEL HT-HOSTEL is sold out, replanning around [FLIGHT]
  refused FL-DIRECT + HT-PALACE + CAR-COMPACT: total $1,730 exceeds the budget $1,500 by $230

provider ledger: [..., BOOK cnf-7 FL-DIRECT $620, CANCEL cnf-7]
```

The three trips are: one that is planned, approved and booked; one whose hotel sells out after
approval, so the planner picks another that fits around the flight already paid for; and one where no
replacement fits the budget, so the workflow fails and Skipper cancels the flight. The WARN lines
from `com.airbnb.skipper` on the third trip are the engine logging a failure the workflow raises by
design.

## Running it with a model

```bash
export ANTHROPIC_API_KEY=...
./gradlew run
```

With the key set, in either mode, `Main` swaps `ScriptedPlanner` for `ClaudePlanner`, which makes one request per
inference step through the Anthropic Java SDK and asks for a structured output matching the record the
workflow expects. Nothing else changes: the same validator, approval and bookings apply. The model
is `claude-opus-5-5` unless `TRIP_AGENT_MODEL` names another. Which offers it picks will vary; what
the workflow lets through will not.

## What Skipper adds to an agent

- **A model call is paid for once.** `PlannerActions` makes each call a checkpointed action. When the
  instance resumes after the approval, or after a crash, the workflow method replays and reads the
  recorded answers. `theModelIsNotAskedAgainWhenTheWorkflowResumes` counts the calls to show it.
- **The agent can wait a day for a person** without holding a thread or a process. The proposal, the
  decision and the bookings are in `trips.db`.
- **A half-booked trip undoes itself.** `TravelActions.book` has a `@Compensate`; when the workflow
  gives up, every confirmed booking is cancelled, newest first.
- **Outages are retries, not failures.** A rate-limited or overloaded model and an unavailable provider
  are each wrapped in `RetryableError` with their own backoff. A bad request or a refusal is not
  retried.

## Where to look

| File | Shows |
| --- | --- |
| `TripBookingWorkflow.java` | The whole agent: the bounded propose-validate loop, the approval park, booking with replanning |
| `ItineraryValidator.java` | The guardrail: pure code that decides whether a proposal may be acted on |
| `PlannerActions.java` | Inference as checkpointed actions with a retry strategy for model outages |
| `TravelActions.java` | The side effects, with `@Compensate` on the booking |
| `TripPlanner.java`, `ScriptedPlanner.java`, `ClaudePlanner.java` | The inference interface and its two implementations |
| `TripResource.java`, `playground.html` | The playground: starting an instance without waiting for it, reading it through queries, signalling the decision |
| `WebServer.java` | The playground and Skipper's admin UI hosted from a plain Java program: Jersey 2 on the JDK's HTTP server |
| `PlanningContext.java` | The one input shape that serves the first plan and every replan |
| `src/test/.../TripBookingWorkflowTest.java` | `WorkflowTest` with a fake planner: refusals, approval, expiry, replanning, compensation, retries |
| `src/test/.../ClaudePlannerTest.java` | `ClaudePlanner` against a local stand-in for the Messages API |
| `src/test/.../PlaygroundTest.java` | The example booted on a throwaway store and driven over HTTP: plan, sell out, approve, rebook |

## Design notes

- **Sold out is an answer, not an exception.** `book` returns a `BookingOutcome` and the workflow
  branches on it. Workflow code must not catch action failures, so anything the workflow is meant to
  route around has to come back as a value. Only "no replacement fits" is thrown, because that is the
  case that should fail the instance and trigger compensation.
- **The budget is workflow input, not something the model reads out of the request.** The limit the
  validator enforces never passes through inference.
- **Approval covers the budget, not the exact offers.** A replacement chosen after a sell-out is
  booked without asking again, as long as it passes the validator. A stricter agent would park for a
  second approval there.
- **The planner gets three attempts per plan.** Each refusal is fed back as the reason to do better;
  after the third the workflow stops asking.

## Things this example had to work around

- **A bare `List` cannot be a `@StateField` or an action result.** Skipper does not persist a top-level
  generic type, so the journal and the search results are small records wrapping a list.
- **Java records work here because of the Jackson on the classpath.** Skipper persists inputs, results
  and state as JSON and runs on whichever Jackson 2.x the application ships. The Anthropic SDK brings
  a current one, and records need 2.12 or later. On the Jackson that Skipper compiles against, these
  types would have to be classes with a no-arg constructor, as in the other examples.
- **The admin UI needs a JAX-RS host on `javax.ws.rs`.** That is Jersey 2; Jersey 3 and later are on
  `jakarta.ws.rs` and do not see the resource's annotations. Jersey logs a few startup warnings through
  `java.util.logging` about the resource's root path and optional features; `WebServer` turns them down.
- **The future does not complete while the workflow is parked.** The playground and the demo read
  the persisted status, the proposal and the outcome through `@QueryMethod`s instead.
- **There is no public API for listing instances.** The playground page gets the list from the admin
  UI's own JSON endpoint and then asks each trip for its view.
- **State assigned in the workflow method is rebuilt on every replay.** The journal is reset on the
  method's first line; appending to the restored copy would duplicate its lines on each resume.
