package com.deroyque.activation.intercomm.service;

import com.deroyque.activation.repository.domain.ActivationRequest;
import com.deroyque.activation.vo.ActivationResultVO;

public interface ActivationProviderIntercommService {

    /**
     * Sends the request id as the idempotency key and never throws on remote failures:
     * the result comes back classified as success, permanent failure or transient failure.
     */
    public abstract ActivationResultVO activate(ActivationRequest activationRequest);

}
