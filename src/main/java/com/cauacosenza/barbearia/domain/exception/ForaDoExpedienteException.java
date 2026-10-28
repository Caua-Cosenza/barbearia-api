package com.cauacosenza.barbearia.domain.exception;

import java.time.LocalTime;

import org.springframework.http.HttpStatus;

/**
 * 400 — o horario pedido nao cabe na jornada do dia.
 *
 * <p>Duas situacoes distintas, e a segunda e uma <b>correcao deliberada</b> em
 * relacao ao Node:
 *
 * <ul>
 *   <li>{@link #inicioForaDaJornada()} — o atendimento comeca antes da
 *       abertura, ou no fechamento, ou depois dele. Era a unica checagem que o
 *       Node fazia.
 *   <li>{@link #naoCabeNoExpediente(LocalTime, LocalTime)} — o atendimento
 *       comeca dentro do expediente mas <b>termina depois do fechamento</b>. O
 *       Node aceitava: dava para criar pela API um agendamento as 19:20 com 50
 *       minutos, terminando 20:10, quarenta minutos depois de a barbearia
 *       fechar. A leitura ja exigia que o slot coubesse inteiro — o laco do
 *       {@code SlotCalculator} avanca enquanto
 *       {@code inicio + duracao <= fim} — entao esse horario nunca aparecia na
 *       tela; so era alcancavel chamando a API direto.
 * </ul>
 *
 * <p>A mensagem do primeiro caso e identica a do Node. A do segundo e nova, e
 * cita os dois horarios porque sem eles o cliente nao tem como entender por
 * que um horario "dentro do expediente" foi recusado.
 */
public class ForaDoExpedienteException extends BusinessException {

	private ForaDoExpedienteException(String mensagem) {
		super(HttpStatus.BAD_REQUEST, "Bad Request", mensagem);
	}

	public static ForaDoExpedienteException inicioForaDaJornada() {
		return new ForaDoExpedienteException("Horário fora do expediente");
	}

	public static ForaDoExpedienteException naoCabeNoExpediente(LocalTime fimDoAtendimento, LocalTime fechamento) {
		return new ForaDoExpedienteException(
				"O atendimento terminaria às " + fimDoAtendimento + " e o expediente encerra às "
						+ fechamento + ". Escolha um horário mais cedo.");
	}

}
