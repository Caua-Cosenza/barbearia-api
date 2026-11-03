package com.cauacosenza.barbearia.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * O status pedido e valido, mas nao para o estado atual do agendamento.
 *
 * <h2>Por que 409 e nao 400 nem 422</h2>
 *
 * <p>O corpo da requisicao esta correto: {@code COMPLETED} e um valor que o
 * {@code StatusPermitidoParaAdmin} aceita, e um 422 diria que o payload esta
 * malformado quando ele nao esta. Um 400 diria "voce mandou algo errado", e a
 * acao que isso sugere — corrigir o formulario — e a acao errada.
 *
 * <p>409 diz o que de fato aconteceu: o <b>estado do recurso</b> no servidor
 * discorda do que a requisicao pressupoe. Para o painel, a reacao certa a isso
 * e recarregar a agenda e mostrar a realidade, nao pedir ao barbeiro que
 * reescreva nada. E o mesmo status que a
 * {@link SlotIndisponivelException} usa, pela mesma razao.
 */
public class TransicaoDeStatusInvalidaException extends BusinessException {

	private TransicaoDeStatusInvalidaException(String mensagem) {
		super(HttpStatus.CONFLICT, "Conflict", mensagem);
	}

	/**
	 * Concluir um atendimento que ainda nao aconteceu.
	 *
	 * <p>A mensagem oferece as duas saidas legitimas em vez de so recusar,
	 * porque quem clicou em "Concluir" por engano quase sempre queria uma
	 * delas: o cliente desmarcou ({@code CANCELLED}) ou nao apareceu
	 * ({@code NO_SHOW}).
	 */
	public static TransicaoDeStatusInvalidaException conclusaoAntesDoHorario() {
		return new TransicaoDeStatusInvalidaException(
				"Não é possível concluir um atendimento que ainda não começou. "
						+ "Se o cliente desmarcou, use CANCELLED; se não apareceu, NO_SHOW.");
	}

}
