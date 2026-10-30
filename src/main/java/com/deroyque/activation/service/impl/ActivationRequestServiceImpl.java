package com.deroyque.activation.service.impl;

import com.deroyque.activation.dto.ActivationRequestDTO;
import com.deroyque.activation.dto.CreateActivationRequestDTO;
import com.deroyque.activation.dto.PageDTO;
import com.deroyque.activation.enumerator.ActivationStatusEnum;
import com.deroyque.activation.enumerator.ErrorEnum;
import com.deroyque.activation.exception.BusinessServiceException;
import com.deroyque.activation.mapper.ActivationRequestMapper;
import com.deroyque.activation.repository.ActivationRequestRepository;
import com.deroyque.activation.repository.domain.ActivationRequest;
import com.deroyque.activation.service.ActivationRequestService;
import com.deroyque.activation.service.AuthenticatedSystemService;
import com.deroyque.activation.util.CorrelationUtil;
import com.deroyque.activation.vo.CreationResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActivationRequestServiceImpl implements ActivationRequestService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final ActivationRequestRepository activationRequestRepository;

    private final ActivationRequestMapper activationRequestMapper;

    private final AuthenticatedSystemService authenticatedSystemService;

    private final Clock clock;

    @Override
    public CreationResultVO create(CreateActivationRequestDTO createActivationRequestDTO) {

        String sourceSystem = authenticatedSystemService.getSourceSystem();

        Optional<ActivationRequest> existingActivationRequest = activationRequestRepository.findBySourceSystemAndExternalId(sourceSystem,
                createActivationRequestDTO.externalId());

        if (existingActivationRequest.isPresent()) {

            return this.resolveExisting(existingActivationRequest.get(), createActivationRequestDTO);
        }

        ActivationRequest activationRequest = ActivationRequest.create(sourceSystem, createActivationRequestDTO.externalId(),
                createActivationRequestDTO.customerId(), createActivationRequestDTO.productCode(), CorrelationUtil.getCurrent(), clock.instant());

        try {

            ActivationRequest savedActivationRequest = activationRequestRepository.saveAndFlush(activationRequest);

            log.info("Activation request {} created for externalId {}", savedActivationRequest.getId(), savedActivationRequest.getExternalId());

            return new CreationResultVO(activationRequestMapper.map(savedActivationRequest), true);

        } catch (DataIntegrityViolationException dataIntegrityViolationException) {

            // Two identical requests passed the lookup above at the same time; the unique index let only one of them insert.
            ActivationRequest winningActivationRequest = activationRequestRepository
                    .findBySourceSystemAndExternalId(sourceSystem, createActivationRequestDTO.externalId())
                    .orElseThrow(() -> dataIntegrityViolationException);

            return this.resolveExisting(winningActivationRequest, createActivationRequestDTO);
        }
    }

    @Override
    public ActivationRequestDTO get(UUID id) {

        return activationRequestRepository.findByIdAndSourceSystem(id, authenticatedSystemService.getSourceSystem())
                .map(activationRequestMapper::map)
                .orElseThrow(() -> new BusinessServiceException(ErrorEnum.REQUEST_NOT_FOUND));
    }

    @Override
    public PageDTO<ActivationRequestDTO> list(ActivationStatusEnum status, String customerId, Integer page, Integer size) {

        String customerIdFilter = customerId == null || customerId.isBlank() ? null : customerId;

        Page<ActivationRequest> activationRequestPage = activationRequestRepository.listByFilter(authenticatedSystemService.getSourceSystem(), status,
                customerIdFilter, PageRequest.of(page, size, NEWEST_FIRST));

        return new PageDTO<>(activationRequestPage.getContent().stream().map(activationRequestMapper::map).toList(), activationRequestPage.getNumber(),
                activationRequestPage.getSize(), activationRequestPage.getTotalElements(), activationRequestPage.getTotalPages());
    }

    private CreationResultVO resolveExisting(ActivationRequest activationRequest, CreateActivationRequestDTO createActivationRequestDTO) {

        if (activationRequest.hasSameContent(createActivationRequestDTO.customerId(), createActivationRequestDTO.productCode())) {

            return new CreationResultVO(activationRequestMapper.map(activationRequest), false);
        }

        throw new BusinessServiceException(ErrorEnum.DUPLICATE_REQUEST, Map.of("existingRequestId", activationRequest.getId()),
                createActivationRequestDTO.externalId());
    }

}
