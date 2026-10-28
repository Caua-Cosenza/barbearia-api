package com.cauacosenza.barbearia.dto.response;

import com.cauacosenza.barbearia.domain.entity.Service;

/**
 * Um item do catalogo em {@code GET /api/v1/services}.
 *
 * <p>Campos exatamente os do {@code select} do {@code serviceRepository.findAll()}
 * do Node: nem {@code isActive} (a lista so traz ativos, entao seria sempre
 * {@code true}), nem os timestamps.
 *
 * <p><b>{@code amountCents} e devolvido.</b> Ha um comentario no
 * {@code serviceService.listAll()} do Node dizendo que o preco nao sai nesta
 * rota — o comentario esta errado, o {@code select} do repositorio inclui
 * {@code amountCents}, e o frontend depende disso: {@code ServicesList.tsx} e
 * {@code BookingModal.tsx} formatam {@code service.amountCents / 100} para
 * mostrar o preco na home e no modal. Remover o campo por causa do comentario
 * faria todos os precos virarem "Consultar".
 *
 * <p>O {@code price} que aparece no tipo {@code Service} do frontend e um
 * fallback opcional de uma versao antiga da API; nenhum backend o envia hoje,
 * e o front ja trata a ausencia.
 */
public record ServiceResponse(
		String id,
		String name,
		String description,
		int amountCents,
		int durationMinutes) {

	public static ServiceResponse de(Service servico) {
		return new ServiceResponse(
				servico.getId(),
				servico.getName(),
				servico.getDescription(),
				servico.getAmountCents(),
				servico.getDurationMinutes());
	}

}
