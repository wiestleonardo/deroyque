package com.deroyque.activation.domain;

public class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(ActivationStatus from, ActivationStatus to) {
        super("Transição de status inválida: " + from + " -> " + to);
    }
}
