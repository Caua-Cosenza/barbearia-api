package com.cauacosenza.barbearia.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cauacosenza.barbearia.domain.entity.Barber;
import com.cauacosenza.barbearia.domain.entity.BarberSchedule;
import com.cauacosenza.barbearia.domain.exception.RecursoNaoEncontradoException;
import com.cauacosenza.barbearia.domain.rules.BookedSlot;
import com.cauacosenza.barbearia.domain.rules.SlotCalculator;
import com.cauacosenza.barbearia.domain.rules.SlotRequest;
import com.cauacosenza.barbearia.domain.rules.TimeBlock;
import com.cauacosenza.barbearia.domain.rules.WorkingHours;
import com.cauacosenza.barbearia.dto.request.AvailableTimesRequest;
import com.cauacosenza.barbearia.dto.response.AvailableTimesResponse;
import com.cauacosenza.barbearia.repository.AppointmentRepository;
import com.cauacosenza.barbearia.repository.BarberRepository;
import com.cauacosenza.barbearia.repository.BarberScheduleRepository;
import com.cauacosenza.barbearia.repository.ScheduleBlockRepository;

/**
 * Os horarios livres de um profissional em um dia.
 *
 * <p>Esta classe <b>nao calcula nada</b>. Ela busca, converte e entrega ao
 * {@link SlotCalculator}, que e puro e foi coberto na Fase 3 com dezenas de
 * casos de borda sem subir banco nenhum. Tudo que e fuso, texto e soma de
 * duracao fica no {@link SlotQueryMapper}. O que sobra aqui e orquestracao —
 * e e assim que a parte mais delicada do sistema continua testavel em
 * milissegundos.
 *
 * <p><b>Profissional sem jornada no dia devolve lista vazia, nao erro.</b>
 * Sexta e sabado sao por ordem de chegada e domingo e fechado; a ausencia de
 * linha em {@code professional_availability} e a propria regra. O contraste
 * com a <i>escrita</i> e deliberado: criar agendamento num dia desses e 400,
 * porque ai alguem esta pedindo o que a tela nunca ofereceu.
 */
@Service
@Transactional(readOnly = true)
public class AvailableTimesService {

	private final BarberRepository barbers;

	private final com.cauacosenza.barbearia.repository.ServiceRepository services;

	private final BarberScheduleRepository schedules;

	private final ScheduleBlockRepository blocks;

	private final AppointmentRepository appointments;

	private final SlotQueryMapper mapper;

	private final SlotCalculator calculator = new SlotCalculator();

	/**
	 * O relogio e injetado para que o corte de antecedencia minima seja
	 * testavel. Em producao e o do sistema; no teste, um
	 * {@link Clock#fixed(java.time.Instant, java.time.ZoneId)} prova que o
	 * filtro de 15 minutos corta no minuto certo, o que seria impossivel com
	 * {@code Instant.now()} embutido.
	 */
	private final Clock clock;

	public AvailableTimesService(BarberRepository barbers,
			com.cauacosenza.barbearia.repository.ServiceRepository services,
			BarberScheduleRepository schedules, ScheduleBlockRepository blocks,
			AppointmentRepository appointments, SlotQueryMapper mapper, Clock clock) {
		this.barbers = barbers;
		this.services = services;
		this.schedules = schedules;
		this.blocks = blocks;
		this.appointments = appointments;
		this.mapper = mapper;
		this.clock = clock;
	}

	public AvailableTimesResponse calcular(AvailableTimesRequest pedido) {
		Barber barbeiro = this.barbers.findByIdAndActiveTrue(pedido.professionalId())
				.orElseThrow(RecursoNaoEncontradoException::profissional);

		com.cauacosenza.barbearia.domain.entity.Service servico =
				this.services.findByIdAndActiveTrue(pedido.serviceId())
						.orElseThrow(RecursoNaoEncontradoException::servico);

		LocalDate dia = LocalDate.parse(pedido.date());
		int dayOfWeek = this.mapper.dayOfWeekDoBanco(dia);

		Optional<BarberSchedule> jornada =
				this.schedules.findByProfessionalIdAndDayOfWeek(barbeiro.getId(), dayOfWeek);
		if (jornada.isEmpty()) {
			// Sem jornada, sem horarios -- e nao e erro.
			return new AvailableTimesResponse(List.of());
		}

		// A duracao bruta: a soma que o frontend manda quando ha varios
		// servicos, ou a do servico de referencia. O teto de 50 e aplicado pelo
		// SlotCalculator, que trabalha com valor bruto por principio.
		int duracaoBruta = (pedido.totalDurationMinutes() != null)
				? pedido.totalDurationMinutes()
				: servico.getDurationMinutes();

		WorkingHours expediente = this.mapper.toWorkingHours(jornada.get());

		List<BookedSlot> ocupados = this.mapper.toBookedSlots(
				this.appointments.buscarQueOcupamAGrade(
						barbeiro.getId(),
						this.mapper.toInstantUtc(dia, LocalTime.MIDNIGHT),
						this.mapper.toInstantUtc(dia.plusDays(1), LocalTime.MIDNIGHT)));

		List<TimeBlock> bloqueios = this.mapper.toTimeBlocks(
				this.blocks.findByProfessionalIdAndDateOrderByStartTimeAsc(
						barbeiro.getId(), this.mapper.toDateKey(dia)));

		List<LocalTime> livres = this.calculator.calcular(
				expediente, ocupados, bloqueios,
				new SlotRequest(dia, duracaoBruta),
				agoraSeForHoje(dia));

		return new AvailableTimesResponse(livres.stream().map(this.mapper::toHoraTexto).toList());
	}

	/**
	 * A hora atual em Brasilia quando o dia pedido e hoje; {@code null} caso
	 * contrario.
	 *
	 * <p>O {@code null} nao e descuido: e como o {@link SlotCalculator} recebe
	 * "nao filtre por antecedencia". Para um dia futuro todos os horarios
	 * valem, e comparar com o relogio produziria o absurdo de esconder as 08:40
	 * de amanha porque agora sao 09:00.
	 */
	private LocalTime agoraSeForHoje(LocalDate dia) {
		// Converte explicitamente a partir do instante, em vez de
		// clock.withZone(...): assim a unica coisa pedida ao relogio e o
		// instante, que e o que ele de fato representa. O fuso do proprio
		// Clock nunca entra na conta, e o caminho e o mesmo que o
		// SlotQueryMapper usa em todas as outras conversoes.
		var agoraEmBrasilia = Instant.now(this.clock).atZone(SlotQueryMapper.FUSO_BRASILIA);
		return dia.equals(agoraEmBrasilia.toLocalDate()) ? agoraEmBrasilia.toLocalTime() : null;
	}

}
