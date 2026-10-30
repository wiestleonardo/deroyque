package com.deroyque.activation.service;

import java.util.UUID;

public interface ActivationProcessingService {

    public abstract void processPending();

    public abstract void process(UUID id);

}
