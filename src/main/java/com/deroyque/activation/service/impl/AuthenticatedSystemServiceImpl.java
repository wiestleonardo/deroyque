package com.deroyque.activation.service.impl;

import com.deroyque.activation.enumerator.ErrorEnum;
import com.deroyque.activation.exception.BusinessServiceException;
import com.deroyque.activation.service.AuthenticatedSystemService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuthenticatedSystemServiceImpl implements AuthenticatedSystemService {

	private static final List<String> SOURCE_SYSTEM_CLAIM_LIST = List.of("client_id", "azp");

	@Override
	public String getSourceSystem() {

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		if (authentication == null || !authentication.isAuthenticated()) {

			throw new BusinessServiceException(ErrorEnum.UNAUTHORIZED);
		}

		if (authentication.getPrincipal() instanceof Jwt jwt) {

			return SOURCE_SYSTEM_CLAIM_LIST.stream().map(jwt::getClaimAsString).filter(claim -> claim != null && !claim.isBlank()).findFirst()
					.orElse(jwt.getSubject());
		}

		return authentication.getName();
	}

}
