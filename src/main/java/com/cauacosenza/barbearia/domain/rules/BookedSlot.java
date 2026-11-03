package com.cauacosenza.barbearia.domain.rules;

import java.time.LocalTime;
import java.util.Objects;

/**
 * Um intervalo ja ocupado na agenda de um dia.
 *
 * @param startsAt inicio, em hora local de Brasilia
 * @param durationMinutes duracao do bloco, em minutos
 * @param duracaoMedida se a duracao e o tempo REAL do atendimento, e nao a
 *     prevista. Muda o tratamento no {@link SlotCalculator}: a duracao
 *     prevista e capada em {@code MAX_SLOT_MINUTES} na leitura — a regra que
 *     impede o bug das 10:00 —, enquanto a medida entra como esta. Capar a
 *     medida apagaria justamente o que ela serve para registrar: um
 *     atendimento que levou 65 minutos ocupou 65, e o vao seguinte comeca
 *     depois disso.
 */
public record BookedSlot(LocalTime startsAt, int durationMinutes, boolean duracaoMedida) {

	/**
	 * O bloco de um agendamento cuja duracao e a <b>prevista</b>.
	 *
	 * <p>Duracao prevista e capada em {@link SlotCalculator#MAX_SLOT_MINUTES}
	 * na leitura; ver {@code duracaoMedida}.
	 */
	public BookedSlot(LocalTime startsAt, int durationMinutes) {
		this(startsAt, durationMinutes, false);
	}

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
