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
	 * <p>A mensagem oferece a saida legitima em vez de so recusar, porque quem
	 * clicou em "Concluir" por engano queria aquilo: o cliente desmarcou.
	 *
	 * <p><b>Ela sugeria tambem {@code NO_SHOW}, e isso estava errado.</b>
	 * Falta antes da hora passou a ser recusada pelo mesmo motivo — ver
	 * {@link #faltaAntesDoHorario()} —, entao a sugestao mandava o barbeiro
	 * para uma segunda recusa. Para atendimento futuro sobra uma saida so, e e
	 * {@code CANCELLED}.
	 */
	public static TransicaoDeStatusInvalidaException conclusaoAntesDoHorario() {
		return new TransicaoDeStatusInvalidaException(
				"Não é possível concluir um atendimento que ainda não começou. "
						+ "Se o cliente desmarcou, use CANCELLED.");
	}

	/**
	 * Registrar falta num atendimento que ainda nao aconteceu.
	 *
	 * <p>Mesmo buraco pela mesma porta: {@code NO_SHOW} tambem esta fora de
	 * {@code OCUPAM_AGENDA}, entao marcar falta antes da hora liberava um
	 * horario vendido. A diferenca e que aqui sobra uma saida so —
	 * {@code CANCELLED} —, porque "nao apareceu" e exatamente o que nao se
	 * pode afirmar de quem ainda nao tinha hora de aparecer.
	 */
	public static TransicaoDeStatusInvalidaException faltaAntesDoHorario() {
		return new TransicaoDeStatusInvalidaException(
				"Não é possível registrar falta em um atendimento que ainda não começou. "
						+ "Se o cliente desmarcou, use CANCELLED.");
	}

}
