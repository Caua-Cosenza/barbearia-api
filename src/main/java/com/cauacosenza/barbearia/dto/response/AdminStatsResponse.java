package com.cauacosenza.barbearia.dto.response;

/**
 * Corpo de {@code GET /api/v1/admin/stats} — os quatro contadores do topo do
 * painel.
 *
 * <p>Os tres primeiros contam apenas {@code PENDING}, {@code CONFIRMED} e
 * {@code COMPLETED}: cancelado e no-show nao sao atendimento. Ja
 * {@code noShowCount} e <b>acumulado desde sempre</b>, sem recorte de periodo
 * — divergencia conhecida do Node, reproduzida de proposito e registrada no
 * README.
 *
 * <p>A semana vai de <b>terca a sabado</b>, nao domingo a sabado: e a semana
 * de trabalho da barbearia. Ver {@code AdminService}.
 */
public record AdminStatsResponse(
		long todayCount,
		long weekCount,
		long monthCount,
		long noShowCount) {

}
