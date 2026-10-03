package com.jsgalactic.axiom.execution;

/**
 * Consistent admission counters for one listener, including all its routes.
 * @param active reserved or running tasks, including cancelled code that has not exited
 * @param queued waiting tasks; these have no execution thread
 * @param accepted total tasks accepted for immediate or queued execution
 * @param rejected total capacity, closed-dispatcher or executor-refusal rejections at submission
 * @param queueTimeouts total queue-wait expirations, excluding execution deadlines
 */
public record AdmissionSnapshot(int active, int queued, long accepted, long rejected, long queueTimeouts) { }
