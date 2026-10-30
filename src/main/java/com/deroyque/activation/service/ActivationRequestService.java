package com.deroyque.activation.service;

import com.deroyque.activation.dto.ActivationRequestDTO;
import com.deroyque.activation.dto.CreateActivationRequestDTO;
import com.deroyque.activation.dto.PageDTO;
import com.deroyque.activation.vo.CreationResultVO;

import java.util.UUID;

public interface ActivationRequestService {

    /**
     * The same externalId with the same content returns the existing record with {@code created = false};
     * with different content it throws DUPLICATE_REQUEST.
     */
    public abstract CreationResultVO create(String sourceSystem, String correlationId, CreateActivationRequestDTO createActivationRequestDTO);

    public abstract ActivationRequestDTO get(String sourceSystem, UUID id);

    public abstract PageDTO<ActivationRequestDTO> list(String sourceSystem, String status, String customerId, Integer page, Integer size);

}
