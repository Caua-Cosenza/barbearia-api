package com.cauacosenza.barbearia.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cauacosenza.barbearia.domain.entity.Appointment;
import com.cauacosenza.barbearia.domain.entity.Barber;
import com.cauacosenza.barbearia.domain.entity.BarberSchedule;
import com.cauacosenza.barbearia.domain.entity.BookedService;
import com.cauacosenza.barbearia.domain.entity.ScheduleBlock;
import com.cauacosenza.barbearia.domain.enums.AppointmentStatus;
import com.cauacosenza.barbearia.domain.event.AgendamentoCriado;
import com.cauacosenza.barbearia.domain.exception.AgendamentoNoPassadoException;
import com.cauacosenza.barbearia.domain.exception.ForaDoExpedienteException;
import com.cauacosenza.barbearia.domain.exception.HorarioReservadoException;
import com.cauacosenza.barbearia.domain.exception.ProfissionalIndisponivelException;
import com.cauacosenza.barbearia.domain.exception.RecursoNaoEncontradoException;
import com.cauacosenza.barbearia.domain.exception.SlotIndisponivelException;
import com.cauacosenza.barbearia.domain.rules.SlotCalculator;
import com.cauacosenza.barbearia.domain.rules.TimeBlock;
import com.cauacosenza.barbearia.domain.rules.WorkingHours;
import com.cauacosenza.barbearia.dto.request.CreatePublicAppointmentRequest;
import com.cauacosenza.barbearia.dto.response.ProfessionalRefResponse;
import com.cauacosenza.barbearia.dto.response.PublicAppointmentResponse;
import com.cauacosenza.barbearia.dto.response.ServiceSnapshotResponse;
import com.cauacosenza.barbearia.repository.AppointmentRepository;
import com.cauacosenza.barbearia.repository.BarberRepository;
import com.cauacosenza.barbearia.repository.BarberScheduleRepository;
import com.cauacosenza.barbearia.repository.ScheduleBlockRepository;

/**
 * A criacao de agendamento de visitante.
 *
 * <h2>O backend nunca confia no frontend</h2>
 *
 * <p>Uma auditoria no Node mostrou que dava para agendar domingo as 03:00
 * chamando a API direto: a tela nunca oferecia esse horario, mas nada no
 * servidor o impedia. Todas as checagens abaixo existem porque a ausencia de
 * uma delas ja foi explorada ou e exploravel. Elas rodam <b>dentro da
 * transacao</b>, na ordem em que estao, e cada uma tem a sua excecao e a sua
 * mensagem.
 *
 * <ol>
 *   <li>data e hora no futuro — na pratica ja barrada pela validacao do DTO
 *       (422); ver {@link AgendamentoNoPassadoException}
 *   <li>o profissional existe e esta ativo
 *   <li>o profissional tem jornada naquele dia da semana — sem jornada e
 *       ordem de chegada, e ordem de chegada nao se agenda
 *   <li>o horario comeca dentro do expediente
 *   <li><b>o atendimento cabe inteiro no expediente</b> — correcao em relacao
 *       ao Node; ver abaixo
 *   <li>nao colide com bloqueio do profissional
 *   <li>os servicos existem e estao ativos
 *   <li>a duracao gravada e {@code min(soma, 50)}, a mesma que o indice usa
 * </ol>
 *
 * <h2>A correcao do item 5</h2>
 *
 * <p>O Node so verificava o <i>inicio</i>:
 * {@code requestedStart >= endMin} recusava. Com expediente ate 19:30, um
 * pedido das 19:20 com 50 minutos passava e o atendimento terminava 20:10. A
 * leitura nunca ofereceu esse horario — o {@link SlotCalculator} avanca
 * enquanto {@code inicio + duracao <= fim} — entao as duas pontas
 * discordavam, e so quem chamasse a API direto alcancava a brecha. Aqui a
 * escrita passa a exigir o mesmo que a leitura.
 *
 * <h2>A garantia final nao esta aqui</h2>
 *
 * <p>Toda checagem acima pode ser vencida por concorrencia: entre verificar e
 * inserir ha uma janela. Quem fecha a janela e a constraint de exclusao da
 * V2, dentro do commit. A divisao e explicita — <b>as validacoes dao a
 * mensagem boa, o banco da a certeza</b> — e e por isso que uma violacao da
 * constraint nao e tratada como defeito: e o caminho normal de duas pessoas
 * querendo o mesmo horario.
 */
@Service
public class PublicAppointmentService {

	private final BarberRepository barbers;

	private final com.cauacosenza.barbearia.repository.ServiceRepository services;

	private final BarberScheduleRepository schedules;

	private final ScheduleBlockRepository blocks;

	private final AppointmentRepository appointments;

	private final SlotQueryMapper mapper;

	private final Clock clock;

	/**
	 * Publica {@link AgendamentoCriado} para o
	 * {@code NotificacaoDeAgendamentoListener}, que envia o WhatsApp
	 * <b>depois do commit</b>. Ver o javadoc do listener para o porque.
	 */
	private final ApplicationEventPublisher eventos;

	public PublicAppointmentService(BarberRepository barbers,
			com.cauacosenza.barbearia.repository.ServiceRepository services,
			BarberScheduleRepository schedules, ScheduleBlockRepository blocks,
			AppointmentRepository appointments, SlotQueryMapper mapper, Clock clock,
			ApplicationEventPublisher eventos) {
		this.barbers = barbers;
		this.services = services;
		this.schedules = schedules;
		this.blocks = blocks;
		this.appointments = appointments;
		this.mapper = mapper;
		this.clock = clock;
		this.eventos = eventos;
	}

	@Transactional
	public PublicAppointmentResponse criar(CreatePublicAppointmentRequest pedido) {
		Instant quando = pedido.scheduledAtInstant();

		// (1) Passado. Inalcancavel por HTTP -- a validacao do DTO ja barrou --
		// e mantida para quem chamar este service de outro lugar.
		if (!quando.isAfter(Instant.now(this.clock))) {
			throw new AgendamentoNoPassadoException();
		}

		// (2) Profissional ativo.
		Barber barbeiro = this.barbers.findByIdAndActiveTrue(pedido.professionalId())
				.orElseThrow(RecursoNaoEncontradoException::profissional);

		// (7) Servicos ativos. Antecipado em relacao a lista acima porque a
		// duracao total e necessaria para checar (5).
		List<com.cauacosenza.barbearia.domain.entity.Service> escolhidos =
				buscarServicosNaOrdemPedida(pedido.serviceIds());

		int duracaoBruta = escolhidos.stream()
				.mapToInt(com.cauacosenza.barbearia.domain.entity.Service::getDurationMinutes)
				.sum();
		int totalAmountCents = escolhidos.stream()
				.mapToInt(com.cauacosenza.barbearia.domain.entity.Service::getAmountCents)
				.sum();

		// (8) A duracao que ocupa a agenda: a mesma conta do backfill da V2 e
		// do SlotQueryMapper. Se divergisse, a tela ofereceria horario que a
		// constraint recusaria no commit.
		int duracaoDoSlot = Math.min(duracaoBruta, SlotCalculator.MAX_SLOT_MINUTES);

		// A data e a hora como o cliente as escreveu, em Brasilia.
		LocalDate dia = LocalDate.parse(pedido.dataLocalPedida());
		LocalTime inicio = this.mapper.toHoraDeBrasilia(quando);

		// (3) Jornada no dia da semana.
		BarberSchedule jornadaDoDia = this.schedules
				.findByProfessionalIdAndDayOfWeek(barbeiro.getId(), this.mapper.dayOfWeekDoBanco(dia))
				.orElseThrow(ProfissionalIndisponivelException::new);
		WorkingHours expediente = this.mapper.toWorkingHours(jornadaDoDia);

		// (4) e (5): comeca dentro do expediente e cabe inteiro nele.
		validarExpediente(inicio, duracaoDoSlot, expediente);

		// (6) Bloqueio do profissional.
		validarBloqueios(barbeiro.getId(), dia, inicio, duracaoDoSlot);

		Appointment agendamento = montar(pedido, barbeiro, escolhidos, quando,
				totalAmountCents, duracaoDoSlot);

		Appointment gravado = gravar(agendamento);

		// O envio do WhatsApp NAO acontece aqui. O evento e entregue depois do
		// commit, e por isso nenhuma lentidao da Evolution segura a conexao
		// com o banco -- nem se manda "agendamento confirmado" de um
		// agendamento que a constraint de exclusao acabou de recusar.
		this.eventos.publishEvent(new AgendamentoCriado(
				gravado.getId(),
				pedido.guestName(),
				pedido.guestPhone(),
				gravado.getScheduledAt(),
				escolhidos.stream()
						.map(com.cauacosenza.barbearia.domain.entity.Service::getName)
						.toList(),
				totalAmountCents,
				gravado.getCancelToken()));

		return responder(gravado, totalAmountCents, duracaoBruta);
	}

	/**
	 * Os servicos pedidos, ativos, <b>na ordem em que o cliente os mandou</b>.
	 *
	 * <p>A ordem importa: o primeiro vai para {@code appointments.service_id},
	 * coluna NOT NULL que sobrou de quando um agendamento tinha um servico so.
	 * Devolver na ordem do banco mudaria qual servico fica nessa coluna e,
	 * portanto, o que relatorios antigos mostram.
	 *
	 * <p>Um id repetido e aceito e conta duas vezes na duracao e no preco —
	 * como no Node, onde {@code findManyByIds} deduplica mas a soma e feita
	 * sobre o resultado. Aqui a soma e feita sobre a lista pedida, entao
	 * repetir cobra duas vezes. Ver README.
	 */
	private List<com.cauacosenza.barbearia.domain.entity.Service> buscarServicosNaOrdemPedida(
			List<String> ids) {

		Map<String, com.cauacosenza.barbearia.domain.entity.Service> encontrados =
				this.services.findByIdInAndActiveTrue(ids).stream()
						.collect(Collectors.toMap(
								com.cauacosenza.barbearia.domain.entity.Service::getId,
								Function.identity()));

		List<com.cauacosenza.barbearia.domain.entity.Service> naOrdem = new ArrayList<>(ids.size());
		for (String id : ids) {
			com.cauacosenza.barbearia.domain.entity.Service servico = encontrados.get(id);
			if (servico == null) {
				// Id inventado e servico desativado dao a mesma resposta: a API
				// nao e um oraculo sobre o que ja existiu no catalogo.
				throw RecursoNaoEncontradoException.servico(id);
			}
			naOrdem.add(servico);
		}
		return naOrdem;
	}

	private static void validarExpediente(LocalTime inicio, int duracaoDoSlot, WorkingHours expediente) {
		if (inicio.isBefore(expediente.startsAt()) || !inicio.isBefore(expediente.endsAt())) {
			throw ForaDoExpedienteException.inicioForaDaJornada();
		}

		// A correcao: o Node parava na checagem acima.
		int fimEmMinutos = inicio.getHour() * 60 + inicio.getMinute() + duracaoDoSlot;
		int fechamentoEmMinutos = expediente.endsAt().getHour() * 60 + expediente.endsAt().getMinute();
		if (fimEmMinutos > fechamentoEmMinutos) {
			// Aritmetica em minutos, nao LocalTime.plusMinutes: um atendimento
			// que atravessasse a meia-noite daria a volta em silencio e
			// pareceria terminar de madrugada, dentro do expediente.
			throw ForaDoExpedienteException.naoCabeNoExpediente(
					LocalTime.of((fimEmMinutos / 60) % 24, fimEmMinutos % 60), expediente.endsAt());
		}
	}

	private void validarBloqueios(String professionalId, LocalDate dia, LocalTime inicio, int duracaoDoSlot) {
		List<ScheduleBlock> doDia = this.blocks.findByProfessionalIdAndDateOrderByStartTimeAsc(
				professionalId, this.mapper.toDateKey(dia));

		int inicioSlot = inicio.getHour() * 60 + inicio.getMinute();
		int fimSlot = inicioSlot + duracaoDoSlot;

		for (TimeBlock bloqueio : this.mapper.toTimeBlocks(doDia)) {
			int inicioBloqueio = bloqueio.startsAt().getHour() * 60 + bloqueio.startsAt().getMinute();
			int fimBloqueio = bloqueio.endsAt().getHour() * 60 + bloqueio.endsAt().getMinute();
			// Sobreposicao de intervalos, a mesma regra do SlotCalculator:
			// encostar nao colide.
			if (inicioSlot < fimBloqueio && fimSlot > inicioBloqueio) {
				throw new HorarioReservadoException();
			}
		}
	}

	private Appointment montar(CreatePublicAppointmentRequest pedido, Barber barbeiro,
			List<com.cauacosenza.barbearia.domain.entity.Service> escolhidos,
			Instant quando, int totalAmountCents, int duracaoDoSlot) {

		Appointment agendamento = new Appointment();
		agendamento.setProfessional(barbeiro);
		// Legado: coluna NOT NULL de quando um agendamento tinha um servico so.
		agendamento.setService(escolhidos.get(0));
		agendamento.setScheduledAt(quando);
		agendamento.setStatus(AppointmentStatus.PENDING);
		agendamento.setNotes(pedido.notes());
		// O preco NUNCA vem do cliente: e a soma do catalogo, lida agora.
		agendamento.setAmountCents(totalAmountCents);
		agendamento.setSlotDurationMinutes(duracaoDoSlot);
		// Credencial do proprio cliente sobre o proprio agendamento. UUID
		// aleatorio e nao o id: adivinhar um id nao pode dar poder de cancelar.
		agendamento.setCancelToken(UUID.randomUUID().toString());

		// PII: o converter cifra na gravacao. E-mail nulo atravessa nulo.
		agendamento.setGuestName(pedido.guestName());
		agendamento.setGuestPhone(pedido.guestPhone());
		agendamento.setGuestEmail(pedido.guestEmail());

		for (com.cauacosenza.barbearia.domain.entity.Service servico : escolhidos) {
			BookedService item = new BookedService();
			item.setService(servico);
			// Snapshot: o catalogo muda, o que foi contratado nao.
			item.setAmountCents(servico.getAmountCents());
			item.setDurationMinutes(servico.getDurationMinutes());
			agendamento.adicionarServico(item);
		}
		return agendamento;
	}

	/**
	 * Grava, traduzindo <b>as duas faces da corrida perdida</b> para a mesma
	 * excecao de negocio.
	 *
	 * <p>O teste da Fase 4 mostrou que o perdedor de uma disputa recebe
	 * {@code DataIntegrityViolationException} quando o vencedor ja comitou, e
	 * {@code CannotAcquireLockException} — deadlock, subclasse de
	 * {@link PessimisticLockingFailureException} — quando as duas transacoes
	 * chegaram ao indice ainda abertas. O segundo caso foi o mais comum: 18 de
	 * 20 iteracoes.
	 *
	 * <p>Traduzir so o primeiro deixaria o deadlock cair no catch-all do
	 * {@code GlobalExceptionHandler} e virar 500 "Erro inesperado" na maioria
	 * das disputas reais. O cliente veria falha de sistema onde deveria ler
	 * "esse horario acabou de ser reservado".
	 *
	 * <p>O {@code catch} de {@link DataIntegrityViolationException} e
	 * deliberadamente amplo: qualquer violacao de integridade na insercao de um
	 * agendamento ja validado e, na pratica, a constraint de sobreposicao. Um
	 * FK quebrado seria defeito de programacao, nao entrada do cliente — mas
	 * sera reportado como 409 em vez de 500. O
	 * {@code initCause} preserva a excecao original, que o handler loga.
	 */
	private Appointment gravar(Appointment agendamento) {
		try {
			return this.appointments.saveAndFlush(agendamento);
		}
		catch (DataIntegrityViolationException | PessimisticLockingFailureException ex) {
			throw new SlotIndisponivelException(ex);
		}
	}

	private static PublicAppointmentResponse responder(Appointment gravado,
			int totalAmountCents, int duracaoBruta) {

		return new PublicAppointmentResponse(
				gravado.getId(),
				gravado.getCancelToken(),
				gravado.getScheduledAt(),
				gravado.getStatus().name(),
				gravado.getNotes(),
				gravado.getBookedServices().stream().map(ServiceSnapshotResponse::de).toList(),
				totalAmountCents,
				// Soma BRUTA, sem o teto: e o tempo de servico contratado, nao
				// o bloco ocupado na agenda. Ver PublicAppointmentResponse.
				duracaoBruta,
				ProfessionalRefResponse.de(gravado.getProfessional()));
	}

}
