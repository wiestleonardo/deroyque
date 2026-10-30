package com.deroyque.activation.enumerator;

import com.deroyque.activation.exception.BusinessServiceException;

import java.util.Arrays;

public enum ActivationStatusEnum {

    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    public static ActivationStatusEnum getByCode(String code) {

        return Arrays.stream(values())
                .filter(activationStatusEnum -> activationStatusEnum.name().equals(code))
                .findFirst()
                .orElseThrow(() -> new BusinessServiceException(ErrorEnum.INVALID_STATUS));
    }

    public Boolean canTransitionTo(ActivationStatusEnum targetStatus) {

        return switch (this) {
            case PENDING -> targetStatus == PROCESSING;
            case PROCESSING -> targetStatus == COMPLETED || targetStatus == FAILED || targetStatus == PENDING;
            case COMPLETED, FAILED -> false;
        };
    }

    public Boolean isFinal() {

        return this == COMPLETED || this == FAILED;
    }

}
