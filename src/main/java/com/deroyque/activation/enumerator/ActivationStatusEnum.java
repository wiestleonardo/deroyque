package com.deroyque.activation.enumerator;

public enum ActivationStatusEnum {

    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    public Boolean canTransitionTo(ActivationStatusEnum targetStatus) {

        return switch (this) {
            case PENDING -> targetStatus == PROCESSING;
            case PROCESSING -> targetStatus == COMPLETED || targetStatus == FAILED || targetStatus == PENDING;
            case COMPLETED, FAILED -> false;
        };
    }

}
