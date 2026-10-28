package com.cauacosenza.barbearia.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * Cancelamento recusado.
 *
 * <p><b>400, e nao 422.</b> O plano desta fase previa 422, mas o Node responde
 * 400 nos dois casos abaixo e o codigo e a fonte da verdade. Ver README.
 *
 * <ul>
 *   <li>{@link #prazoEncerrado()} — falta menos de uma hora para o
 *       atendimento. O limite e o mesmo {@code ONE_HOUR_MS} do Node, e e o
 *       mesmo que decide o {@code canCancel} da tela de cancelamento: se os
 *       dois discordassem, o botao apareceria habilitado para depois falhar.
 *   <li>{@link #statusNaoCancelavel()} — o agendamento ja saiu de
 *       {@code PENDING}/{@code CONFIRMED}. E o que responde ao segundo clique
 *       no mesmo link: o cancelamento nao e refeito, o {@code cancelled_at}
 *       original e preservado, e o cliente recebe um erro de negocio limpo em
 *       vez de 500.
 * </ul>
 */
public class PrazoCancelamentoException extends BusinessException {

	private PrazoCancelamentoException(String mensagem) {
		super(HttpStatus.BAD_REQUEST, "Bad Request", mensagem);
	}

	public static PrazoCancelamentoException prazoEncerrado() {
		return new PrazoCancelamentoException(
				"Prazo de cancelamento encerrado. Entre em contato com a barbearia.");
	}

	public static PrazoCancelamentoException statusNaoCancelavel() {
		return new PrazoCancelamentoException("Agendamento não pode ser cancelado");
	}

}
