package com.cauacosenza.barbearia.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Resposta de {@code GET /api/v1/cancel/{token}} — o que a tela
 * {@code /cancelar/:token} mostra antes de o cliente confirmar.
 *
 * <p><b>{@code canCancel} e a mesma regra que o POST aplica</b>, e tem que
 * ser: a tela usa este booleano para habilitar ou nao o botao. Se ela dissesse
 * {@code true} e o POST recusasse, o cliente clicaria e tomaria um erro — que
 * e justamente o caso em que ele ja esta apressado. As duas pontas decidem por
 * {@code status cancelavel && falta mais de uma hora}.
 *
 * <p>O {@code guestName} sai decifrado. E PII, e sai porque o dono do link e o
 * proprio cliente — o token e a credencial. Quando o agendamento nao tem nome
 * de visitante (caso de conta logada, que o site nao cria hoje) o Node devolve
 * string vazia, nao nulo, e o mesmo e feito aqui para a tela nao imprimir
 * "null".
 *
 * @param services nome, preco e duracao de cada item, do snapshot
 * @param totalDurationMinutes soma <b>bruta</b>, sem o teto de 50, como em
 *     {@link PublicAppointmentResponse}
 */
public record CancelPageResponse(
		String id,
		String guestName,
		Instant scheduledAt,
		String status,
		List<ItemResponse> services,
		int totalAmountCents,
		int totalDurationMinutes,
		boolean canCancel) {

	/**
	 * Um item do agendamento nesta tela.
	 *
	 * <p>Nao reaproveita {@link ServiceSnapshotResponse} porque o Node nao
	 * manda {@code serviceId} aqui, e o tipo {@code CancelPageData} do frontend
	 * tambem nao o declara. Acrescentar o campo seria inofensivo para a tela e
	 * uma divergencia gratuita no diff.
	 */
	public record ItemResponse(String name, int amountCents, int durationMinutes) {

	}

}
