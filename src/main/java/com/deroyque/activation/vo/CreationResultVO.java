package com.deroyque.activation.vo;

import com.deroyque.activation.dto.ActivationRequestDTO;

public record CreationResultVO(ActivationRequestDTO activationRequestDTO, Boolean created) {

}
