package com.deroyque.activation.api.impl;

import com.deroyque.activation.api.ActivationRequestController;
import com.deroyque.activation.constants.RouteConstants;
import com.deroyque.activation.dto.ActivationRequestDTO;
import com.deroyque.activation.dto.CreateActivationRequestDTO;
import com.deroyque.activation.dto.PageDTO;
import com.deroyque.activation.service.ActivationRequestService;
import com.deroyque.activation.util.CorrelationUtil;
import com.deroyque.activation.util.StringUtil;
import com.deroyque.activation.vo.CreationResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ActivationRequestControllerImpl implements ActivationRequestController {

    private static final List<String> SOURCE_SYSTEM_CLAIM_LIST = List.of("client_id", "azp");

    private final ActivationRequestService activationRequestService;

    @Override
    public ResponseEntity<ActivationRequestDTO> create(Jwt jwt, CreateActivationRequestDTO createActivationRequestDTO) {

        String sourceSystem = this.getSourceSystem(jwt);

        log.info("Creating activation request for externalId {} from system {}", createActivationRequestDTO.externalId(), sourceSystem);

        CreationResultVO creationResultVO = this.activationRequestService.create(sourceSystem, CorrelationUtil.getCurrent(),
                createActivationRequestDTO);

        if (!creationResultVO.created()) {

            return ResponseEntity.ok(creationResultVO.activationRequestDTO());
        }

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path(RouteConstants.ActivationRequest.GET_BY_ID)
                .buildAndExpand(creationResultVO.activationRequestDTO().id())
                .toUri();

        return ResponseEntity.created(location).body(creationResultVO.activationRequestDTO());
    }

    @Override
    public ResponseEntity<ActivationRequestDTO> get(Jwt jwt, UUID id) {

        return ResponseEntity.ok(this.activationRequestService.get(this.getSourceSystem(jwt), id));
    }

    @Override
    public ResponseEntity<PageDTO<ActivationRequestDTO>> list(Jwt jwt, String status, String customerId, Integer page, Integer size) {

        return ResponseEntity.ok(this.activationRequestService.list(this.getSourceSystem(jwt), status, customerId, page, size));
    }

    private String getSourceSystem(Jwt jwt) {

        return SOURCE_SYSTEM_CLAIM_LIST.stream()
                .map(jwt::getClaimAsString)
                .filter(StringUtil::notEmpty)
                .findFirst()
                .orElse(jwt.getSubject());
    }

}
