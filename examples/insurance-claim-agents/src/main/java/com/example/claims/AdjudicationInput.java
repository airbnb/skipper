package com.example.claims;

import java.util.List;

/** What the adjudicator decides from. {@code rejections} is why the rules refused its previous decision. */
public record AdjudicationInput(CaseFile file, Opinions opinions, List<String> rejections) {}
