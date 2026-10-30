package com.deroyque.ativacao.repository.domain;

import com.deroyque.ativacao.enumerator.ErroEnum;
import com.deroyque.ativacao.enumerator.SituacaoSolicitacaoAtivacaoEnum;
import com.deroyque.ativacao.exception.BusinessServiceException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Getter
@Entity
@Table(name = "tb_solicitacaoativacao")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SolicitacaoAtivacao implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final Integer TAMANHO_MAXIMO_MOTIVO_FALHA = 200;

    @Id
    @Column(name = "id_solicitacaoativacao", nullable = false, updatable = false)
    private UUID idSolicitacaoAtivacao;

    @Column(name = "cd_sistemaorigem", nullable = false, updatable = false)
    private String codigoSistemaOrigem;

    @Column(name = "cd_externo", nullable = false, updatable = false)
    private String codigoExterno;

    @Column(name = "cd_cliente", nullable = false, updatable = false)
    private String codigoCliente;

    @Column(name = "cd_produto", nullable = false, updatable = false)
    private String codigoProduto;

    @Enumerated(EnumType.STRING)
    @Column(name = "fl_situacao", nullable = false)
    private SituacaoSolicitacaoAtivacaoEnum situacao;

    @Column(name = "qt_tentativa", nullable = false)
    private Integer quantidadeTentativa;

    @Column(name = "dt_proximatentativa", nullable = false)
    private Instant dataProximaTentativa;

    @Column(name = "tx_motivofalha")
    private String motivoFalha;

    @Column(name = "cd_correlacao", updatable = false)
    private String codigoCorrelacao;

    @Column(name = "dt_cadastro", nullable = false, updatable = false)
    private Instant dataCadastro;

    @Column(name = "dt_manutencao", nullable = false)
    private Instant dataManutencao;

    @Version
    @Column(name = "nr_versao", nullable = false)
    private Long numeroVersao;

    public static SolicitacaoAtivacao criar(String codigoSistemaOrigem, String codigoExterno, String codigoCliente, String codigoProduto,
            String codigoCorrelacao, Instant agora) {

        SolicitacaoAtivacao solicitacaoAtivacao = new SolicitacaoAtivacao();
        solicitacaoAtivacao.idSolicitacaoAtivacao = UUID.randomUUID();
        solicitacaoAtivacao.codigoSistemaOrigem = codigoSistemaOrigem;
        solicitacaoAtivacao.codigoExterno = codigoExterno;
        solicitacaoAtivacao.codigoCliente = codigoCliente;
        solicitacaoAtivacao.codigoProduto = codigoProduto;
        solicitacaoAtivacao.codigoCorrelacao = codigoCorrelacao;
        solicitacaoAtivacao.situacao = SituacaoSolicitacaoAtivacaoEnum.PENDING;
        solicitacaoAtivacao.quantidadeTentativa = 0;
        solicitacaoAtivacao.dataProximaTentativa = agora;
        solicitacaoAtivacao.dataCadastro = agora;
        solicitacaoAtivacao.dataManutencao = agora;

        return solicitacaoAtivacao;
    }

    public void iniciarProcessamento(Instant agora, Duration tempoReserva) {

        this.mudarSituacao(SituacaoSolicitacaoAtivacaoEnum.PROCESSING, agora);
        this.quantidadeTentativa++;
        this.dataProximaTentativa = agora.plus(tempoReserva);
    }

    public void concluir(Instant agora) {

        this.mudarSituacao(SituacaoSolicitacaoAtivacaoEnum.COMPLETED, agora);
        this.motivoFalha = null;
    }

    public void falhar(String motivoFalha, Instant agora) {

        this.mudarSituacao(SituacaoSolicitacaoAtivacaoEnum.FAILED, agora);
        this.motivoFalha = limitarTamanho(motivoFalha);
    }

    public void agendarNovaTentativa(String motivoFalha, Instant dataProximaTentativa, Instant agora) {

        this.mudarSituacao(SituacaoSolicitacaoAtivacaoEnum.PENDING, agora);
        this.motivoFalha = limitarTamanho(motivoFalha);
        this.dataProximaTentativa = dataProximaTentativa;
    }

    public Boolean isReservaExpirada(Instant agora) {

        return this.situacao == SituacaoSolicitacaoAtivacaoEnum.PROCESSING && !this.dataProximaTentativa.isAfter(agora);
    }

    public Boolean isProntaParaProcessar(Instant agora) {

        return this.situacao == SituacaoSolicitacaoAtivacaoEnum.PENDING && !this.dataProximaTentativa.isAfter(agora);
    }

    public Boolean possuiMesmoConteudo(String codigoCliente, String codigoProduto) {

        return Objects.equals(this.codigoCliente, codigoCliente) && Objects.equals(this.codigoProduto, codigoProduto);
    }

    private void mudarSituacao(SituacaoSolicitacaoAtivacaoEnum situacaoDestino, Instant agora) {

        if (!this.situacao.podeMudarPara(situacaoDestino)) {

            throw new BusinessServiceException(ErroEnum.TRANSICAO_SITUACAO_INVALIDA, this.situacao, situacaoDestino);
        }

        this.situacao = situacaoDestino;
        this.dataManutencao = agora;
    }

    private static String limitarTamanho(String motivoFalha) {

        if (motivoFalha == null || motivoFalha.length() <= TAMANHO_MAXIMO_MOTIVO_FALHA) {

            return motivoFalha;
        }

        return motivoFalha.substring(0, TAMANHO_MAXIMO_MOTIVO_FALHA);
    }

}
