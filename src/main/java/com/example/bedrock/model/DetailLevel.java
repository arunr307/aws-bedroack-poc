package com.example.bedrock.model;

/**
 * Controls how detailed an explanation should be
 * in {@code POST /api/code/explain}.
 */
public enum DetailLevel {

    /** 2–4 sentence high-level summary of what the code does. */
    BRIEF,

    /**
     * Line-by-line walkthrough covering purpose, logic, edge cases,
     * time/space complexity, and notable patterns or pitfalls.
     */
    DETAILED
}
