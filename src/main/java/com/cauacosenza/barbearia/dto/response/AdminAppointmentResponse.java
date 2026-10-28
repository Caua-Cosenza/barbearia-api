package com.cauacosenza.barbearia.dto.response;

import java.time.Instant;
import java.util.List;

import com.cauacosenza.barbearia.domain.entity.Appointment;

/**
 * Uma linha da tabela do painel — {@code GET /api/v1/admin/appointments}.
 *
 * <h2>{@code service} e {@code services} coexistem, e os dois sao usados</h2>
 *
 * <p>Parece redundante e nao e. O painel faz:
 *
 * <pre>
 * services.length &gt; 0 ? services.map(s =&gt; s.name).join(' + ') : service.name
 * </pre>
 *
 * <p>{@code services} e a lista real do agendamento (corte + barba +
 * pigmentacao); {@code service} e o servico unico da coluna legada
 * {@code appointments.service_id}, usado como fallback pelos agendamentos
 * anteriores a tabela {@code appointment_services}.
 *
 * <p><b>Omitir {@code services} foi bug real:</b> o painel mostrava "Corte
 * Disfarcado" e a Pigmentacao sumia da tela — o barbeiro so descobria o
 * servico a mais quando o cliente sentava na cadeira. O painel tambem soma
 * {@code services[].durationMinutes} (com teto de 50) para mostrar a duracao,
 * entao a lista incompleta tambem encurtava o tempo exibido.
 *
 * <h2>PII decifrada</h2>
 *
 * <p>{@code customerName}, {@code customerPhone} e {@code customerEmail} saem
 * em texto claro — e o painel do dono, que precisa ligar para o cliente. Vem
 * do {@code EncryptedStringConverter}, sem decifrar nada a mao.
 *
 * <p>A origem da PII depende do tipo de reserva: visitante tem
 * {@code guest_*_encrypted} preenchido; cliente com conta tem a PII em
 * {@code users}. Quando nao ha nem um nem outro, o Node escreve
 * {@code "Desconhecido"} — literal reproduzido aqui para a tela nao imprimir
 * "null".
 */
public record AdminAppointmentResponse(
		String id,
		Instant scheduledAt,
		String status,
		String customerName,
		String customerPhone,
		String customerEmail,
		ServicoPrincipal service,
		List<ServicoDoAgendamento> services,
		ProfessionalRefResponse professional) {

	/** Quando nao ha PII de visitante nem conta associada. */
	private static final String SEM_DADO = "Desconhecido";

	/**
	 * Ordem em que os servicos aparecem no painel: o mais longo primeiro,
	 * desempatando por nome.
	 *
	 * <p><b>Por que ordenar.</b> Nem a consulta daqui nem o {@code include} do
	 * Prisma ordenam {@code appointment_services}, e a tabela nao tem coluna
	 * de posicao. Sem ordenar, a lista sai na ordem que o plano de execucao
	 * der — que pode mudar entre consultas — e o painel, que junta os nomes
	 * com {@code " + "}, mostraria "Barba + Corte" numa recarga e
	 * "Corte + Barba" na seguinte.
	 *
	 * <p><b>Por que duracao decrescente.</b> No Node a ordem e, por acidente,
	 * a de insercao (ordem fisica no heap do Postgres), que e a ordem em que o
	 * cliente escolheu os servicos — e quem reserva escolhe o principal
	 * primeiro. Ordenar pelo mais longo reproduz isso no caso real
	 * ("Corte + Barba") <b>e</b> e deterministico, que a ordem do Node nao e
	 * por contrato.
	 *
	 * <p>Nao e garantia de igualdade com o Node: um cliente que escolhesse a
	 * barba antes do corte veria a ordem invertida entre os dois backends. A
	 * solucao definitiva e uma coluna de posicao em
	 * {@code appointment_services}, que exige migration no schema
	 * compartilhado — trabalho futuro, registrado no README.
	 */
	private static final java.util.Comparator<com.cauacosenza.barbearia.domain.entity.BookedService>
			ORDEM_DE_EXIBICAO = java.util.Comparator
					.comparingInt(com.cauacosenza.barbearia.domain.entity.BookedService::getDurationMinutes)
					.reversed()
					.thenComparing(item -> item.getService().getName());

	/** O servico da coluna legada {@code appointments.service_id}. */
	public record ServicoPrincipal(String id, String name, int durationMinutes) {

	}

	/**
	 * Um item de {@code appointment_services}.
	 *
	 * <p>Sem {@code amountCents}: o painel nao mostra preco nesta tela, e o
	 * Node tambem nao o envia. Ver {@code ServiceSnapshotResponse}, que leva o
	 * valor porque a tela de confirmacao do cliente precisa dele.
	 */
	public record ServicoDoAgendamento(String name, int durationMinutes) {

	}

	public static AdminAppointmentResponse de(Appointment agendamento) {
		return new AdminAppointmentResponse(
				agendamento.getId(),
				agendamento.getScheduledAt(),
				agendamento.getStatus().name(),
				nomeDoCliente(agendamento),
				telefoneDoCliente(agendamento),
				emailDoCliente(agendamento),
				new ServicoPrincipal(
						agendamento.getService().getId(),
						agendamento.getService().getName(),
						agendamento.getService().getDurationMinutes()),
				agendamento.getBookedServices().stream()
						.sorted(ORDEM_DE_EXIBICAO)
						.map(item -> new ServicoDoAgendamento(
								item.getService().getName(),
								item.getDurationMinutes()))
						.toList(),
				ProfessionalRefResponse.de(agendamento.getProfessional()));
	}

	private static String nomeDoCliente(Appointment agendamento) {
		if (agendamento.getGuestName() != null) {
			return agendamento.getGuestName();
		}
		return (agendamento.getUser() != null) ? agendamento.getUser().getName() : SEM_DADO;
	}

	private static String telefoneDoCliente(Appointment agendamento) {
		if (agendamento.getGuestPhone() != null) {
			return agendamento.getGuestPhone();
		}
		return (agendamento.getUser() != null) ? agendamento.getUser().getPhone() : SEM_DADO;
	}

	/**
	 * E-mail e o unico dos tres que pode sair nulo: o Node so o le de
	 * {@code guest_email_encrypted}, que e opcional, e nao cai para o e-mail
	 * da conta. O tipo do frontend ja declara {@code string | null}.
	 */
	private static String emailDoCliente(Appointment agendamento) {
		return agendamento.getGuestEmail();
	}

}
