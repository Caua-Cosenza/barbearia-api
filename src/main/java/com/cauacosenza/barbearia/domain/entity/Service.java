package com.cauacosenza.barbearia.domain.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Um item do catalogo — tabela {@code services}.
 *
 * <p>Nada aqui e cifrado: nome e descricao de servico sao publicos, aparecem
 * na tela de reserva.
 *
 * <p>{@code amountCents} e inteiro em centavos. Preco em {@code double} acaba
 * em 49.989999999999995, e dinheiro nao admite isso.
 *
 * <p>{@code durationMinutes} e a duracao <b>bruta</b> do servico. O teto de
 * {@code SlotCalculator.MAX_SLOT_MINUTES} nao vive aqui: um servico de 50
 * minutos combinado com um de 10 continua somando 60 nesta tabela, e e o
 * atendimento que ocupa 50.
 */
@Entity
@Table(name = "services")
@Getter
@Setter
@NoArgsConstructor
public class Service {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private String id;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "description")
	private String description;

	@Column(name = "amount_cents", nullable = false)
	private int amountCents;

	@Column(name = "duration_minutes", nullable = false)
	private int durationMinutes;

	@Column(name = "is_active", nullable = false)
	private boolean active = true;

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
