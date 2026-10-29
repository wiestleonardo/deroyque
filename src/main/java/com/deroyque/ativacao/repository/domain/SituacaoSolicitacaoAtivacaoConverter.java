package com.deroyque.ativacao.repository.domain;

import com.deroyque.ativacao.enumerator.SituacaoSolicitacaoAtivacaoEnum;
import com.deroyque.ativacao.util.ObjectUtil;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class SituacaoSolicitacaoAtivacaoConverter implements AttributeConverter<SituacaoSolicitacaoAtivacaoEnum, String> {

    @Override
    public String convertToDatabaseColumn(SituacaoSolicitacaoAtivacaoEnum situacaoSolicitacaoAtivacaoEnum) {

        return ObjectUtil.isNull(situacaoSolicitacaoAtivacaoEnum) ? null : situacaoSolicitacaoAtivacaoEnum.getCodigo();
    }

    @Override
    public SituacaoSolicitacaoAtivacaoEnum convertToEntityAttribute(String codigo) {

        return ObjectUtil.isNull(codigo) ? null : SituacaoSolicitacaoAtivacaoEnum.getByCodigo(codigo);
    }

}
