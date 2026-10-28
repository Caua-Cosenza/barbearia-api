package com.cauacosenza.barbearia.dto.request;

/**
 * As expressoes regulares que o backend Node aplica com Zod, transcritas uma
 * unica vez.
 *
 * <p>Ficam reunidas aqui porque sao <b>contrato com o frontend</b>, nao
 * preferencia de implementacao: cada uma tem um equivalente exato em
 * {@code utils/validation.ts} ou em {@code CreateAppointmentDto.ts}, e uma
 * divergencia de um caractere muda quais pedidos a API aceita. Espalhadas
 * pelos records, a proxima a ser escrita seria copiada de memoria.
 */
final class ValidationPatterns {

	/**
	 * UUID em qualquer versao, como o {@code z.string().uuid()} do Zod.
	 *
	 * <p>Os ids no banco sao TEXT e vem do {@code @default(uuid())} do Prisma,
	 * entao sao sempre UUID v4 — mas a checagem e de formato, nao de versao,
	 * igual a do Zod.
	 */
	static final String UUID = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

	/** {@code YYYY-MM-DD}, o mesmo {@code /^\d{4}-\d{2}-\d{2}$/} do Node. */
	static final String DATA_ISO = "^\\d{4}-\\d{2}-\\d{2}$";

	/**
	 * {@code HH:MM}, o mesmo {@code /^\d{2}:\d{2}$/} do
	 * {@code createBlockedSlotSchema}.
	 *
	 * <p>Como no Node, so o <b>formato</b> e checado: {@code "99:99"} passa
	 * daqui. Quem recusa valor impossivel e a conversao do
	 * {@code SlotQueryMapper} na leitura, que levanta
	 * {@code IllegalStateException} citando a coluna.
	 */
	static final String HORA_HHMM = "^\\d{2}:\\d{2}$";

	/**
	 * Telefone: 10 a 15 digitos, <b>so digitos</b>.
	 *
	 * <p>Identico ao {@code /^\d{10,15}$/} de {@code createPublicAppointmentSchema}.
	 * Note que <i>nao</i> e o {@code phoneSchema} de {@code utils/validation.ts}
	 * ({@code /^\+?[1-9]\d{7,14}$/}, que admite {@code +}): esse e usado em
	 * outras rotas. A reserva publica exige digitos puros, e o frontend
	 * colabora mandando {@code customerPhone.replace(/\D/g, '')}.
	 */
	static final String TELEFONE = "^\\d{10,15}$";

	/**
	 * Telefone no formato internacional, admitindo {@code +}: o
	 * {@code phoneSchema} de {@code utils/validation.ts}
	 * ({@code /^\+?[1-9]\d{7,14}$/}).
	 *
	 * <p>Usado no cadastro de conta. As duas regras de telefone convivem no
	 * Node desde sempre e nao sao intercambiaveis — a reserva publica exige
	 * digitos puros porque o frontend ja manda
	 * {@code customerPhone.replace(/\D/g, '')}.
	 */
	static final String TELEFONE_INTERNACIONAL = "^\\+?[1-9]\\d{7,14}$";

	/**
	 * A mensagem do Zod para campo obrigatorio ausente, literalmente.
	 *
	 * <p>Por isso os campos usam {@code @NotNull} com esta mensagem e
	 * <b>nao</b> {@code @NotBlank}: o {@code @NotBlank} reclamaria de nulo com
	 * a mensagem de formato ("Invalid professional ID"), e o Node diz
	 * "Required". Os {@code @Pattern} e {@code @Size} que acompanham sao
	 * tolerantes a nulo, entao um campo ausente produz exatamente uma
	 * violacao, com exatamente o texto que o Node produz.
	 */
	static final String OBRIGATORIO = "Required";

	private ValidationPatterns() {
	}

}
