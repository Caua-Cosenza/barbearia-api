package com.cauacosenza.barbearia.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Um servico dentro de um agendamento — tabela {@code appointment_services}.
 *
 * <p>Um atendimento combina varios servicos (corte + barba + pigmentacao), e
 * esta tabela e o que permite isso. {@code amountCents} e
 * {@code durationMinutes} sao <b>snapshot</b> do {@link Service} no momento da
 * reserva, nao referencia viva: o catalogo muda de preco e de duracao, e um
 * agendamento de tres meses atras precisa continuar contando o que contava.
 *
 * <p>A soma de {@code durationMinutes} dos itens, com teto de 50, e a duracao
 * efetiva do atendimento. Ela e materializada em
 * {@link Appointment#getSlotDurationMinutes()} — ver o javadoc de
 * {@link Appointment} para o porque.
 */
@Entity
@Table(name = "appointment_services")
@Getter
@Setter
@NoArgsConstructor
public class BookedService {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private String id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "appointment_id", nullable = false)
	private Appointment appointment;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "service_id", nullable = false)
	private Service service;

	@Column(name = "amount_cents", nullable = false)
	private int amountCents;

	@Column(name = "duration_minutes", nullable = false)
	private int durationMinutes;

}
