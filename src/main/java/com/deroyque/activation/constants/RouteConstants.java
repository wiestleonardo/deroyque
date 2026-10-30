package com.deroyque.activation.constants;

public final class RouteConstants {

    private RouteConstants() {

    }

    public static final String API = "/api/v1";

    public static final class ActivationRequest {

        private ActivationRequest() {

        }

        public static final String BASE = API + "/requests";
        public static final String GET_BY_ID = "/{id}";
    }

    public static final class ActivationProvider {

        private ActivationProvider() {

        }

        public static final String BASE = "/provider";
        public static final String ACTIVATE = BASE + "/v1/activate";
    }

    public static final class LocalDev {

        private LocalDev() {

        }

        public static final String BASE = "/dev";
        public static final String TOKEN = BASE + "/token";
    }

}
