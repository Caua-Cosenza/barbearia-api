package com.cauacosenza.barbearia.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cauacosenza.barbearia.domain.entity.RefreshToken;

/**
 * Consultas sobre {@code refresh_tokens}.
 *
 * <p>Tudo aqui e por {@code tokenHash}, nunca pelo token: a coluna guarda o
 * SHA-256 em hexadecimal e o valor original nao existe no banco. Validar um
 * refresh token e hashear o que chegou e procurar pelo hash — busca exata,
 * amparada pelo indice unico.
 *
 * <p>Esta tabela e o que da <b>efeito real</b> ao logout e a rotacao. Um JWT
 * e valido ate expirar e nao tem como ser "desfeito"; e a ausencia da linha
 * aqui que faz um token revogado parar de servir antes dos sete dias.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	void deleteByTokenHash(String tokenHash);

	/** Usado ao trocar a senha ou encerrar todas as sessoes de uma conta. */
	void deleteByUserId(String userId);

}
