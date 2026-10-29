package com.deroyque.ativacao.constants;

public final class MotivoFalhaConstants {

    private MotivoFalhaConstants() {

    }

    public static final String TEMPO_LIMITE_PROVEDOR = "PROVIDER_TIMEOUT";
    public static final String PROVEDOR_INACESSIVEL = "PROVIDER_UNREACHABLE";
    public static final String PREFIXO_ERRO_HTTP = "PROVIDER_HTTP_";
    public static final String PREFIXO_RECUSA = "PROVIDER_REJECTED_";
    public static final String PREFIXO_CREDENCIAL = "PROVIDER_AUTH_";
    public static final String PREFIXO_TENTATIVAS_ESGOTADAS = "MAX_ATTEMPTS_EXCEEDED:";
    public static final String RESERVA_EXPIRADA = "PROCESSING_LEASE_EXPIRED";

}
