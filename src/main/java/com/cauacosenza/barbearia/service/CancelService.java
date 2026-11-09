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
 * <p>Cancelar duas vezes nao refaz o cancelamento nem explode. O primeiro
 * clique <b>apaga o token</b>, entao o segundo nao encontra agendamento
 * nenhum: sai o 404 de "Agendamento não encontrado", igual ao de um token
 * inventado, e o {@code cancelled_at} original fica intacto por nao haver
 * caminho para reescreve-lo. Antes de o token morrer, o segundo clique dava
 * 400 e o {@code UPDATE} era barrado pelo status; hoje e barrado antes, na
 * busca.
 *
 * <h2>O token nao e credencial de historico</h2>
 *
 * <p>Duas regras limitam a vida dele, e as duas existem porque o link viaja
 * por WhatsApp, fica no historico do navegador e sobrevive a encaminhamento:
 *
 * <ul>
 *   <li><b>morre quando usado</b> — ver {@link #cancelar(String)};
 *   <li><b>a leitura expira</b> {@value #DIAS_DE_LEITURA} dias depois do
 *       horario marcado — ver {@link #consultar(String)}.
 * </ul>
 *
 * <p>As duas recusas respondem exatamente como um token inexistente. O que
 * <b>nao</b> muda: o nome continua saindo na pagina enquanto ela abre. Quem
 * tem o link tem a mensagem do WhatsApp, que ja traz nome, data, servicos e
 * total — esconder na pagina nao protegeria nada e atrapalharia quem esta
 * cancelando de boa-fe.
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

	/**
	 * Por quanto tempo DEPOIS do atendimento o link ainda abre a pagina.
	 *
	 * <p>O token nao e credencial de historico: ele viaja por WhatsApp, fica
	 * no historico do navegador e sobrevive a qualquer encaminhamento da
	 * mensagem. Sem prazo, um link de um ano atras ainda devolve nome,
	 * horario, servicos e valor pago.
	 */
	static final int DIAS_DE_LEITURA = 7;

	private static final Duration VALIDADE_DA_LEITURA = Duration.ofDays(DIAS_DE_LEITURA);

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

		// Leitura expira: o link e para conferir e cancelar, nao para
		// consultar historico. Sem este corte, um link vazado abre a pagina
		// anos depois -- medido com um agendamento de maio de 2024, que ainda
		// devolvia nome, horario, servicos e valor pago.
		//
		// A recusa reusa a MESMA excecao de token inexistente, de proposito:
		// uma mensagem propria ("este link expirou") contaria a quem tem um
		// link antigo que o agendamento existiu, e abriria exatamente o canal
		// de distincao que a invalidacao no cancelamento fecha.
		if (leituraExpirada(agendamento)) {
			throw RecursoNaoEncontradoException.agendamento();
		}

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

		// O token morre aqui. Apagar a coluna, e nao marcar um "usado em":
		// assim a recusa de token morto passa pelo MESMO caminho da de token
		// inexistente -- `findByCancelToken` nao acha, `buscar` lanca -- e as
		// duas respostas nao podem divergir, porque sao a mesma linha de
		// codigo. Uma coluna de "usado" exigiria uma segunda checagem, que
		// alguem depois ajustaria num lugar e nao no outro.
		//
		// A unicidade do indice nao estorva: no Postgres, nulos nao colidem
		// em indice unico, entao varios agendamentos cancelados convivem.
		//
		// Custo assumido: quem clicar duas vezes no link recebe "Agendamento
		// nao encontrado" na segunda, em vez de "Agendamento nao pode ser
		// cancelado". Nenhuma das duas e sucesso, e a troca paga o fechamento
		// do canal de distincao.
		agendamento.setCancelToken(null);
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
	 * A janela de leitura do link acabou?
	 *
	 * <p>Sete dias <b>depois do horario marcado</b>. O cliente pode querer
	 * conferir o que pagou no dia seguinte ao corte; um ano depois, nao.
	 *
	 * <p>Conta do horario do atendimento, e nao da criacao: um agendamento
	 * marcado com dois meses de antecedencia precisa do link aberto nesses
	 * dois meses.
	 *
	 * <p>Vale so para a <b>leitura</b>. A escrita ja e barrada antes, pela
	 * antecedencia minima de uma hora, que nao muda.
	 */
	private boolean leituraExpirada(Appointment agendamento) {
		return agendamento.getScheduledAt()
				.isBefore(Instant.now(this.clock).minus(VALIDADE_DA_LEITURA));
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
