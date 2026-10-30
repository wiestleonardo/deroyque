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
import com.deroyque.activation.util.StringUtil;
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

    private static final String SORT_FIELD = "createdAt";
    private static final String EXISTING_ID_PROPERTY = "existingRequestId";

    private final ActivationRequestRepository activationRequestRepository;

    private final ActivationRequestMapper activationRequestMapper;

    private final Clock clock;

    @Override
    public CreationResultVO create(String sourceSystem, String correlationId, CreateActivationRequestDTO createActivationRequestDTO) {

        Optional<ActivationRequest> existingActivationRequest = this.activationRequestRepository
                .findBySourceSystemAndExternalId(sourceSystem, createActivationRequestDTO.externalId());

        if (existingActivationRequest.isPresent()) {

            return this.resolveExisting(existingActivationRequest.get(), createActivationRequestDTO);
        }

        ActivationRequest activationRequest = ActivationRequest.create(sourceSystem, createActivationRequestDTO.externalId(),
                createActivationRequestDTO.customerId(), createActivationRequestDTO.productCode(), correlationId, this.clock.instant());

        try {

            ActivationRequest savedActivationRequest = this.activationRequestRepository.saveAndFlush(activationRequest);

            log.info("Activation request {} created for externalId {}", savedActivationRequest.getId(), savedActivationRequest.getExternalId());

            return new CreationResultVO(this.activationRequestMapper.map(savedActivationRequest), Boolean.TRUE);

        } catch (DataIntegrityViolationException dataIntegrityViolationException) {

            // Two requests passed the lookup above at the same time; the unique index decided which one is saved.
            ActivationRequest winningActivationRequest = this.activationRequestRepository
                    .findBySourceSystemAndExternalId(sourceSystem, createActivationRequestDTO.externalId())
                    .orElseThrow(() -> dataIntegrityViolationException);

            log.info("Concurrent creation of externalId {} resolved by the unique index", createActivationRequestDTO.externalId());

            return this.resolveExisting(winningActivationRequest, createActivationRequestDTO);
        }
    }

    @Override
    public ActivationRequestDTO get(String sourceSystem, UUID id) {

        ActivationRequest activationRequest = this.activationRequestRepository.findByIdAndSourceSystem(id, sourceSystem)
                .orElseThrow(() -> new BusinessServiceException(ErrorEnum.REQUEST_NOT_FOUND));

        return this.activationRequestMapper.map(activationRequest);
    }

    @Override
    public PageDTO<ActivationRequestDTO> list(String sourceSystem, String status, String customerId, Integer page, Integer size) {

        ActivationStatusEnum activationStatusEnum = StringUtil.notEmpty(status) ? ActivationStatusEnum.getByCode(status) : null;
        String customerIdFilter = StringUtil.notEmpty(customerId) ? customerId : null;

        Page<ActivationRequest> activationRequestPage = this.activationRequestRepository.listByFilter(sourceSystem, activationStatusEnum,
                customerIdFilter, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, SORT_FIELD)));

        return new PageDTO<>(activationRequestPage.getContent().stream().map(this.activationRequestMapper::map).toList(),
                activationRequestPage.getNumber(), activationRequestPage.getSize(), activationRequestPage.getTotalElements(),
                activationRequestPage.getTotalPages());
    }

    private CreationResultVO resolveExisting(ActivationRequest activationRequest, CreateActivationRequestDTO createActivationRequestDTO) {

        if (activationRequest.hasSameContent(createActivationRequestDTO.customerId(), createActivationRequestDTO.productCode())) {

            return new CreationResultVO(this.activationRequestMapper.map(activationRequest), Boolean.FALSE);
        }

        throw new BusinessServiceException(ErrorEnum.DUPLICATE_REQUEST, Map.of(EXISTING_ID_PROPERTY, activationRequest.getId()),
                createActivationRequestDTO.externalId());
    }

}
