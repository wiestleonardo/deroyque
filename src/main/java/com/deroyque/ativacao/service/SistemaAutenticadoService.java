package com.deroyque.ativacao.service;

public interface SistemaAutenticadoService {

    /**
     * Sistema de origem da requisição atual, lido do token autenticado (client_id, depois azp, depois sub).
     * Lança NAO_AUTENTICADO quando não há autenticação na thread atual.
     */
    public abstract String adquirirSistemaOrigem();

}
