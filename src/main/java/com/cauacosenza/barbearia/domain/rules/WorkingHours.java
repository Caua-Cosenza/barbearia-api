package com.cauacosenza.barbearia.domain.rules;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Jornada semanal recorrente de um profissional em um dia da semana.
 *
 * <p>Corresponde a uma linha de {@code professional_availability}. Nao e uma
 * entity JPA: a Fase 4 cria a entity e converte para este record.
 *
 * <p><b>Convencao de {@code dayOfWeek}</b> — 0 = domingo ... 6 = sabado, a
 * convencao do JavaScript ({@code Date.getDay()}), que e a que esta gravada no
 * banco. Isso <i>nao</i> e a convencao do {@link java.time.DayOfWeek} do Java,
 * onde segunda = 1 e domingo = 7. Use {@link #dayOfWeekOf(LocalDate)} para
 * converter e nao errar por um dia.
 *
 * <p>Horarios em hora local de Brasilia. O calculo nao sabe o que e fuso.
 *
 * @param dayOfWeek dia da semana na convencao do banco (0 = domingo)
 * @param startsAt abertura, inclusiva
 * @param endsAt fechamento, exclusivo: um slot precisa terminar ate aqui
 */
public record WorkingHours(int dayOfWeek, LocalTime startsAt, LocalTime endsAt) {

	public WorkingHours {
		if (dayOfWeek < 0 || dayOfWeek > 6) {
			throw new IllegalArgumentException(
					"dayOfWeek deve estar entre 0 (domingo) e 6 (sabado), recebido " + dayOfWeek);
		}
		Objects.requireNonNull(startsAt, "startsAt nao pode ser nulo");
		Objects.requireNonNull(endsAt, "endsAt nao pode ser nulo");
	}

	/**
	 * Converte uma data para a convencao de {@code dayOfWeek} usada no banco
	 * (0 = domingo ... 6 = sabado).
	 *
	 * <p>{@code DayOfWeek.getValue()} devolve 1 para segunda e 7 para domingo;
	 * o resto por 7 leva o domingo a 0 e mantem os outros dias.
	 */
	public static int dayOfWeekOf(LocalDate date) {
		Objects.requireNonNull(date, "date nao pode ser nulo");
		return date.getDayOfWeek().getValue() % 7;
	}

}
