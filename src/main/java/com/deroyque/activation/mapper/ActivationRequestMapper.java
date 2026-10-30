package com.deroyque.activation.mapper;

import com.deroyque.activation.dto.ActivationRequestDTO;
import com.deroyque.activation.repository.domain.ActivationRequest;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, injectionStrategy = InjectionStrategy.CONSTRUCTOR,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ActivationRequestMapper {

    ActivationRequestDTO map(ActivationRequest activationRequest);

}
