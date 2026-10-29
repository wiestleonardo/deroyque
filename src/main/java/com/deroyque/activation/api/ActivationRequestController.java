package com.deroyque.activation.api;

import com.deroyque.activation.api.dto.ActivationRequestResponse;
import com.deroyque.activation.api.dto.CreateActivationRequestBody;
import com.deroyque.activation.api.dto.PageResponse;
import com.deroyque.activation.application.ActivationRequestService;
import com.deroyque.activation.application.CreateActivationCommand;
import com.deroyque.activation.domain.ActivationStatus;
import com.deroyque.activation.shared.CorrelationId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/requests")
public class ActivationRequestController {

    private final ActivationRequestService service;

    public ActivationRequestController(ActivationRequestService service) {
        this.service = service;
    }

    /**
     * 201 + Location quando cria; 200 com o registro existente quando é retry do mesmo conteúdo;
     * 409 quando o externalId já foi usado com conteúdo diferente.
     */
    @PostMapping
    public ResponseEntity<ActivationRequestResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                            @Valid @RequestBody CreateActivationRequestBody body) {
        var command = new CreateActivationCommand(
                ClientIdentity.of(jwt), body.externalId(), body.customerId(), body.productCode(),
                CorrelationId.current());
        var result = service.create(command);
        var response = ActivationRequestResponse.from(result.request());
        if (!result.created()) {
            return ResponseEntity.ok(response);
        }
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    public ActivationRequestResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ActivationRequestResponse.from(service.get(ClientIdentity.of(jwt), id));
    }

    @GetMapping
    public PageResponse<ActivationRequestResponse> search(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) ActivationStatus status,
            @RequestParam(required = false) String customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        var result = service.search(ClientIdentity.of(jwt), status, customerId, pageable);
        return PageResponse.from(result, ActivationRequestResponse::from);
    }
}
