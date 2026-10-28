package com.cauacosenza.barbearia.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Resposta de {@code POST /api/v1/appointments/public}, com HTTP 201.
 *
 * <p>E o objeto que o {@code BookingModal} empurra para a tela de confirmacao
 * via {@code navigate('/booking/confirmation', { state: { appointment } })}.
 *
 * <p><b>O {@code cancelToken} sai no corpo.</b> E a unica vez que ele aparece:
 * e com ele que se monta o link {@code /cancelar/{token}} enviado ao cliente
 * por WhatsApp. Nao e segredo de servidor, e sim a credencial do proprio
 * cliente sobre o proprio agendamento — quem tem o link cancela, sem login. E
 * por isso que ele e um UUID aleatorio e nao o id do agendamento: adivinhar o
 * id de alguem nao pode dar poder de cancelar a reserva dessa pessoa.
 *
 * <p><b>{@code totalDurationMinutes} e a soma BRUTA</b>, sem o teto de 50. Com
 * corte de 50 e pigmentacao de 10 o campo traz 60, enquanto o bloco realmente
 * ocupado na agenda e 50. Parece inconsistente e e o que o Node devolve: o
 * numero serve para a tela dizer ao cliente quanto tempo de servico ele
 * contratou, nao para descrever a ocupacao da agenda. Quem descreve a ocupacao
 * e {@code appointments.slot_duration_minutes}, que nao sai nesta resposta.
 *
 * @param notes pode ser nulo; o frontend nunca envia observacao hoje
 */
public record PublicAppointmentResponse(
		String id,
		String cancelToken,
		Instant scheduledAt,
		String status,
		String notes,
		List<ServiceSnapshotResponse> services,
		int totalAmountCents,
		int totalDurationMinutes,
		ProfessionalRefResponse professional) {

}
