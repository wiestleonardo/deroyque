package com.deroyque.activation.api;

import com.deroyque.activation.constants.RouteConstants;
import com.deroyque.activation.constants.ValidationConstants;
import com.deroyque.activation.dto.ActivationRequestDTO;
import com.deroyque.activation.dto.CreateActivationRequestDTO;
import com.deroyque.activation.dto.PageDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

@RequestMapping(path = RouteConstants.ActivationRequest.BASE, produces = MediaType.APPLICATION_JSON_VALUE)
public interface ActivationRequestController {

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public abstract ResponseEntity<ActivationRequestDTO> create(
			@Valid @RequestBody CreateActivationRequestDTO createActivationRequestDTO);

	@GetMapping(RouteConstants.ActivationRequest.GET_BY_ID)
	public abstract ResponseEntity<ActivationRequestDTO> get(@PathVariable UUID id);

	@GetMapping
	public abstract ResponseEntity<PageDTO<ActivationRequestDTO>> list(
			@RequestParam(required = false) String status, @RequestParam(required = false) String customerId,
			@RequestParam(defaultValue = "0") @Min(0) Integer page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(ValidationConstants.MAX_PAGE_SIZE) Integer size);

}
