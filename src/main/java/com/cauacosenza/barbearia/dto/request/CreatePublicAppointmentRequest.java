package com.cauacosenza.barbearia.dto.request;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.cauacosenza.barbearia.validation.DataFutura;
import com.cauacosenza.barbearia.validation.DataHoraComFuso;

/**
 * Corpo de {@code POST /api/v1/appointments/public} — a reserva de visitante,
 * sem conta e sem login. E o unico jeito de agendar pelo site.
 *
 * <p>Traducao do {@code createPublicAppointmentSchema} (Zod) do Node, regra a
 * regra, incluindo o {@code .refine} que exige data futura.
 *
 * <p><b>Preco nunca entra aqui.</b> Nao ha campo de valor, e a configuracao
 * global do Jackson recusa propriedade desconhecida, entao mandar
 * {@code amountCents} e erro, nao campo ignorado. O valor e sempre lido do
 * catalogo pelo {@code serviceIds}. Para os nomes que o Node barra
 * explicitamente — {@code amount}, {@code price}, {@code value} — existe ainda
 * o {@code RejeitaPrecoNoCorpoFilter}, que responde 400 antes da
 * desserializacao, como o {@code rejectAmountInBody} fazia.
 *
 * @param professionalId profissional escolhido, UUID
 * @param serviceIds um ou mais servicos, UUID cada. A ordem importa: o
 *     primeiro vai para a coluna {@code appointments.service_id}, que e
 *     NOT NULL e e legado de quando um agendamento tinha um servico so
 * @param scheduledAt data e hora em ISO-8601 <b>com fuso</b>, por exemplo
 *     {@code 2026-10-28T10:00:00-03:00}. O frontend monta exatamente assim.
 *     Fica {@code String} e nao {@link OffsetDateTime} de proposito: assim a
 *     recusa de um valor malformado vem da Bean Validation, com a mensagem e o
 *     status que o Node da, e nao da desserializacao do Jackson
 * @param guestName nome do visitante, 2 a 100 caracteres. Vai cifrado para
 *     {@code guest_name_encrypted}
 * @param guestPhone telefone so com digitos, 10 a 15. Vai cifrado
 * @param guestEmail opcional. Vai cifrado; nulo atravessa como nulo
 * @param notes observacao livre, ate 500 caracteres. Nao e cifrada — e campo
 *     sobre o atendimento, nao sobre a pessoa
 */
public record CreatePublicAppointmentRequest(

		@NotNull(message = ValidationPatterns.OBRIGATORIO)
		@Pattern(regexp = ValidationPatterns.UUID, message = "Invalid uuid")
		String professionalId,

		@NotNull(message = ValidationPatterns.OBRIGATORIO)
		@Size(min = 1, message = "Selecione ao menos um serviço")
		List<@Pattern(regexp = ValidationPatterns.UUID, message = "Invalid uuid") String> serviceIds,

		@NotNull(message = ValidationPatterns.OBRIGATORIO)
		@DataHoraComFuso
		@DataFutura(message = "Data deve ser no futuro")
		String scheduledAt,

		@NotNull(message = ValidationPatterns.OBRIGATORIO)
		@Size(min = 2, max = 100)
		String guestName,

		@NotNull(message = ValidationPatterns.OBRIGATORIO)
		@Pattern(regexp = ValidationPatterns.TELEFONE,
				message = "Telefone deve conter apenas dígitos (10-15)")
		String guestPhone,

		@Email
		String guestEmail,

		@Size(max = 500)
		String notes) {

	/**
	 * O instante pedido, ja resolvido para UTC.
	 *
	 * <p>So pode ser chamado depois da validacao — se o formato nao passou, o
	 * pedido nunca chega ao service. O {@link IllegalStateException} existe
	 * para que um uso fora de ordem apareca como defeito de programacao e nao
	 * como 400 enganoso para o cliente.
	 */
	public Instant scheduledAtInstant() {
		try {
			return OffsetDateTime.parse(this.scheduledAt).toInstant();
		}
		catch (DateTimeParseException ex) {
			throw new IllegalStateException(
					"scheduledAt deveria ter sido validado antes de chegar aqui", ex);
		}
	}

	/**
	 * A data, em {@code YYYY-MM-DD}, <b>como o cliente a escreveu</b>.
	 *
	 * <p>Recorta a string em vez de converter o instante para um fuso, de
	 * proposito: e o que o Node faz
	 * ({@code dto.scheduledAt.split('T')[0]}) para achar o dia da semana e para
	 * consultar {@code blocked_slots.date}. Como o frontend sempre manda
	 * {@code -03:00}, o recorte e a data de Brasilia; se um cliente mandasse
	 * {@code 2026-10-28T23:00:00Z}, o recorte daria {@code 2026-10-28} e a
	 * conversao daria {@code 2026-10-28} as 20:00 — mesmo dia. Divergiriam so
	 * em horarios que nao existem no expediente.
	 */
	public String dataLocalPedida() {
		return this.scheduledAt.substring(0, this.scheduledAt.indexOf('T'));
	}

}
