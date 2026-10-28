package com.cauacosenza.barbearia.domain.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Um refresh token emitido para uma conta — tabela {@code refresh_tokens}.
 *
 * <p>A coluna guarda o <b>hash</b> do token, nunca o token. Um vazamento do
 * banco nao entrega sessao a ninguem, e a consequencia pratica e que o valor
 * original nao pode ser reconstruido: validar um refresh token e hashear o que
 * chegou e procurar pelo hash. Por isso o hash <i>nao</i> passa pelo
 * {@code EncryptedStringConverter} — precisa ser procuravel, e cifra com IV
 * aleatorio nao e.
 *
 * <p>A FK e {@code ON DELETE CASCADE}: token sem conta nao significa nada.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private String id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private AdminUser user;

	/** Indice unico. */
	@Column(name = "token_hash", nullable = false)
	private String tokenHash;

	@JdbcTypeCode(SqlTypes.TIMESTAMP)
	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@JdbcTypeCode(SqlTypes.TIMESTAMP)
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@PrePersist
	void aoInserir() {
		if (this.createdAt == null) {
			this.createdAt = Instant.now();
		}
	}

}
