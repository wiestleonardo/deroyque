package com.deroyque.activation.application.provider;

import com.deroyque.activation.domain.ActivationRequest;

/** Porta para o serviço externo de ativação. A implementação HTTP fica em infrastructure. */
public interface ActivationProvider {

    /**
     * Deve enviar o id da solicitação como chave de idempotência: em caso de timeout não sabemos se
     * o provedor ativou, e a nova tentativa não pode gerar uma segunda ativação.
     * Nunca lança exceção por falha remota; devolve o resultado classificado.
     */
    ProviderOutcome activate(ActivationRequest request);
}
