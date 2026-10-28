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
 * A jornada semanal recorrente de um profissional em um dia da semana —
 * tabela {@code professional_availability}.
 *
 * <p>O nome e {@code BarberSchedule} e nao {@code WorkingHours} de proposito:
 * {@code WorkingHours} ja existe em {@code domain/rules} e e outra coisa — a
 * projecao de entrada do calculo, pura, sem JPA. Entity e record nunca
 * compartilham nome neste projeto, para que o mapper possa importar os dois
 * lados sem nome qualificado.
 *
 * <p><b>As aspas nos nomes de coluna nao sao enfeite.</b> Esta tabela e a
 * unica do schema em camelCase ({@code "dayOfWeek"}, {@code "startTime"},
 * {@code "endTime"}), porque esses campos nunca foram mapeados no
 * {@code schema.prisma}. Sem as aspas escapadas o Hibernate gera
 * {@code dayofweek} em minusculas e o Postgres nao encontra a coluna —
 * {@code ddl-auto: validate} falha no boot, o que ja e o melhor dos finais
 * possiveis. Comparar com {@link ScheduleBlock}, que tem
 * {@code start_time}/{@code end_time} em snake_case: a inconsistencia esta no
 * banco de producao e nao pode ser corrigida aqui.
 *
 * <p><b>{@code dayOfWeek} segue a convencao do JavaScript</b>: 0 = domingo ...
 * 6 = sabado, que e o que {@code Date.getDay()} do Node gravou. Nao e a
 * convencao do {@link java.time.DayOfWeek}, onde segunda = 1 e domingo = 7.
 * A conversao vive em {@code WorkingHours.dayOfWeekOf} e o mapper e o unico
 * lugar que a usa.
 *
 * <p><b>{@code startTime} e {@code endTime} sao TEXT</b> no formato
 * {@code "HH:MM"}, nao {@code TIME}. Ficam como {@code String} na entity e o
 * mapper converte para {@link java.time.LocalTime}: mapear TEXT como
 * {@code LocalTime} faria o Hibernate pedir uma coluna {@code time} e o
 * {@code validate} reprovaria.
 *
 * <p>Ausencia de linha e uma regra, nao um dado faltando: sexta e sabado do
 * Jhonatan funcionam por ordem de chegada justamente porque nao ha jornada
 * cadastrada nesses dias.
 */
@Entity
@Table(name = "professional_availability")
@Getter
@Setter
@NoArgsConstructor
public class BarberSchedule {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private String id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "professional_id", nullable = false)
	private Barber professional;

	/** 0 = domingo ... 6 = sabado. Coluna em camelCase: aspas obrigatorias. */
	@Column(name = "\"dayOfWeek\"", nullable = false)
	private int dayOfWeek;

	/** Abertura, TEXT {@code "HH:MM"}. */
	@Column(name = "\"startTime\"", nullable = false)
	private String startTime;

	/** Fechamento, TEXT {@code "HH:MM"}. */
	@Column(name = "\"endTime\"", nullable = false)
	private String endTime;

}
