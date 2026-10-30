package com.deroyque.activation.vo;

import java.time.Duration;

public sealed interface ActivationResultVO {

    public record Success() implements ActivationResultVO {

    }

    public record PermanentFailure(String reason) implements ActivationResultVO {

    }

    public record TransientFailure(String reason, Duration retryAfter) implements ActivationResultVO {

        public TransientFailure(String reason) {

            this(reason, null);
        }

    }

}
