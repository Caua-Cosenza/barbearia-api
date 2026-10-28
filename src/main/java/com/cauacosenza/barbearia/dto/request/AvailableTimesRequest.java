package com.cauacosenza.barbearia.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * Corpo de {@code POST /api/v1/available-times}.
 *
 * <p>Traducao do {@code AvailableTimesSchema} (Zod) do Node, regra a regra.
 *
 * @param professionalId id do profissional, UUID
 * @param serviceId id do servico de referencia, UUID. Quando o cliente escolhe
 *     varios servicos o frontend manda aqui o <b>primeiro</b> deles e a soma
 *     em {@code totalDurationMinutes} — este campo serve so para provar que o
 *     servico existe e para dar a duracao padrao quando a soma nao vem.
 * @param date dia desejado em {@code YYYY-MM-DD}, data local de Brasilia.
 *     Nao e {@code LocalDate} no record de proposito: um valor malformado
 *     falharia na desserializacao do Jackson, antes da Bean Validation, e a
 *     resposta sairia como JSON invalido em vez da mensagem de validacao que o
 *     Node devolve.
 * @param totalDurationMinutes soma das duracoes dos servicos escolhidos.
 *     Opcional: ausente, vale a duracao do {@code serviceId}. O frontend ja
 *     manda este valor <b>capado em 50</b>; o backend capa de novo, porque a
 *     API tambem responde a quem nao e o frontend.
 */
public record AvailableTimesRequest(

		@NotNull(message = ValidationPatterns.OBRIGATORIO)
		@Pattern(regexp = ValidationPatterns.UUID, message = "Invalid professional ID")
		String professionalId,

		@NotNull(message = ValidationPatterns.OBRIGATORIO)
		@Pattern(regexp = ValidationPatterns.UUID, message = "Invalid service ID")
		String serviceId,

		@NotNull(message = ValidationPatterns.OBRIGATORIO)
		@Pattern(regexp = ValidationPatterns.DATA_ISO, message = "Date must be YYYY-MM-DD")
		String date,

		@Positive(message = "totalDurationMinutes deve ser inteiro positivo")
		Integer totalDurationMinutes) {

}
