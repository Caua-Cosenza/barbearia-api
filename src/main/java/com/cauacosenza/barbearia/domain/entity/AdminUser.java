package com.cauacosenza.barbearia.domain.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.cauacosenza.barbearia.domain.enums.Role;
import com.cauacosenza.barbearia.security.EncryptedStringConverter;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Uma conta com login — tabela {@code users}.
 *
 * <p>O nome {@code AdminUser} e herdado do uso real: na pratica as contas
 * existentes sao do dono e da recepcao. O {@link Role} na coluna admite
 * {@code CLIENT}, mas a reserva publica nao cria conta — ela grava a PII do
 * visitante nas colunas {@code guest_*_encrypted} de {@link Appointment}.
 *
 * <p>{@code email} <b>nao</b> e cifrado, e isso e deliberado: e a credencial
 * de login, precisa de indice unico e de busca exata. {@code WHERE} em coluna
 * cifrada nao funciona (ver {@link EncryptedStringConverter}), portanto cifrar
 * o e-mail quebraria o login. Nome e telefone, que nao sao procurados, vao
 * cifrados.
 *
 * <p>{@code passwordHash} e bcrypt, nunca a senha.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class AdminUser {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private String id;

	@Column(name = "email", nullable = false)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "name_encrypted", nullable = false)
	private String name;

	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "phone_encrypted", nullable = false)
	private String phone;

	/** Tipo enumerado nativo do Postgres, nao varchar — ver {@link Appointment}. */
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "role", nullable = false)
	private Role role = Role.CLIENT;

	@JdbcTypeCode(SqlTypes.TIMESTAMP)
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@JdbcTypeCode(SqlTypes.TIMESTAMP)
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@PrePersist
	void aoInserir() {
		Instant agora = Instant.now();
		if (this.createdAt == null) {
			this.createdAt = agora;
		}
		this.updatedAt = agora;
	}

	@PreUpdate
	void aoAtualizar() {
		this.updatedAt = Instant.now();
	}

}
