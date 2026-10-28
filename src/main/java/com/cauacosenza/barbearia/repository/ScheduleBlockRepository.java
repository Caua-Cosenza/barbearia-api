package com.cauacosenza.barbearia.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cauacosenza.barbearia.domain.entity.ScheduleBlock;

/**
 * Consultas sobre {@code blocked_slots}.
 *
 * <p>{@code date} e TEXT {@code "YYYY-MM-DD"}, portanto a comparacao e de
 * string e o chamador precisa formatar a data assim — use
 * {@code SlotQueryMapper.toDateKey}. O formato ISO salva a situacao: ordem
 * lexicografica e ordem cronologica coincidem, entao intervalo por
 * {@code between} tambem funcionaria.
 */
public interface ScheduleBlockRepository extends JpaRepository<ScheduleBlock, String> {

	List<ScheduleBlock> findByProfessionalIdAndDateOrderByStartTimeAsc(String professionalId, String date);

}
