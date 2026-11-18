package com.example.claims;

import java.util.List;

/**
 * Claims that each take a different path through the workflow. The page offers them as examples and the
 * scripted demo runs them. {@code reply} is what the claimant answers if asked for more information.
 */
public final class Samples {
  private Samples() {}

  public record Sample(String label, String policyNumber, String description, long claimedCents, String reply) {}

  public static final List<Sample> ALL = List.of(
      new Sample("Cracked windshield", "P-1001",
          "A stone hit my windshield on the highway on 2027-03-02 and it cracked.", 40_000, null),
      new Sample("Rear-end collision", "P-1001",
          "I was rear-ended at a traffic light on 2027-03-05. The bumper and the trunk lid need replacing.", 420_000, null),
      new Sample("Stolen car", "P-2002",
          "My car was stolen from the street outside my house on 2027-03-01. Police report number 88213.", 1_100_000, null),
      new Sample("Vague report", "P-1001",
          "My car got damaged and I need it fixed.", 90_000,
          "A shopping cart hit the driver's door on 2027-03-03 and left a dent."),
      new Sample("Hail damage", "P-3003",
          "A hail storm on 2027-03-04 damaged the roof and the hood.", 300_000, null),
      new Sample("Payment bounces", "P-4004",
          "A branch fell and broke my rear window glass on 2027-03-06.", 60_000, null));
}
