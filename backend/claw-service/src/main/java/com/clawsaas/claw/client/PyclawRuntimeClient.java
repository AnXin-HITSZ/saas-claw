package com.clawsaas.claw.client;

/**
 * Stub for Python runtime API calls.
 */
public interface PyclawRuntimeClient {
    Object runAgent(Object request);
    Object resumeAgent(Object request);
}
