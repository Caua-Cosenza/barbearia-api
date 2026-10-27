package com.cauacosenza.barbearia.domain.rules;

import java.time.LocalTime;
import java.util.Objects;

/**
 * Um agendamento que ja ocupa a agenda.
 *
 * <p>{@code durationMinutes} e a duracao <b>bruta</b>: a soma das duracoes dos
 * servicos do agendamento, sem teto. O teto de
 * {@link SlotCalculator#MAX_SLOT_MINUTES} e aplicado pelo
 * {@link SlotCalculator}, nunca aqui — guardar o valor bruto e o que permite
 * ao teste provar que o teto esta sendo aplicado na leitura.
 *
 * <p>A Fase 4 e responsavel por decidir <i>quais</i> agendamentos chegam aqui.
 * No Node, o repositorio filtra {@code status in (PENDING, CONFIRMED)}: um
 * agendamento cancelado, concluido ou no-show <b>nao</b> ocupa horario.
 *
 * @param startsAt inicio, em hora local de Brasilia
 * @param durationMinutes soma bruta das duracoes dos servicos, em minutos
 */
public record BookedSlot(LocalTime startsAt, int durationMinutes) {

	public BookedSlot {
		Objects.requireNonNull(startsAt, "startsAt nao pode ser nulo");
		// Zero e aceito: reproduz a aritmetica do Node, onde um agendamento sem
		// servico nenhum vira um bloco de largura zero. Negativo nao existe no
		// banco (coluna NOT NULL alimentada por duracao de servico) e seria um
		// bloco que termina antes de comecar, entao e recusado.
		if (durationMinutes < 0) {
			throw new IllegalArgumentException(
					"durationMinutes nao pode ser negativo, recebido " + durationMinutes);
		}
	}

}
