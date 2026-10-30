package com.deroyque.activation.constants;

public final class FailureReasonConstants {

    private FailureReasonConstants() {

    }

    public static final String TIMEOUT = "PROVIDER_TIMEOUT";
    public static final String UNREACHABLE = "PROVIDER_UNREACHABLE";
    public static final String HTTP_ERROR_PREFIX = "PROVIDER_HTTP_";
    public static final String REJECTED_PREFIX = "PROVIDER_REJECTED_";
    public static final String MAX_ATTEMPTS_PREFIX = "MAX_ATTEMPTS_EXCEEDED:";
    public static final String LEASE_EXPIRED = "PROCESSING_LEASE_EXPIRED";

}
