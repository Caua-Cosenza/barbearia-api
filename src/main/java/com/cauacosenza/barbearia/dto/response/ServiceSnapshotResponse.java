package com.cauacosenza.barbearia.dto.response;

import com.cauacosenza.barbearia.domain.entity.BookedService;

/**
 * Um servico <b>como foi comprado</b>, dentro da resposta de criacao de
 * agendamento.
 *
 * <p>Preco e duracao vem da linha de {@code appointment_services}, nao do
 * catalogo: sao o snapshot do momento da reserva. Um reajuste no dia seguinte
 * nao muda o que o cliente contratou, e a tela de confirmacao continua
 * mostrando o valor que ele viu.
 *
 * <p>{@code name}, ao contrario, vem do catalogo — {@code appointment_services}
 * nao guarda o nome. Renomear um servico reescreve retroativamente o nome
 * exibido em agendamentos antigos. E assim no Node tambem.
 */
public record ServiceSnapshotResponse(
		String serviceId,
		String name,
		int amountCents,
		int durationMinutes) {

	public static ServiceSnapshotResponse de(BookedService item) {
		return new ServiceSnapshotResponse(
				item.getService().getId(),
				item.getService().getName(),
				item.getAmountCents(),
				item.getDurationMinutes());
	}

}
