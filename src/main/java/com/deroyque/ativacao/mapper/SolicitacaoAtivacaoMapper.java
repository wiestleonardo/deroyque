package com.deroyque.ativacao.mapper;

import com.deroyque.ativacao.dto.SolicitacaoAtivacaoDTO;
import com.deroyque.ativacao.repository.domain.SolicitacaoAtivacao;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, injectionStrategy = InjectionStrategy.CONSTRUCTOR,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface SolicitacaoAtivacaoMapper {

    @Mapping(target = "id", source = "idSolicitacaoAtivacao")
    @Mapping(target = "externalId", source = "codigoExterno")
    @Mapping(target = "customerId", source = "codigoCliente")
    @Mapping(target = "productCode", source = "codigoProduto")
    @Mapping(target = "status", source = "situacao")
    @Mapping(target = "attempts", source = "quantidadeTentativa")
    @Mapping(target = "failureReason", source = "motivoFalha")
    @Mapping(target = "createdAt", source = "dataCadastro")
    @Mapping(target = "updatedAt", source = "dataManutencao")
    SolicitacaoAtivacaoDTO map(SolicitacaoAtivacao solicitacaoAtivacao);

}
