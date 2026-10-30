package com.deroyque.activation.constants;

public final class SecurityConstants {

    private SecurityConstants() {

    }

    public static final String SCOPE_READ = "activation:read";
    public static final String SCOPE_WRITE = "activation:write";
    public static final String AUTHORITY_READ = "SCOPE_" + SCOPE_READ;
    public static final String AUTHORITY_WRITE = "SCOPE_" + SCOPE_WRITE;

}
