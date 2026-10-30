package com.deroyque.activation.intercomm.service;

import com.deroyque.activation.repository.domain.ActivationRequest;

public interface ActivationProviderIntercommService {

    /**
     * Sends the request id as the idempotency key. Throws IntegrationException when the call fails.
     */
    public abstract void activate(ActivationRequest activationRequest);

}
