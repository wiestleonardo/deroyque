package com.deroyque.ativacao.constants;

public final class RotaConstants {

    private RotaConstants() {

    }

    public static final String API = "/api/v1";

    public static final class SolicitacaoAtivacao {

        private SolicitacaoAtivacao() {

        }

        public static final String BASE = API + "/requests";
        public static final String ADQUIRIR = "/{id}";
    }

    public static final class ProvedorAtivacao {

        private ProvedorAtivacao() {

        }

        public static final String ATIVAR = "/provider/v1/activate";
    }

    public static final class LocalDev {

        private LocalDev() {

        }

        public static final String BASE = "/dev";
        public static final String TOKEN = BASE + "/token";
    }

}
