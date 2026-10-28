package com.cauacosenza.barbearia.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cauacosenza.barbearia.domain.entity.BarberSchedule;

/**
 * Consultas sobre {@code professional_availability}.
 *
 * <p>{@link #findByProfessionalIdAndDayOfWeek(String, int)} devolve
 * {@link Optional} porque existe indice unico em
 * {@code (professional_id, "dayOfWeek")}: um profissional tem no maximo uma
 * jornada por dia da semana. O {@link Optional#empty()} nao e ausencia de
 * dado, e a regra — sem jornada no dia, o calculo devolve lista vazia e o dia
 * funciona por ordem de chegada.
 *
 * <p>O {@code dayOfWeek} esperado segue a convencao do banco (0 = domingo),
 * nao a do {@link java.time.DayOfWeek}. Converta com
 * {@code WorkingHours.dayOfWeekOf}.
 */
public interface BarberScheduleRepository extends JpaRepository<BarberSchedule, String> {

	Optional<BarberSchedule> findByProfessionalIdAndDayOfWeek(String professionalId, int dayOfWeek);

	List<BarberSchedule> findByProfessionalIdOrderByDayOfWeekAsc(String professionalId);

}
