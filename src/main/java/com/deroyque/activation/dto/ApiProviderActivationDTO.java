package com.deroyque.activation.dto;

import java.util.UUID;

public record ApiProviderActivationDTO(

        UUID requestId,

        String customerId,

        String productCode) {

}
