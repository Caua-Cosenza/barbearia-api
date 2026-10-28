package com.cauacosenza.barbearia.service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.cauacosenza.barbearia.domain.entity.Appointment;
import com.cauacosenza.barbearia.domain.entity.BarberSchedule;
import com.cauacosenza.barbearia.domain.entity.BookedService;
import com.cauacosenza.barbearia.domain.entity.ScheduleBlock;
import com.cauacosenza.barbearia.domain.rules.BookedSlot;
import com.cauacosenza.barbearia.domain.rules.SlotCalculator;
import com.cauacosenza.barbearia.domain.rules.TimeBlock;
import com.cauacosenza.barbearia.domain.rules.WorkingHours;

/**
 * A ponte entre o banco e o calculo de horarios: converte entities JPA nos
 * records de {@code domain/rules}.
 *
 * <p>Aqui mora <b>tudo que o {@link SlotCalculator} se recusa a saber</b>. O
 * calculo e puro e trabalha em hora local de Brasilia sobre listas de records;
 * fuso, texto, soma de duracoes e convencao de dia da semana sao problemas
 * desta classe. Essa fronteira e o que mantem {@code domain/rules} sem import
 * de Spring e de JPA.
 *
 * <p>As quatro traducoes, e por que cada uma pode morder:
 *
 * <ul>
 *   <li><b>{@code Instant} UTC para {@link LocalTime} de Brasilia.</b> A
 *       coluna {@code scheduled_at} e UTC. Um agendamento as 03:00 UTC e
 *       meia-noite do dia <i>anterior</i> em Brasilia, e tratar o Instant como
 *       hora local poria o atendimento tres horas adiante.
 *   <li><b>TEXT {@code "HH:MM"} para {@link LocalTime}.</b> As colunas de hora
 *       sao texto, nao {@code TIME}.
 *   <li><b>Soma das duracoes dos itens, com teto e fallback.</b> Ver
 *       {@link #slotDurationMinutes(Appointment)}.
 *   <li><b>{@link java.time.DayOfWeek} do Java para a convencao do banco.</b>
 *       O Java usa 1 = segunda ... 7 = domingo; o banco guarda o
 *       {@code Date.getDay()} do JavaScript, 0 = domingo ... 6 = sabado.
 *       Errar por um dia aqui devolve a agenda do dia errado sem erro algum, e
 *       e por isso que a conversao nao e reimplementada: delega a
 *       {@link WorkingHours#dayOfWeekOf(LocalDate)}, que a Fase 3 deixou
 *       pronta e testada.
 * </ul>
 *
 * <p>Sem estado: um bean por conveniencia de injecao, seguro para uso
 * concorrente.
 */
@Component
public class SlotQueryMapper {

	/**
	 * O fuso do negocio. A barbearia fica em Macae/RJ e todo horario que o
	 * cliente ve e deste fuso.
	 *
	 * <p>{@code America/Sao_Paulo} e nao {@code UTC-3} fixo: o Brasil ja teve
	 * horario de verao e pode voltar a ter. Com o fuso nomeado a conversao
	 * acompanha a base tzdata; com o deslocamento fixo, uma eventual volta do
	 * DST deslocaria a agenda inteira em uma hora.
	 */
	public static final ZoneId FUSO_BRASILIA = ZoneId.of("America/Sao_Paulo");

	/**
	 * Duracao assumida quando o agendamento nao tem nenhum
	 * {@link BookedService} associado.
	 *
	 * <p>Esses agendamentos existem: sao os anteriores a tabela
	 * {@code appointment_services}, criados quando um agendamento tinha um
	 * unico servico referenciado direto por {@code service_id}. O Node assume
	 * 30 minutos para eles, e o backfill da V2 gravou 30 em
	 * {@code slot_duration_minutes} por isso. Trocar este numero desalinha o
	 * calculo do que a constraint de exclusao ja materializou.
	 */
	public static final int DURACAO_PADRAO_MINUTOS = 30;

	/** As colunas de hora sao TEXT {@code "HH:MM"} — sem segundos. */
	private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

	/** {@code blocked_slots.date} e TEXT {@code "YYYY-MM-DD"}. */
	private static final DateTimeFormatter DATA = DateTimeFormatter.ISO_LOCAL_DATE;

	/**
	 * A jornada do dia, pronta para o calculo.
	 *
	 * <p>O {@code dayOfWeek} e copiado do banco sem conversao: a entity ja
	 * guarda a convencao 0 = domingo, que e a mesma de
	 * {@link WorkingHours}. A conversao necessaria e a do outro lado — de
	 * {@link LocalDate} para o inteiro — e esta em
	 * {@link #dayOfWeekDoBanco(LocalDate)}.
	 */
	public WorkingHours toWorkingHours(BarberSchedule jornada) {
		Objects.requireNonNull(jornada, "jornada nao pode ser nula");
		return new WorkingHours(
				jornada.getDayOfWeek(),
				parseHora(jornada.getStartTime(), "professional_availability.startTime"),
				parseHora(jornada.getEndTime(), "professional_availability.endTime"));
	}

	/** Um bloqueio pontual, pronto para o calculo. */
	public TimeBlock toTimeBlock(ScheduleBlock bloqueio) {
		Objects.requireNonNull(bloqueio, "bloqueio nao pode ser nulo");
		return new TimeBlock(
				parseHora(bloqueio.getStartTime(), "blocked_slots.start_time"),
				parseHora(bloqueio.getEndTime(), "blocked_slots.end_time"));
	}

	public List<TimeBlock> toTimeBlocks(List<ScheduleBlock> bloqueios) {
		Objects.requireNonNull(bloqueios, "bloqueios nao pode ser nulo");
		List<TimeBlock> convertidos = new ArrayList<>(bloqueios.size());
		for (ScheduleBlock bloqueio : bloqueios) {
			convertidos.add(toTimeBlock(bloqueio));
		}
		return List.copyOf(convertidos);
	}

	/**
	 * Um agendamento existente, como bloco ocupado da agenda.
	 *
	 * <p>O inicio vem de {@code scheduled_at} convertido para hora de
	 * Brasilia; a duracao vem de {@link #slotDurationMinutes(Appointment)}.
	 */
	public BookedSlot toBookedSlot(Appointment agendamento) {
		Objects.requireNonNull(agendamento, "agendamento nao pode ser nulo");
		return new BookedSlot(
				toHoraDeBrasilia(agendamento.getScheduledAt()),
				slotDurationMinutes(agendamento));
	}

	public List<BookedSlot> toBookedSlots(List<Appointment> agendamentos) {
		Objects.requireNonNull(agendamentos, "agendamentos nao pode ser nulo");
		List<BookedSlot> convertidos = new ArrayList<>(agendamentos.size());
		for (Appointment agendamento : agendamentos) {
			convertidos.add(toBookedSlot(agendamento));
		}
		return List.copyOf(convertidos);
	}

	/**
	 * A duracao efetiva de um atendimento, em minutos: soma das duracoes dos
	 * seus {@link BookedService}, com teto de
	 * {@link SlotCalculator#MAX_SLOT_MINUTES}, ou
	 * {@link #DURACAO_PADRAO_MINUTOS} quando nao ha item nenhum.
	 *
	 * <p><b>Este metodo e a definicao unica dessa conta</b>, e e deliberado que
	 * ele seja publico: a mesma regra aparece no backfill da V2 e no
	 * preenchimento de {@code appointments.slot_duration_minutes} na escrita.
	 * Se as duas pontas divergirem, a tela oferece um horario que a constraint
	 * de exclusao vai recusar no commit — o pior tipo de inconsistencia,
	 * porque aparece como erro aleatorio na reserva.
	 *
	 * <p>O teto tambem e reaplicado pelo {@link SlotCalculator}, que trabalha
	 * com duracao bruta por principio. Aplicar duas vezes e inofensivo —
	 * {@code min} e idempotente — e cada camada continua correta sozinha: o
	 * calculo protege-se de um mapper futuro que esqueca o teto, e o mapper
	 * garante que a duracao que vai para o banco e a mesma que o indice usa.
	 */
	public int slotDurationMinutes(Appointment agendamento) {
		Objects.requireNonNull(agendamento, "agendamento nao pode ser nulo");
		List<BookedService> itens = agendamento.getBookedServices();
		if (itens == null || itens.isEmpty()) {
			return DURACAO_PADRAO_MINUTOS;
		}
		int soma = 0;
		for (BookedService item : itens) {
			soma += item.getDurationMinutes();
		}
		return Math.min(soma, SlotCalculator.MAX_SLOT_MINUTES);
	}

	/**
	 * {@code scheduled_at} (UTC) para a hora que o cliente ve.
	 *
	 * <p>Perde a data de proposito: o calculo trabalha dentro de um unico dia e
	 * so quer a hora. Quem precisa da data em Brasilia usa
	 * {@link #toDataDeBrasilia(Instant)}.
	 */
	public LocalTime toHoraDeBrasilia(Instant instante) {
		Objects.requireNonNull(instante, "instante nao pode ser nulo");
		return instante.atZone(FUSO_BRASILIA).toLocalTime();
	}

	/**
	 * {@code scheduled_at} (UTC) para a data em Brasilia.
	 *
	 * <p>Nao e a mesma data que o UTC mostra. 2026-03-10T02:00Z e ainda
	 * 2026-03-09 em Brasilia, e um agendamento da madrugada apareceria no dia
	 * seguinte se a conversao fosse ignorada.
	 */
	public LocalDate toDataDeBrasilia(Instant instante) {
		Objects.requireNonNull(instante, "instante nao pode ser nulo");
		return instante.atZone(FUSO_BRASILIA).toLocalDate();
	}

	/**
	 * Uma data e hora de Brasilia de volta para {@code Instant} UTC, pronto
	 * para a coluna. O caminho inverso de {@link #toHoraDeBrasilia(Instant)}.
	 */
	public Instant toInstantUtc(LocalDate data, LocalTime hora) {
		Objects.requireNonNull(data, "data nao pode ser nula");
		Objects.requireNonNull(hora, "hora nao pode ser nula");
		return data.atTime(hora).atZone(FUSO_BRASILIA).toInstant();
	}

	/**
	 * A hora no formato {@code "HH:MM"} que o frontend espera em cada slot de
	 * {@code available-times}.
	 *
	 * <p>Usa o mesmo {@code DateTimeFormatter} da leitura, entao ida e volta
	 * sao simetricas. {@link LocalTime#toString()} <i>nao</i> serve: ele omite
	 * os minutos quando sao zero em alguns casos e acrescenta segundos quando
	 * existem, e o frontend compara essas strings com as que recebeu.
	 */
	public String toHoraTexto(LocalTime hora) {
		Objects.requireNonNull(hora, "hora nao pode ser nula");
		return HORA.format(hora);
	}

	/**
	 * A data no formato em que {@code blocked_slots.date} a guarda:
	 * {@code "YYYY-MM-DD"}.
	 */
	public String toDateKey(LocalDate data) {
		Objects.requireNonNull(data, "data nao pode ser nula");
		return DATA.format(data);
	}

	/**
	 * O {@code dayOfWeek} na convencao do banco (0 = domingo ... 6 = sabado).
	 *
	 * <p>Delega a {@link WorkingHours#dayOfWeekOf(LocalDate)}. A conversao e
	 * uma linha e a tentacao de reescreve-la aqui e grande; nao reescrever e o
	 * ponto — uma segunda implementacao e uma segunda chance de errar por um
	 * dia, e esse erro nao levanta excecao, so devolve a agenda da terca
	 * quando o cliente pediu a da segunda.
	 */
	public int dayOfWeekDoBanco(LocalDate data) {
		return WorkingHours.dayOfWeekOf(data);
	}

	private static LocalTime parseHora(String valor, String coluna) {
		if (valor == null) {
			throw new IllegalStateException(coluna + " e NOT NULL no banco mas chegou nula");
		}
		try {
			return LocalTime.parse(valor.trim(), HORA);
		}
		catch (DateTimeException ex) {
			// O valor entra na mensagem: nao e PII, e sem ele a unica pista
			// seria "alguma linha de alguma tabela esta torta".
			throw new IllegalStateException(
					coluna + " deveria estar em \"HH:MM\" mas contem \"" + valor + "\"", ex);
		}
	}

}
