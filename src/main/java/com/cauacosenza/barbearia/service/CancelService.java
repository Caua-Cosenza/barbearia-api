package com.cauacosenza.barbearia.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cauacosenza.barbearia.domain.entity.Appointment;
import com.cauacosenza.barbearia.domain.entity.BookedService;
import com.cauacosenza.barbearia.domain.enums.AppointmentStatus;
import com.cauacosenza.barbearia.domain.event.AgendamentoCancelado;
import com.cauacosenza.barbearia.domain.exception.PrazoCancelamentoException;
import com.cauacosenza.barbearia.domain.exception.RecursoNaoEncontradoException;
import com.cauacosenza.barbearia.dto.response.CancelPageResponse;
import com.cauacosenza.barbearia.repository.AppointmentRepository;

/**
 * O cancelamento pelo link que o cliente recebe por WhatsApp.
 *
 * <p>Nao ha login: o {@code cancelToken} <b>e</b> a credencial. Quem tem o
 * link cancela. E por isso que ele e um UUID aleatorio e nao o id do
 * agendamento — se fosse o id, qualquer um que descobrisse o id de outra
 * pessoa poderia cancelar a reserva dela.
 *
 * <h2>Uma regra, dois lugares, e eles precisam concordar</h2>
 *
 * <p>{@link #consultar(String)} devolve {@code canCancel} e
 * {@link #cancelar(String)} decide se aceita. Se discordassem, a tela
 * mostraria o botao habilitado e o clique daria erro — exatamente quando o
 * cliente esta com pressa. Por isso os dois chamam
 * {@link #podeCancelar(Appointment)}.
 *
 * <h2>O segundo clique no mesmo link</h2>
 *
 * <p>Cancelar duas vezes nao refaz o cancelamento nem explode: o status ja nao
 * e cancelavel, entao sai um 400 de negocio com "Agendamento não pode ser
 * cancelado", e o {@code cancelled_at} original fica intacto. O frontend trata
 * isso como mensagem na tela. Repetir o {@code UPDATE} sobrescreveria a hora
 * real do cancelamento por uma hora qualquer de alguem conferindo o link.
 */
@Service
public class CancelService {

	/**
	 * Antecedencia minima para cancelar sozinho pelo link.
	 *
	 * <p>Uma hora, o mesmo {@code ONE_HOUR_MS} do Node. Depois disso o cliente
	 * precisa falar com a barbearia: a essa altura o profissional ja organizou
	 * o dia em torno daquele horario.
	 */
	private static final Duration ANTECEDENCIA_MINIMA = Duration.ofHours(1);

	private final AppointmentRepository appointments;

	private final Clock clock;

	/** Publica {@link AgendamentoCancelado} para avisar o barbeiro. */
	private final ApplicationEventPublisher eventos;

	public CancelService(AppointmentRepository appointments, Clock clock,
			ApplicationEventPublisher eventos) {
		this.appointments = appointments;
		this.clock = clock;
		this.eventos = eventos;
	}

	/** Os dados da tela de cancelamento. */
	@Transactional(readOnly = true)
	public CancelPageResponse consultar(String token) {
		Appointment agendamento = buscar(token);
		List<BookedService> itens = agendamento.getBookedServices();

		return new CancelPageResponse(
				agendamento.getId(),
				// O Node devolve string vazia, nao nulo, quando nao ha nome de
				// visitante -- a tela imprimiria "null".
				(agendamento.getGuestName() != null) ? agendamento.getGuestName() : "",
				agendamento.getScheduledAt(),
				agendamento.getStatus().name(),
				itens.stream()
						.map(item -> new CancelPageResponse.ItemResponse(
								item.getService().getName(),
								item.getAmountCents(),
								item.getDurationMinutes()))
						.toList(),
				itens.stream().mapToInt(BookedService::getAmountCents).sum(),
				// Soma bruta, sem o teto de 50: tempo de servico contratado.
				itens.stream().mapToInt(BookedService::getDurationMinutes).sum(),
				podeCancelar(agendamento));
	}

	/** Efetiva o cancelamento. */
	@Transactional
	public void cancelar(String token) {
		Appointment agendamento = buscar(token);
		List<BookedService> itens = agendamento.getBookedServices();

		if (!agendamento.getStatus().ocupaAgenda()) {
			throw PrazoCancelamentoException.statusNaoCancelavel();
		}
		if (!dentroDoPrazo(agendamento)) {
			throw PrazoCancelamentoException.prazoEncerrado();
		}

		agendamento.setStatus(AppointmentStatus.CANCELLED);
		agendamento.setCancelledAt(Instant.now(this.clock));
		this.appointments.saveAndFlush(agendamento);

		// Os nomes dos servicos sao lidos AGORA, dentro da transacao: o
		// ouvinte roda depois do commit, com a sessao do Hibernate ja fechada,
		// e tocar a colecao la levantaria LazyInitializationException.
		this.eventos.publishEvent(new AgendamentoCancelado(
				agendamento.getId(),
				(agendamento.getGuestName() != null) ? agendamento.getGuestName() : "",
				agendamento.getScheduledAt(),
				itens.stream().map(item -> item.getService().getName()).toList()));
		// O horario volta a ser vendavel sozinho: o WHERE parcial da constraint
		// de exclusao so enxerga PENDING e CONFIRMED, entao a linha sai do
		// indice no mesmo UPDATE. Nao ha nada a limpar.
	}

	private Appointment buscar(String token) {
		return this.appointments.findByCancelToken(token)
				.orElseThrow(RecursoNaoEncontradoException::agendamento);
	}

	/**
	 * {@code true} quando o agendamento ainda pode ser cancelado pelo link.
	 *
	 * <p>Status cancelavel <b>e</b> mais de uma hora de antecedencia. As duas
	 * condicoes, na mesma funcao, usadas pela consulta e pela escrita.
	 */
	private boolean podeCancelar(Appointment agendamento) {
		return agendamento.getStatus().ocupaAgenda() && dentroDoPrazo(agendamento);
	}

	private boolean dentroDoPrazo(Appointment agendamento) {
		Duration falta = Duration.between(Instant.now(this.clock), agendamento.getScheduledAt());
		// Estritamente maior, como o '>' do Node: faltando exatamente uma hora,
		// o prazo ja venceu.
		return falta.compareTo(ANTECEDENCIA_MINIMA) > 0;
	}

}
