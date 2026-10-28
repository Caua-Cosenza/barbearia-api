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
 * Bloqueio pontual de agenda — tabela {@code blocked_slots}. Folga, almoco,
 * imprevisto.
 *
 * <p>Nome escolhido para nao colidir com o record {@code TimeBlock} de
 * {@code domain/rules}, pelo mesmo motivo de {@link BarberSchedule}.
 *
 * <p>{@code date} e TEXT {@code "YYYY-MM-DD"} e {@code startTime}/{@code endTime}
 * sao TEXT {@code "HH:MM"} — aqui em snake_case, ao contrario de
 * {@link BarberSchedule}. Ficam como {@code String} na entity: a coluna e
 * texto, e mapear como {@code LocalDate}/{@code LocalTime} faria o
 * {@code validate} exigir {@code date}/{@code time} no banco. Consultar por
 * dia e portanto comparacao de string, o que funciona porque
 * {@code "YYYY-MM-DD"} ordena lexicograficamente igual a cronologicamente.
 *
 * <p>Um bloqueio <b>nao</b> participa da formacao dos vaos livres: e um filtro
 * aplicado no fim do calculo. Abre um buraco na lista de horarios sem
 * realinhar os seguintes. Ver {@code TimeBlock} e {@code SlotCalculator}.
 */
@Entity
@Table(name = "blocked_slots")
@Getter
@Setter
@NoArgsConstructor
public class ScheduleBlock {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private String id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "professional_id", nullable = false)
	private Barber professional;

	/** TEXT {@code "YYYY-MM-DD"}, em data local de Brasilia. */
	@Column(name = "date", nullable = false)
	private String date;

	/** Inicio do bloqueio, inclusivo. TEXT {@code "HH:MM"}. */
	@Column(name = "start_time", nullable = false)
	private String startTime;

	/** Fim do bloqueio, exclusivo. TEXT {@code "HH:MM"}. */
	@Column(name = "end_time", nullable = false)
	private String endTime;

	@Column(name = "reason")
	private String reason;

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
