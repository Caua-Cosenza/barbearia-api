package com.cauacosenza.barbearia.domain.exception;

import org.springframework.http.HttpStatus;

/**
 * 409 — alguem ocupou este horario primeiro.
 *
 * <p>E para onde convergem <b>as duas faces da corrida perdida</b> que o teste
 * da Fase 4 revelou. O banco recusa o segundo agendamento de duas formas
 * diferentes, dependendo de escalonamento:
 *
 * <ul>
 *   <li>{@code DataIntegrityViolationException} — o vencedor ja tinha comitado
 *       quando o perdedor chegou ao indice;
 *   <li>{@code CannotAcquireLockException} (deadlock) — as duas transacoes
 *       chegaram ao indice ainda abertas, cada uma esperou pela outra, e o
 *       Postgres matou uma. Foi o caminho mais comum: 18 de 20 iteracoes.
 * </ul>
 *
 * <p>Para o cliente as duas significam a mesma frase. Traduzir so a primeira
 * deixaria o deadlock cair no catch-all como 500 "Erro inesperado" na maioria
 * das vezes em que duas pessoas disputam o mesmo horario — ou seja, justamente
 * quando a mensagem boa mais importa.
 */
public class SlotIndisponivelException extends BusinessException {

	private static final String MENSAGEM = "Este horário acabou de ser reservado. Escolha outro.";

	public SlotIndisponivelException() {
		super(HttpStatus.CONFLICT, "Conflict", MENSAGEM);
	}

	public SlotIndisponivelException(Throwable causa) {
		this();
		initCause(causa);
	}

}
