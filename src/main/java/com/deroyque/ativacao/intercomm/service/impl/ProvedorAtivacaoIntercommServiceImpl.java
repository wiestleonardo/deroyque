package com.deroyque.ativacao.intercomm.service.impl;

import com.deroyque.ativacao.dto.ApiAtivacaoProvedorDTO;
import com.deroyque.ativacao.intercomm.client.ProvedorAtivacaoClient;
import com.deroyque.ativacao.intercomm.service.ProvedorAtivacaoIntercommService;
import com.deroyque.ativacao.repository.domain.SolicitacaoAtivacao;
import com.deroyque.ativacao.util.CorrelacaoUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProvedorAtivacaoIntercommServiceImpl implements ProvedorAtivacaoIntercommService {

    private final ProvedorAtivacaoClient provedorAtivacaoClient;

    @Override
    public void ativar(SolicitacaoAtivacao solicitacaoAtivacao) {

        ApiAtivacaoProvedorDTO apiAtivacaoProvedorDTO = new ApiAtivacaoProvedorDTO(solicitacaoAtivacao.getIdSolicitacaoAtivacao(),
                solicitacaoAtivacao.getCodigoCliente(), solicitacaoAtivacao.getCodigoProduto());

        provedorAtivacaoClient.ativar(solicitacaoAtivacao.getIdSolicitacaoAtivacao().toString(), CorrelacaoUtil.adquirirAtual(), apiAtivacaoProvedorDTO);
    }

}
