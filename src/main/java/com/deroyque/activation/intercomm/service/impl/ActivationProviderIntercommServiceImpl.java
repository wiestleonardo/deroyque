package com.deroyque.activation.intercomm.service.impl;

import com.deroyque.activation.dto.ApiProviderActivationDTO;
import com.deroyque.activation.intercomm.client.ActivationProviderClient;
import com.deroyque.activation.intercomm.service.ActivationProviderIntercommService;
import com.deroyque.activation.repository.domain.ActivationRequest;
import com.deroyque.activation.util.CorrelationUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ActivationProviderIntercommServiceImpl implements ActivationProviderIntercommService {

    private final ActivationProviderClient activationProviderClient;

    @Override
    public void activate(ActivationRequest activationRequest) {

        ApiProviderActivationDTO apiProviderActivationDTO = new ApiProviderActivationDTO(activationRequest.getId(), activationRequest.getCustomerId(),
                activationRequest.getProductCode());

        activationProviderClient.activate(activationRequest.getId().toString(), CorrelationUtil.getCurrent(), apiProviderActivationDTO);
    }

}
