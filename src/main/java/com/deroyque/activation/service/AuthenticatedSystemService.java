package com.deroyque.activation.service;

public interface AuthenticatedSystemService {

    /**
     * Source system of the current request, taken from the authenticated token (client_id, then azp, then sub).
     * Throws UNAUTHORIZED when there is no authentication in the current thread.
     */
    public abstract String getSourceSystem();

}
