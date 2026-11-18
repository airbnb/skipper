package com.example.claims;

import java.util.List;

/**
 * The intake agent's structured reading of a free-text claim. {@code missing} lists what the claimant still
 * has to supply, in words that can be shown to them; it is empty when the claim can be reviewed. The date is
 * YYYY-MM-DD, or empty when the claim does not give one.
 */
public record IntakeReport(IncidentType incidentType, String incidentDate, String summary, List<String> missing) {}
