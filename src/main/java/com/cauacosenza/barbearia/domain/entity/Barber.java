package com.cauacosenza.barbearia.domain.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.cauacosenza.barbearia.security.EncryptedStringConverter;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Um profissional da barbearia — tabela {@code professionals}.
 *
 * <p>O nome da classe e {@code Barber} e nao {@code Professional} por uma
 * razao pratica: {@code Professional} convive mal com
 * {@code professional_availability}, que neste codigo e
 * {@link BarberSchedule}. Manter o par Barber/BarberSchedule deixa claro quem
 * e dono de quem sem olhar o mapeamento.
 *
 * <p>{@code isWalkIn} marca o profissional ficticio de atendimento por ordem
 * de chegada. Ele nao tem linha em {@code professional_availability}, e e
 * exatamente essa ausencia que faz o calculo de horarios devolver lista vazia
 * para ele — nao ha caso especial no codigo.
 */
@Entity
@Table(name = "professionals")
@Getter
@Setter
@NoArgsConstructor
public class Barber {

	/** TEXT no banco, nao {@code uuid}: o Prisma gera o id na aplicacao. */
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private String id;

	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "name_encrypted", nullable = false)
	private String name;

	@Column(name = "bio")
	private String bio;

	@Column(name = "is_active", nullable = false)
	private boolean active = true;

	@Column(name = "is_walk_in", nullable = false)
	private boolean walkIn = false;

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
