package com.cauacosenza.barbearia.dto.response;

import com.cauacosenza.barbearia.domain.entity.BarberSchedule;

/**
 * Uma linha da jornada semanal, dentro de {@link ProfessionalResponse}.
 *
 * <p>{@code dayOfWeek} sai na convencao do banco (0 = domingo ... 6 = sabado),
 * que e a mesma do {@code Date.getDay()} do JavaScript — e por isso que o
 * frontend pode comparar direto com {@code new Date(...).getDay()} em
 * {@code isCalDayDisabled}. Converter para a convencao do
 * {@link java.time.DayOfWeek} aqui desabilitaria os dias errados no calendario.
 *
 * <p>{@code startTime} e {@code endTime} saem como TEXT {@code "HH:MM"}, como
 * estao na coluna.
 */
public record AvailabilityResponse(
		int dayOfWeek,
		String startTime,
		String endTime) {

	public static AvailabilityResponse de(BarberSchedule jornada) {
		return new AvailabilityResponse(
				jornada.getDayOfWeek(),
				jornada.getStartTime(),
				jornada.getEndTime());
	}

}
