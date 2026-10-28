package com.cauacosenza.barbearia.dto.response;

import java.time.Instant;

import com.cauacosenza.barbearia.domain.entity.ScheduleBlock;

/**
 * Um bloqueio de agenda, em {@code GET} e {@code POST
 * /api/v1/admin/blocked-slots}.
 *
 * <p>Os campos sao os da linha de {@code blocked_slots} como o Prisma a
 * devolve — inclusive {@code professionalId}, que o tipo {@code BlockedSlot}
 * do frontend nao declara e portanto ignora. Esta aqui porque o Node o envia
 * (o {@code create} e o {@code findMany} nao usam {@code select}, entao sai a
 * linha inteira), e omiti-lo seria uma divergencia gratuita.
 *
 * <p>{@code date}, {@code startTime} e {@code endTime} saem como TEXT, no
 * formato em que estao gravados: {@code "YYYY-MM-DD"} e {@code "HH:MM"}.
 */
public record BlockedSlotResponse(
		String id,
		String professionalId,
		String date,
		String startTime,
		String endTime,
		String reason,
		Instant createdAt) {

	public static BlockedSlotResponse de(ScheduleBlock bloqueio) {
		return new BlockedSlotResponse(
				bloqueio.getId(),
				bloqueio.getProfessional().getId(),
				bloqueio.getDate(),
				bloqueio.getStartTime(),
				bloqueio.getEndTime(),
				bloqueio.getReason(),
				bloqueio.getCreatedAt());
	}

}
