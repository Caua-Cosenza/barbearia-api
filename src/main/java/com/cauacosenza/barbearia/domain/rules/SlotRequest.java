package com.cauacosenza.barbearia.domain.rules;

import java.time.LocalDate;
import java.util.Objects;

/**
 * O que o cliente pediu: a data e quanto tempo o atendimento vai levar.
 *
 * <p>{@code requestedDurationMinutes} e a duracao <b>bruta</b> — a soma das
 * duracoes dos servicos escolhidos, que o frontend manda em
 * {@code totalDurationMinutes}. O teto de
 * {@link SlotCalculator#MAX_SLOT_MINUTES} e aplicado pelo
 * {@link SlotCalculator}.
 *
 * <p>A duracao precisa ser positiva. No Node isso e garantido na borda pelo
 * Zod ({@code z.number().int().positive()}), que devolve 400 antes de o
 * calculo rodar. Nao e uma preciosidade: o laco que gera os horarios avanca
 * pela duracao, entao zero seria laco infinito.
 *
 * @param date dia para o qual se quer os horarios, em data local de Brasilia
 * @param requestedDurationMinutes soma bruta das duracoes dos servicos
 */
public record SlotRequest(LocalDate date, int requestedDurationMinutes) {

	public SlotRequest {
		Objects.requireNonNull(date, "date nao pode ser nulo");
		if (requestedDurationMinutes <= 0) {
			throw new IllegalArgumentException(
					"requestedDurationMinutes deve ser positivo, recebido " + requestedDurationMinutes);
		}
	}

}
