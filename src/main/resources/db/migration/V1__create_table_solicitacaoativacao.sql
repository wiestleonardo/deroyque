CREATE TABLE tb_solicitacaoativacao (
    id_solicitacaoativacao UUID                     NOT NULL,
    cd_sistemaorigem       VARCHAR(100)             NOT NULL,
    cd_externo             VARCHAR(64)              NOT NULL,
    cd_cliente             VARCHAR(64)              NOT NULL,
    cd_produto             VARCHAR(64)              NOT NULL,
    fl_situacao            VARCHAR(20)              NOT NULL,
    qt_tentativa           INTEGER                  NOT NULL DEFAULT 0,
    dt_proximatentativa    TIMESTAMP WITH TIME ZONE NOT NULL,
    tx_motivofalha         VARCHAR(200),
    cd_correlacao          VARCHAR(64),
    dt_cadastro            TIMESTAMP WITH TIME ZONE NOT NULL,
    dt_manutencao          TIMESTAMP WITH TIME ZONE NOT NULL,
    nr_versao              BIGINT                   NOT NULL,
    CONSTRAINT solicitacaoativacao_pk PRIMARY KEY (id_solicitacaoativacao),
    CONSTRAINT solicitacaoativacao_fl_situacao_ck CHECK (fl_situacao IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE UNIQUE INDEX solicitacaoativacao_cd_sistemaorigem_cd_externo_idx_unq
    ON tb_solicitacaoativacao (cd_sistemaorigem, cd_externo);

CREATE INDEX solicitacaoativacao_fl_situacao_dt_proximatentativa_idx
    ON tb_solicitacaoativacao (fl_situacao, dt_proximatentativa);

CREATE INDEX solativacao_cd_sistemaorigem_fl_situacao_dt_cadastro_idx
    ON tb_solicitacaoativacao (cd_sistemaorigem, fl_situacao, dt_cadastro);

CREATE INDEX solativacao_cd_sistemaorigem_cd_cliente_dt_cadastro_idx
    ON tb_solicitacaoativacao (cd_sistemaorigem, cd_cliente, dt_cadastro);
