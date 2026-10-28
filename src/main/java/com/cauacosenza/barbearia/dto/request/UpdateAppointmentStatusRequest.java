package com.cauacosenza.barbearia.dto.request;

import jakarta.validation.constraints.NotNull;

import com.cauacosenza.barbearia.domain.enums.AppointmentStatus;
import com.cauacosenza.barbearia.validation.StatusPermitidoParaAdmin;

/**
 * Corpo de {@code PUT /api/v1/admin/appointments/{id}/status}.
 *
 * <p><b>{@code PENDING} nao e aceito, de proposito.</b> O {@code UpdateStatusSchema}
 * do Node lista apenas {@code CONFIRMED}, {@code CANCELLED}, {@code COMPLETED}
 * e {@code NO_SHOW} — o admin nao devolve um agendamento ao estado inicial. Um
 * atendimento ja finalizado que voltasse a PENDING reocuparia a agenda e
 * reapareceria como pendente no painel do dia seguinte.
 *
 * <p>A validacao esta em {@link #status()} e nao no enum do Jackson porque um
 * valor fora da lista precisa virar 422 no envelope, nao erro de
 * desserializacao.
 */
public record UpdateAppointmentStatusRequest(

		@NotNull(message = ValidationPatterns.OBRIGATORIO)
		@StatusPermitidoParaAdmin
		AppointmentStatus status) {

}
