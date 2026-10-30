package com.deroyque.ativacao.constants;

public final class SegurancaConstants {

    private SegurancaConstants() {

    }

    public static final String ESCOPO_LEITURA = "activation:read";
    public static final String ESCOPO_ESCRITA = "activation:write";
    public static final String AUTORIDADE_LEITURA = "SCOPE_" + ESCOPO_LEITURA;
    public static final String AUTORIDADE_ESCRITA = "SCOPE_" + ESCOPO_ESCRITA;

}
