package com.deroyque.activation.api.impl;

import com.deroyque.activation.api.ActivationRequestController;
import com.deroyque.activation.constants.RouteConstants;
import com.deroyque.activation.dto.ActivationRequestDTO;
import com.deroyque.activation.dto.CreateActivationRequestDTO;
import com.deroyque.activation.dto.PageDTO;
import com.deroyque.activation.enumerator.ActivationStatusEnum;
import com.deroyque.activation.service.ActivationRequestService;
import com.deroyque.activation.vo.CreationResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ActivationRequestControllerImpl implements ActivationRequestController {

	private final ActivationRequestService activationRequestService;

	@Override
	public ResponseEntity<ActivationRequestDTO> create(CreateActivationRequestDTO createActivationRequestDTO) {

		log.info("Creating activation request for externalId {}", createActivationRequestDTO.externalId());

		CreationResultVO creationResultVO = activationRequestService.create(createActivationRequestDTO);

		if (!creationResultVO.created()) {

			return ResponseEntity.ok(creationResultVO.activationRequestDTO());
		}

		URI location = ServletUriComponentsBuilder.fromCurrentRequest().path(RouteConstants.ActivationRequest.GET_BY_ID)
				.buildAndExpand(creationResultVO.activationRequestDTO().id()).toUri();

		return ResponseEntity.created(location).body(creationResultVO.activationRequestDTO());
	}

	@Override
	public ResponseEntity<ActivationRequestDTO> get(UUID id) {

		return ResponseEntity.ok(activationRequestService.get(id));
	}

	@Override
	public ResponseEntity<PageDTO<ActivationRequestDTO>> list(ActivationStatusEnum status, String customerId, Integer page,
			Integer size) {

		return ResponseEntity.ok(activationRequestService.list(status, customerId, page, size));
	}

}
