package com.cauacosenza.barbearia.dto.response;

import java.util.Comparator;
import java.util.List;

import com.cauacosenza.barbearia.domain.entity.Barber;
import com.cauacosenza.barbearia.domain.entity.BarberSchedule;

/**
 * Um profissional em {@code GET /api/v1/professionals}.
 *
 * <p>Campos exatamente os que o {@code professionalService.listAll()} do Node
 * monta: {@code id}, {@code name} (decifrado), {@code bio}, {@code isWalkIn} e
 * a jornada semanal. Nao sai {@code isActive} — a lista so traz ativos — nem
 * {@code photoUrl}, que existe no tipo do frontend mas nao no banco; o front
 * ja o declara opcional e nunca o recebeu de backend nenhum.
 *
 * <p><b>O {@code isWalkIn} nao e decorativo.</b> Com ele verdadeiro, o
 * {@code BookingModal} bloqueia o botao de confirmar e mostra a orientacao de
 * ordem de chegada; o profissional de walk-in tambem nao tem nenhuma linha de
 * jornada, entao {@code availability} vem vazia e o calculo de horarios
 * devolve lista vazia. Os dois mecanismos dizem a mesma coisa por caminhos
 * diferentes.
 *
 * @param availability jornada semanal, <b>ordenada por {@code dayOfWeek}</b>
 */
public record ProfessionalResponse(
		String id,
		String name,
		String bio,
		boolean isWalkIn,
		List<AvailabilityResponse> availability) {

	public static ProfessionalResponse de(Barber barbeiro, List<BarberSchedule> jornadas) {
		return new ProfessionalResponse(
				barbeiro.getId(),
				barbeiro.getName(),
				barbeiro.getBio(),
				barbeiro.isWalkIn(),
				jornadas.stream()
						// O Node nao ordena (o include do Prisma sai na ordem do
						// heap do Postgres). O frontend so usa .some() e .find(),
						// entao a ordem nao o afeta -- mas uma resposta cuja
						// ordem muda entre chamadas torna o diff entre os dois
						// backends impossivel de ler.
						.sorted(Comparator.comparingInt(BarberSchedule::getDayOfWeek))
						.map(AvailabilityResponse::de)
						.toList());
	}

}
