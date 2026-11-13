# Insurance claim agents

Several AI agents adjudicating a car insurance claim, orchestrated by Skipper workflows: plain
Java 17, Gradle, a file-backed SQLite store.

A claim arrives as free text. An intake agent reads it, three specialist agents review it in
parallel, an adjudicator agent reconciles their opinions, and a human adjuster steps in when the
rules say a person must decide. Then the claim is paid, or the money set aside for it is released.

| Agent | Runs as | Its one job |
| --- | --- | --- |
| Intake | an action of the claim workflow | Read the claim; say what is missing |
| Coverage specialist | a child workflow | Does the policy cover this kind of incident? |
| Fraud specialist | a child workflow | Is anything about the claim or its history suspicious? |
| Damage specialist | a child workflow | Is the amount in line with what this repair costs? |
| Adjudicator | an action of the claim workflow | Reconcile the three opinions into pay or deny |

The agents propose. The workflow and a set of plain rules (`ClaimRules`) decide what happens: no
agent can pay more than the policy allows, pay against a coverage denial, or approve a large payout
without a person.

```bash
./gradlew build                  # compiles and runs the tests (23 tests, under a minute, no network)
./gradlew run                    # serves the claims page and the admin UI until Ctrl-C
./gradlew run --args="--demo"    # runs six sample claims, prints what happened, and exits
```

None of these needs an API key: by default the agents are scripted, so the same claim always gets
the same result.

## Trying it

`./gradlew run` serves a page at <http://localhost:8090/> for making claims. Six examples fill in
the form, and each takes a different path:

| Example | What happens |
| --- | --- |
| Cracked windshield | All three specialists approve and the claim is paid automatically, less the deductible. |
| Rear-end collision | Approved, but the payout is over the automatic limit, so it waits for you to act as the adjuster. |
| Stolen car | The fraud specialist flags a third claim in a year and asks for a person. |
| Vague report | The intake agent asks what happened and when, and the claim waits for your answer. |
| Hail damage | The coverage specialist finds the policy does not cover it, and the claim is denied. |
| Payment bounces | The claim is approved, the payment fails, and the money set aside for it is released. |

As the adjuster you can approve a different amount or deny the claim. Try approving more than the
policy can pay: the rules refuse a person's decision the same way they refuse an agent's.

Stop the process while a claim is waiting for an answer or an adjuster and start it again. The
claim is still there and can still be answered, because everything the agents did is in
`claims.db`.

Each claim links to its timeline in Skipper's admin UI, served at
<http://localhost:8090/skipper/admin/>: the intake, the three specialists running side by side as
child workflows, their reports arriving as signals, the adjudication attempts, the wait for the
adjuster, and the payment. Both pages listen on the loopback interface only. Set `CLAIMS_PORT` to
move them off 8090.

## The scripted demo

`./gradlew run --args="--demo"` runs the six examples without a browser, answering as the claimant
and as an adjuster who accepts the recommendation:

```
Cracked windshield ($400 on P-1001)
  Understood the claim as glass: A stone hit my windshield on the highway on 2027-03-02 and it cracked
  Coverage specialist: approve. The policy covers glass damage.
  Fraud specialist: approve. Nothing unusual in the claim or the policy's history.
  Damage specialist: approve. The amount is in line with the usual cost of $450.
  Refused the adjudicator's decision: a payout of $400 is more than this policy can pay for this claim, $150 after the $250 deductible
  Adjudicator: pay $150. We are paying the assessed damage, less your $250 deductible.
  => PAID $150 (automatic): We are paying the assessed damage, less your $250 deductible.

Stolen car ($11,000 on P-2002)
  adjuster approves $10,500
  ...
  Fraud specialist: refer. This would be claim number 3 on the policy in twelve months.
  ...
  Sent to an adjuster because the fraud specialist asked for a human review: ...; a payout of $10,500 is above the $2,000 limit for automatic approval
  The adjuster approved $10,500
  => PAID $10,500 (adjuster): Reviewed by an adjuster.

Payment bounces ($600 on P-4004)
  ...
  => COMPENSATION_COMPLETED: the payment failed and the reservation was released

payments ledger: [..., RESERVE rsv-5 $200, RELEASE rsv-5]
```

The WARN lines from `com.airbnb.skipper` on the last claim are the engine logging a failure the
workflow handles by design.

## Running it with a model

```bash
export ANTHROPIC_API_KEY=...
./gradlew run
```

With the key set, in either mode, `Main` swaps `ScriptedModel` for `ClaudeModel`. Each agent is then
one system prompt and one request through the Anthropic Java SDK, with a structured output matching
the record the workflow expects. Nothing else changes: the same rules, waits and payments apply. The
model is `claude-opus-5-5` unless `CLAIMS_MODEL` names another. What the agents say will vary; what
the rules let through will not.

## What Skipper adds to a team of agents

- **Each specialist is its own workflow.** It has its own retries and its own timeline, and a slow or
  failing one does not stop the other two.
- **A join with a deadline.** The claim waits for all three opinions, but only for ten minutes. After
  that it carries on with whoever answered, and the rules send it to a person because an opinion is
  missing. `aSpecialistThatNeverAnswersDoesNotHoldTheClaimUp` shows it.
- **No agent is paid for twice.** Every model call is a checkpointed action. The claim's workflow
  method replays from its first line after each wait and each signal, and reads the recorded answers.
  `noAgentIsAskedTwiceHoweverOftenTheClaimResumes` counts the calls.
- **Waiting for people costs nothing.** A claim can wait a week for the claimant and indefinitely for
  an adjuster without holding a thread or a process.
- **A failed payment undoes the reservation.** `PaymentActions.reserve` has a `@Compensate`; when the
  payment cannot be made, the workflow fails and the money goes back.
- **Outages are retries, not failures.** An overloaded model and an unavailable payment system are
  each wrapped in `RetryableError` with their own backoff.

## Where to look

| File | Shows |
| --- | --- |
| `ClaimWorkflow.java` | The orchestration: intake with a wait for the claimant, the fan-out to three child workflows, the join with a deadline, adjudication, the wait for the adjuster, payment |
| `SpecialistWorkflow.java` | One specialist agent as a child workflow: look up evidence, form an opinion, report back |
| `ClaimRules.java` | The guardrail: pure code that decides whether a decision may stand, and whether it needs a person |
| `AgentActions.java` | Every model call as a checkpointed action with a retry strategy for outages |
| `ReportActions.java` | A child signalling its parent, and retrying when the parent is busy |
| `PaymentActions.java` | The side effects, with `@Compensate` on the reservation |
| `ClaimsModel.java`, `ScriptedModel.java`, `ClaudeModel.java` | The inference interface and its two implementations |
| `ClaimResource.java`, `claims.html` | The claims page: starting an instance without waiting for it, reading it through a query, signalling replies and decisions |
| `WebServer.java` | The page and Skipper's admin UI hosted from a plain Java program: Jersey 2 on the JDK's HTTP server |
| `src/test/.../ClaimWorkflowTest.java` | `WorkflowTest` with a fake model: every path above, on an in-memory runtime where the children run too |
| `src/test/.../ClaudeModelTest.java` | `ClaudeModel` against a local stand-in for the Messages API |
| `src/test/.../ClaimsPageTest.java` | The example booted on a throwaway store and driven over HTTP |

## Design notes

- **Orchestration is code, not a model.** Which agents run, in what order, and what happens when
  they disagree or go quiet is the workflow method. Only the judgement inside each step is inference.
- **A specialist's doubt is an answer, not an error.** It returns `REFER` and the rules turn that into
  a wait for a person. Workflow code must not catch action failures, so anything the workflow is meant
  to route around has to come back as a value.
- **The rules bind people too.** The adjuster's decision goes through the same limit check as the
  adjudicator's, in the signal method itself, so an over-limit approval is never stored.
- **The opinions are frozen when the review window closes.** A report that arrives late is ignored,
  so every later step replays with the same opinions it first saw.
- **The adjudicator gets three attempts.** Each refusal is fed back as the reason to do better; if
  none passes, the claim goes to a person without a recommendation.

## Things this example had to work around

- **There is no built-in "wait for my children".** The claim starts each specialist detached with
  `asChildOf(this)`, and each specialist reports back by signalling the claim. The claim waits on its
  own state with `waitUntil`.
- **Three reports arrive at once.** Skipper applies signals to an instance one at a time and rejects a
  concurrent one, which shows as a failed `opinion` signal in the timeline. `ReportActions` retries,
  and the claim keeps one opinion per specialist, so a repeated delivery changes nothing.
- **The workflow factory has to be registered after the runtime is built.** Workflows and actions
  `@Inject` an `IWorkflowFactory` to start children and send signals, and it only exists once the
  runtime does. `SkipperSetup` and the test's `@BeforeEach` register it on the `SimpleInjector`.
- **A bare `List` cannot be a `@StateField` or an action result.** Skipper does not persist a top-level
  generic type, so the opinions and the journal are small records wrapping a list.
- **Java records work here because of the Jackson on the classpath.** Skipper runs on whichever
  Jackson 2.x the application ships. The Anthropic SDK brings a current one, and records need 2.12 or
  later. On the Jackson that Skipper compiles against, these types would have to be classes with a
  no-arg constructor.
- **State assigned in the workflow method is rebuilt on every replay.** The journal is reset on the
  method's first line; appending to the restored copy would duplicate its lines on each resume.
- **The admin UI needs a JAX-RS host on `javax.ws.rs`.** That is Jersey 2; Jersey 3 and later are on
  `jakarta.ws.rs`. Jersey logs a few startup warnings through `java.util.logging`; `WebServer` turns
  them down.
- **There is no public API for listing instances.** The page gets the list from the admin UI's own
  JSON endpoint, leaves out anything with a parent, and asks each claim for its view.
