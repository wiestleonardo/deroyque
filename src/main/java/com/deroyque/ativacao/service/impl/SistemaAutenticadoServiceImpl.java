package com.deroyque.ativacao.service.impl;

import com.deroyque.ativacao.enumerator.ErroEnum;
import com.deroyque.ativacao.exception.BusinessServiceException;
import com.deroyque.ativacao.service.SistemaAutenticadoService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SistemaAutenticadoServiceImpl implements SistemaAutenticadoService {

    private static final List<String> LISTA_CLAIM_SISTEMA_ORIGEM = List.of("client_id", "azp");

    @Override
    public String adquirirSistemaOrigem() {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {

            throw new BusinessServiceException(ErroEnum.NAO_AUTENTICADO);
        }

        if (authentication.getPrincipal() instanceof Jwt jwt) {

            return LISTA_CLAIM_SISTEMA_ORIGEM.stream()
                    .map(jwt::getClaimAsString)
                    .filter(claim -> claim != null && !claim.isBlank())
                    .findFirst()
                    .orElse(jwt.getSubject());
        }

        return authentication.getName();
    }

}
