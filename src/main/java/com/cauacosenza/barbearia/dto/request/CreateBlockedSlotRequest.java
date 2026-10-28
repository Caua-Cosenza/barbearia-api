package com.cauacosenza.barbearia.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpo de {@code POST /api/v1/admin/blocked-slots} — o "reservar horario" do
 * painel, usado para folga, almoco e imprevisto.
 *
 * <p>Traducao do {@code createBlockedSlotSchema} (Zod) do Node, incluindo o
 * {@code .refine} que exige {@code startTime < endTime} — comparado como
 * <b>string</b>, que e o que o Node faz e que funciona porque {@code "HH:MM"}
 * de largura fixa ordena lexicograficamente igual a cronologicamente.
 *
 * <p>Os horarios continuam TEXT {@code "HH:MM"} e a data TEXT
 * {@code "YYYY-MM-DD"} porque e isso que as colunas de {@code blocked_slots}
 * guardam. Ver {@code ScheduleBlock}.
 */
public record CreateBlockedSlotRequest(

		@NotNull(message = ValidationPatterns.OBRIGATORIO)
		@Pattern(regexp = ValidationPatterns.UUID, message = "Invalid uuid")
		String professionalId,

		@NotNull(message = ValidationPatterns.OBRIGATORIO)
		@Pattern(regexp = ValidationPatterns.DATA_ISO, message = "Date must be YYYY-MM-DD")
		String date,

		@NotNull(message = ValidationPatterns.OBRIGATORIO)
		@Pattern(regexp = ValidationPatterns.HORA_HHMM, message = "startTime must be HH:MM")
		String startTime,

		@NotNull(message = ValidationPatterns.OBRIGATORIO)
		@Pattern(regexp = ValidationPatterns.HORA_HHMM, message = "endTime must be HH:MM")
		String endTime,

		@Size(max = 200)
		String reason) {

	/**
	 * O {@code .refine} do Zod, que reporta o erro no campo
	 * {@code startTime} — por isso o nome deste metodo, que e o que o Jakarta
	 * Validation usa para batizar a propriedade na mensagem.
	 *
	 * <p>Devolve {@code true} quando algum dos dois e nulo ou malformado:
	 * nesse caso quem reclama e o {@code @Pattern}, e comparar strings tortas
	 * produziria uma segunda mensagem sobre o mesmo erro.
	 */
	@jakarta.validation.constraints.AssertTrue(message = "startTime must be earlier than endTime")
	public boolean isStartTime() {
		if (this.startTime == null || this.endTime == null) {
			return true;
		}
		return this.startTime.compareTo(this.endTime) < 0;
	}

}
