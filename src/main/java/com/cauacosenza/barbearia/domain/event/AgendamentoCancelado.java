package com.cauacosenza.barbearia.domain.event;

import java.time.Instant;
import java.util.List;

/**
 * Um cliente cancelou pelo link que recebeu.
 *
 * <p>Retrato imutavel, pelos mesmos motivos de {@link AgendamentoCriado}: o
 * ouvinte roda apos o commit, fora da sessao do Hibernate.
 *
 * <p>Nao ha telefone do cliente nem valor: a mensagem de cancelamento vai
 * <b>para o barbeiro</b> e nao traz nenhum dos dois. O atendimento nao vai
 * acontecer e nao ha ninguem para ligar.
 */
public record AgendamentoCancelado(
		String appointmentId,
		String guestName,
		Instant scheduledAt,
		List<String> servicos) {

	public AgendamentoCancelado {
		servicos = List.copyOf(servicos);
	}

}
