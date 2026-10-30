package com.deroyque.activation.intercomm.client;

import com.deroyque.activation.constants.HeaderConstants;
import com.deroyque.activation.constants.RouteConstants;
import com.deroyque.activation.dto.ApiProviderActivationDTO;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

@HttpExchange(contentType = MediaType.APPLICATION_JSON_VALUE)
public interface ActivationProviderClient {

    @PostExchange(RouteConstants.ActivationProvider.ACTIVATE)
    public abstract void activate(@RequestHeader(HeaderConstants.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader(name = HeaderConstants.CORRELATION_ID, required = false) String correlationId,
            @RequestBody ApiProviderActivationDTO apiProviderActivationDTO);

}
