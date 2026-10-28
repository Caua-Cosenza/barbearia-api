package com.cauacosenza.barbearia.domain.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Uma inscricao Web Push — tabela {@code push_subscriptions}.
 *
 * <p>{@code endpoint} e a URL que o navegador entregou (indice unico, e por
 * ela que uma reinscricao do mesmo dispositivo nao duplica a linha);
 * {@code p256dh} e {@code auth} sao as chaves publicas do navegador usadas
 * para cifrar o payload da notificacao.
 *
 * <p>Nao ha FK para {@code users}: a barbearia notifica o balcao, nao o
 * cliente, e a inscricao e do dispositivo.
 */
@Entity
@Table(name = "push_subscriptions")
@Getter
@Setter
@NoArgsConstructor
public class PushSubscription {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private String id;

	@Column(name = "endpoint", nullable = false)
	private String endpoint;

	@Column(name = "p256dh", nullable = false)
	private String p256dh;

	@Column(name = "auth", nullable = false)
	private String auth;

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
