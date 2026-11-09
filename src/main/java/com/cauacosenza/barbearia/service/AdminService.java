package com.cauacosenza.barbearia.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cauacosenza.barbearia.domain.entity.Appointment;
import com.cauacosenza.barbearia.domain.enums.AppointmentStatus;
import com.cauacosenza.barbearia.domain.exception.RecursoNaoEncontradoException;
import com.cauacosenza.barbearia.domain.exception.TransicaoDeStatusInvalidaException;
import com.cauacosenza.barbearia.dto.request.UpdateAppointmentStatusRequest;
import com.cauacosenza.barbearia.dto.response.AdminAppointmentResponse;
import com.cauacosenza.barbearia.dto.response.AdminStatsResponse;
import com.cauacosenza.barbearia.dto.response.UpdateAppointmentStatusResponse;
import com.cauacosenza.barbearia.repository.AppointmentRepository;

/**
 * O que o painel do dono precisa: contadores, agenda do dia e mudanca de
 * status.
 *
 * <h2>Todo recorte de data e em Brasilia</h2>
 *
 * <p>Nunca em UTC. Uma versao anterior do Node usava {@code toISOString()}
 * para achar "hoje" e, depois das 21h — quando em UTC ja e o dia seguinte —
 * passava a trabalhar com a data errada: bloqueios legitimos eram recusados
 * por "estar no passado" e os contadores pulavam de dia antes da hora.
 *
 * <p>O dia do painel vai de {@code 00:00:00.000} a {@code 23:59:59.999} em
 * Brasilia, e os dois limites sao <b>inclusivos</b> — o Node compara com
 * {@code gte}/{@code lte} contra um fim de dia em {@code .999}. Nao e o
 * {@code [inicio, fim)} usado no calculo de horarios, e a diferenca importa
 * para os numeros baterem.
 *
 * <h2>A semana vai de terca a sabado</h2>
 *
 * <p>Nao e domingo a sabado: e a semana de trabalho da barbearia. Segunda e
 * domingo nao se atende.
 *
 * <p>Consequencia que parece bug e nao e: <b>consultado num domingo ou numa
 * segunda, o contador mostra a semana que acabou de terminar</b>. O calculo
 * anda para tras ate a terca mais recente, e nesses dois dias ela pertence a
 * semana anterior. E o comportamento do Node, e faz sentido para quem olha o
 * painel na folga querendo saber como foi a semana.
 */
@Service
public class AdminService {

	/** O fim do dia que o Node monta: {@code 23:59:59.999}. */
	private static final LocalTime FIM_DO_DIA = LocalTime.of(23, 59, 59, 999_000_000);

	/**
	 * Os status que contam como atendimento nos tres primeiros contadores.
	 *
	 * <p>Cancelado e no-show ficam de fora: nao houve corte. Note que
	 * {@code PENDING} <b>entra</b> — o contador de hoje responde "quantas
	 * pessoas tenho na agenda", nao "quantas ja atendi".
	 */
	private static final Set<AppointmentStatus> CONTAM_COMO_ATENDIMENTO = Set.of(
			AppointmentStatus.PENDING,
			AppointmentStatus.CONFIRMED,
			AppointmentStatus.COMPLETED);

	private final AppointmentRepository appointments;

	private final SlotQueryMapper mapper;

	private final Clock clock;

	public AdminService(AppointmentRepository appointments, SlotQueryMapper mapper, Clock clock) {
		this.appointments = appointments;
		this.mapper = mapper;
		this.clock = clock;
	}

	/**
	 * A agenda de um dia, <b>com todos os status</b>.
	 *
	 * <p>Sem filtro de propósito, ao contrario da consulta que alimenta o
	 * calculo de horarios: o dono quer ver tambem o que foi cancelado e quem
	 * nao apareceu. E o historico do dia, nao a ocupacao da agenda.
	 */
	@Transactional(readOnly = true)
	public List<AdminAppointmentResponse> agendaDoDia(LocalDate dia) {
		return this.appointments
				.buscarDoPeriodoParaOPainel(inicioEmUtc(dia), fimEmUtc(dia))
				.stream()
				.map(AdminAppointmentResponse::de)
				.toList();
	}

	/**
	 * Muda o status de um agendamento.
	 *
	 * <p>Cancelar aqui <b>libera o horario sozinho</b>: o {@code WHERE}
	 * parcial da constraint de exclusao da V2 so enxerga {@code PENDING} e
	 * {@code CONFIRMED}, entao a linha sai do indice no mesmo UPDATE e o
	 * horario volta a aparecer em {@code available-times}. Nao ha nada a
	 * limpar.
	 *
	 * <p>{@code cancelled_at} nao e preenchido aqui, e isso acompanha o Node —
	 * o {@code adminService.updateAppointmentStatus} grava so o status. A
	 * coluna fica reservada ao cancelamento pelo link do cliente.
	 *
	 * <h2>Cancelar aqui NAO avisa o cliente — decisao, nao esquecimento</h2>
	 *
	 * <p>Nao ha evento publicado neste metodo, de proposito. As tres mensagens
	 * de WhatsApp existentes avisam o cliente na criacao e o barbeiro na criacao
	 * e no cancelamento pelo link; uma quarta, avisando o cliente quando o
	 * Jhonatan cancela pelo painel, foi desenhada, revisada e <b>descartada</b>:
	 * ele prefere avisar pessoalmente. Cancelar um horario ja marcado e
	 * justamente a situacao em que ele quer explicar o motivo, oferecer outro
	 * dia e ouvir a resposta — um aviso automatico chegaria antes dele e
	 * tiraria essa conversa.
	 *
	 * <p>Nao e regressao da migracao: o {@code adminService.ts} do Node tambem
	 * nao avisa ninguem neste caminho.
	 *
	 * <p>Daqui sai uma consequencia que parece bug e nao e: este metodo
	 * <b>nao</b> apaga o {@code cancel_token}, ao contrario do
	 * {@link CancelService#cancelar(String)}. Sem a mensagem, o link que o
	 * cliente ja tem e a unica forma de ele descobrir que o horario caiu; fechar
	 * o token aqui o deixaria sem nenhum caminho. A expiracao de leitura de sete
	 * dias ja limita a janela. As duas coisas dependem uma da outra: quem
	 * implementar a mensagem pode fechar o token; quem fechar o token sem a
	 * mensagem piora a situacao. Ver README, "Cancelamento pelo painel NAO avisa
	 * o cliente".
	 */
	@Transactional
	public UpdateAppointmentStatusResponse mudarStatus(String id, UpdateAppointmentStatusRequest pedido) {
		Appointment agendamento = this.appointments.findById(id)
				.orElseThrow(RecursoNaoEncontradoException::agendamento);

		// COMPLETED e NO_SHOW dizem, os dois, que o atendimento ja terminou --
		// um porque aconteceu, outro porque o cliente nao veio. Nenhum dos
		// dois esta em OCUPAM_AGENDA, entao aplicar qualquer um deles a um
		// atendimento FUTURO devolve o horario para a grade e o vende a um
		// segundo cliente. Os dois aparecem na porta no mesmo minuto, e o erro
		// de um clique no painel vira overbooking silencioso.
		//
		// CANCELLED e a excecao proposital: cancelar antes da hora e a
		// operacao legitima, e liberar o horario e justamente o ponto.
		if (agendamento.getScheduledAt().isAfter(Instant.now(this.clock))) {
			if (pedido.status() == AppointmentStatus.COMPLETED) {
				throw TransicaoDeStatusInvalidaException.conclusaoAntesDoHorario();
			}
			if (pedido.status() == AppointmentStatus.NO_SHOW) {
				throw TransicaoDeStatusInvalidaException.faltaAntesDoHorario();
			}
		}

		agendamento.setStatus(pedido.status());

		// O carimbo do fim real. Vai a nulo quando o status sai de COMPLETED
		// porque um completed_at remanescente continuaria ancorando o vao
		// seguinte num atendimento que deixou de estar concluido.
		if (pedido.status() == AppointmentStatus.COMPLETED) {
			agendamento.setCompletedAt(Instant.now(this.clock));
		}
		else {
			agendamento.setCompletedAt(null);
		}

		Appointment salvo = this.appointments.saveAndFlush(agendamento);

		return new UpdateAppointmentStatusResponse(salvo.getId(), salvo.getStatus().name());
	}

	/** Os quatro contadores do topo do painel. */
	@Transactional(readOnly = true)
	public AdminStatsResponse estatisticas() {
		LocalDate hoje = hojeEmBrasilia();

		LocalDate inicioDaSemana = tercaDaSemanaDe(hoje);
		LocalDate fimDaSemana = inicioDaSemana.plusDays(4);

		LocalDate primeiroDoMes = hoje.withDayOfMonth(1);
		LocalDate ultimoDoMes = hoje.withDayOfMonth(hoje.lengthOfMonth());

		return new AdminStatsResponse(
				contar(hoje, hoje),
				contar(inicioDaSemana, fimDaSemana),
				contar(primeiroDoMes, ultimoDoMes),
				// Acumulado desde sempre, sem recorte. Ver o javadoc de
				// AppointmentRepository.countByStatus e o README.
				this.appointments.countByStatus(AppointmentStatus.NO_SHOW));
	}

	/** Hoje em Brasilia — nunca {@code LocalDate.now()} do fuso da JVM. */
	public LocalDate hojeEmBrasilia() {
		return this.mapper.toDataDeBrasilia(Instant.now(this.clock));
	}

	private long contar(LocalDate de, LocalDate ate) {
		return this.appointments.countByScheduledAtBetweenAndStatusIn(
				inicioEmUtc(de), fimEmUtc(ate), CONTAM_COMO_ATENDIMENTO);
	}

	/**
	 * A terca-feira da semana de trabalho a que {@code dia} pertence.
	 *
	 * <p>Anda para tras ate a terca mais recente. Em domingo e segunda isso
	 * cai na terca da semana anterior, e e assim que o Node se comporta — ver
	 * o javadoc da classe.
	 */
	private static LocalDate tercaDaSemanaDe(LocalDate dia) {
		// DayOfWeek do Java: segunda=1 ... domingo=7. Terca=2.
		int diasDesdeTerca = Math.floorMod(dia.getDayOfWeek().getValue() - DayOfWeek.TUESDAY.getValue(), 7);
		return dia.minusDays(diasDesdeTerca);
	}

	private Instant inicioEmUtc(LocalDate dia) {
		return this.mapper.toInstantUtc(dia, LocalTime.MIDNIGHT);
	}

	/** {@code 23:59:59.999} em Brasilia, limite <b>inclusivo</b>. */
	private Instant fimEmUtc(LocalDate dia) {
		return this.mapper.toInstantUtc(dia, FIM_DO_DIA);
	}

}
