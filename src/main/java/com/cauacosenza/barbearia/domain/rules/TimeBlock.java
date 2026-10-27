package com.cauacosenza.barbearia.domain.rules;

import java.time.LocalTime;
import java.util.Objects;

/**
 * Bloqueio pontual de agenda criado pelo profissional — folga, almoco,
 * imprevisto. Corresponde a uma linha de {@code blocked_slots}.
 *
 * <p>Um bloqueio <b>nao</b> participa da formacao dos vaos: ele e aplicado
 * depois, removendo os horarios que colidem com ele. A consequencia pratica e
 * que um bloqueio abre um buraco na lista, mas <i>nao realinha</i> os horarios
 * seguintes. E assim que o Node se comporta e e o que o frontend espera.
 *
 * @param startsAt inicio do bloqueio, inclusivo
 * @param endsAt fim do bloqueio, exclusivo
 */
public record TimeBlock(LocalTime startsAt, LocalTime endsAt) {

	public TimeBlock {
		Objects.requireNonNull(startsAt, "startsAt nao pode ser nulo");
		Objects.requireNonNull(endsAt, "endsAt nao pode ser nulo");
		// O Node garante startTime < endTime na borda (Zod .refine no
		// blockedSlotsController), entao um bloqueio invertido nunca chega aqui.
		if (!startsAt.isBefore(endsAt)) {
			throw new IllegalArgumentException("startsAt deve ser anterior a endsAt");
		}
	}

}
