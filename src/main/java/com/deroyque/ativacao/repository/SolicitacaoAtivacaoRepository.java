package com.deroyque.ativacao.repository;

import com.deroyque.ativacao.enumerator.SituacaoSolicitacaoAtivacaoEnum;
import com.deroyque.ativacao.repository.domain.SolicitacaoAtivacao;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SolicitacaoAtivacaoRepository extends JpaRepository<SolicitacaoAtivacao, UUID> {

    public abstract Optional<SolicitacaoAtivacao> findByCodigoSistemaOrigemAndCodigoExterno(String codigoSistemaOrigem, String codigoExterno);

    public abstract Optional<SolicitacaoAtivacao> findByIdSolicitacaoAtivacaoAndCodigoSistemaOrigem(UUID idSolicitacaoAtivacao,
            String codigoSistemaOrigem);

    @Query("""
            SELECT solicitacaoAtivacao
              FROM SolicitacaoAtivacao solicitacaoAtivacao
             WHERE solicitacaoAtivacao.codigoSistemaOrigem = :codigoSistemaOrigem
               AND (:situacao IS NULL OR solicitacaoAtivacao.situacao = :situacao)
               AND (:codigoCliente IS NULL OR solicitacaoAtivacao.codigoCliente = :codigoCliente)
            """)
    public abstract Page<SolicitacaoAtivacao> listarPorFiltro(String codigoSistemaOrigem, SituacaoSolicitacaoAtivacaoEnum situacao, String codigoCliente,
            Pageable pageable);

    @Query("""
            SELECT solicitacaoAtivacao
              FROM SolicitacaoAtivacao solicitacaoAtivacao
             WHERE solicitacaoAtivacao.situacao IN :listaSituacao
               AND solicitacaoAtivacao.dataProximaTentativa <= :agora
             ORDER BY solicitacaoAtivacao.dataProximaTentativa
            """)
    public abstract List<SolicitacaoAtivacao> listarProntoParaProcessar(List<SituacaoSolicitacaoAtivacaoEnum> listaSituacao, Instant agora, Limit limit);

}
