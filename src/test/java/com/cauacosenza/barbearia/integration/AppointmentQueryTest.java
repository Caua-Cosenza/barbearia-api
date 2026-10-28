package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.cauacosenza.barbearia.domain.entity.Appointment;
import com.cauacosenza.barbearia.domain.entity.Barber;
import com.cauacosenza.barbearia.domain.entity.Service;
import com.cauacosenza.barbearia.domain.enums.AppointmentStatus;
import com.cauacosenza.barbearia.domain.rules.BookedSlot;

/**
 * As consultas que alimentam o calculo de horarios livres.
 *
 * <p>Dois erros sao procurados aqui, e os dois dao a resposta errada em
 * silencio: incluir no calculo um agendamento que nao ocupa agenda (um dia que
 * parece cheio de atendimentos ja cancelados) e errar a inclusividade das
 * bordas do intervalo (o agendamento das 19:30 que desaparece, ou o da
 * meia-noite seguinte que aparece).
 */
class AppointmentQueryTest extends AbstractPostgresIntegrationTest {

	private static final LocalDate DIA = LocalDate.of(2026, 3, 10);

	// =======================================================================
	// Filtro de status
	// =======================================================================

	@Nested
	@DisplayName("so PENDING e CONFIRMED ocupam agenda")
	class FiltroDeStatus {

		@Test
		@DisplayName("PENDING e CONFIRMED aparecem; CANCELLED, COMPLETED e NO_SHOW nao")
		void apenasOsQueOcupam() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", 50);

			// Um agendamento por status, cada um em sua hora -- sobrepostos
			// seriam recusados pela constraint da V2.
			for (AppointmentStatus status : AppointmentStatus.values()) {
				LocalTime hora = LocalTime.of(9 + status.ordinal(), 0);
				gravar(novoAgendamento(barbeiro, servico, DIA, hora, status));
			}

			List<Appointment> ocupam = buscarODiaInteiro(barbeiro);

			assertThat(ocupam)
					.extracting(Appointment::getStatus)
					.containsExactlyInAnyOrder(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED);
		}

		@ParameterizedTest(name = "{0} nao ocupa agenda")
		@EnumSource(value = AppointmentStatus.class, names = { "CANCELLED", "COMPLETED", "NO_SHOW" })
		void statusQueNaoOcupamSaoInvisiveis(AppointmentStatus status) {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", 50);
			gravar(novoAgendamento(barbeiro, servico, DIA, LocalTime.of(10, 0), status));

			assertThat(buscarODiaInteiro(barbeiro)).isEmpty();
			// E a confirmacao de que a linha existe no banco: o filtro e da
			// consulta, nao um agendamento que nao foi gravado.
			assertThat(appointments.count()).isEqualTo(1);
		}

		@ParameterizedTest(name = "{0} ocupa agenda")
		@EnumSource(value = AppointmentStatus.class, names = { "PENDING", "CONFIRMED" })
		void statusQueOcupamAparecem(AppointmentStatus status) {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", 50);
			gravar(novoAgendamento(barbeiro, servico, DIA, LocalTime.of(10, 0), status));

			assertThat(buscarODiaInteiro(barbeiro)).hasSize(1);
		}

		@Test
		@DisplayName("o filtro da consulta concorda com AppointmentStatus.ocupaAgenda()")
		void consultaConcordaComODominio() {
			// A regra vive em tres lugares (dominio, consulta, WHERE da
			// constraint). Esta asercao amarra os dois primeiros.
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", 50);
			for (AppointmentStatus status : AppointmentStatus.values()) {
				gravar(novoAgendamento(barbeiro, servico, DIA, LocalTime.of(9 + status.ordinal(), 0), status));
			}

			assertThat(buscarODiaInteiro(barbeiro))
					.allSatisfy(agendamento -> assertThat(agendamento.getStatus().ocupaAgenda()).isTrue())
					.hasSize((int) java.util.Arrays.stream(AppointmentStatus.values())
							.filter(AppointmentStatus::ocupaAgenda).count());
		}

		@Test
		@DisplayName("a agenda de um profissional nao vaza para a do outro")
		void filtraPorProfissional() {
			Barber jhonatan = novoBarbeiro("Jhonatan");
			Barber outro = novoBarbeiro("Outro");
			Service servico = novoServico("Corte", 50);

			gravar(novoAgendamento(jhonatan, servico, DIA, LocalTime.of(10, 0), AppointmentStatus.PENDING));
			gravar(novoAgendamento(outro, servico, DIA, LocalTime.of(10, 0), AppointmentStatus.PENDING));

			assertThat(buscarODiaInteiro(jhonatan)).hasSize(1);
			assertThat(buscarODiaInteiro(outro)).hasSize(1);
		}

	}

	// =======================================================================
	// Bordas do intervalo
	// =======================================================================

	@Nested
	@DisplayName("o intervalo e [inicio, fim) — inicio inclusivo, fim exclusivo")
	class Bordas {

		@Test
		@DisplayName("o agendamento exatamente no inicio entra")
		void inicioEhInclusivo() {
			Barber barbeiro = comAgendamentoAs(LocalTime.of(8, 40));

			assertThat(appointments.buscarQueOcupamAgenda(barbeiro.getId(),
					instante(LocalTime.of(8, 40)), instante(LocalTime.of(19, 30)))).hasSize(1);
		}

		@Test
		@DisplayName("o agendamento exatamente no fim fica fora")
		void fimEhExclusivo() {
			Barber barbeiro = comAgendamentoAs(LocalTime.of(19, 30));

			assertThat(appointments.buscarQueOcupamAgenda(barbeiro.getId(),
					instante(LocalTime.of(8, 40)), instante(LocalTime.of(19, 30)))).isEmpty();
		}

		@Test
		@DisplayName("um milissegundo antes do fim entra")
		void umMilissegundoAntesDoFimEntra() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", 50);
			Appointment agendamento = novoAgendamentoUtc(barbeiro, servico,
					instante(LocalTime.of(19, 30)).minusMillis(1), 50, AppointmentStatus.PENDING);
			gravar(agendamento);

			assertThat(appointments.buscarQueOcupamAgenda(barbeiro.getId(),
					instante(LocalTime.of(8, 40)), instante(LocalTime.of(19, 30)))).hasSize(1);
		}

		@Test
		@DisplayName("um milissegundo antes do inicio fica fora")
		void umMilissegundoAntesDoInicioFicaFora() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", 50);
			gravar(novoAgendamentoUtc(barbeiro, servico,
					instante(LocalTime.of(8, 40)).minusMillis(1), 50, AppointmentStatus.PENDING));

			assertThat(appointments.buscarQueOcupamAgenda(barbeiro.getId(),
					instante(LocalTime.of(8, 40)), instante(LocalTime.of(19, 30)))).isEmpty();
		}

		@Test
		@DisplayName("o dia inteiro em Brasilia e [00:00 de hoje, 00:00 de amanha)")
		void diaInteiroNaoPegaOVizinho() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", 50);

			// 23:30 do dia anterior, 09:00 do dia, 00:00 do dia seguinte.
			gravar(novoAgendamentoUtc(barbeiro, servico,
					mapper.toInstantUtc(DIA.minusDays(1), LocalTime.of(23, 30)), 50, AppointmentStatus.PENDING));
			gravar(novoAgendamento(barbeiro, servico, DIA, LocalTime.of(9, 0), AppointmentStatus.PENDING));
			gravar(novoAgendamentoUtc(barbeiro, servico,
					mapper.toInstantUtc(DIA.plusDays(1), LocalTime.MIDNIGHT), 50, AppointmentStatus.PENDING));

			List<Appointment> doDia = buscarODiaInteiro(barbeiro);

			assertThat(doDia).hasSize(1);
			assertThat(mapper.toHoraDeBrasilia(doDia.get(0).getScheduledAt())).isEqualTo(LocalTime.of(9, 0));
		}

		@Test
		@DisplayName("o resultado vem ordenado por scheduled_at")
		void resultadoOrdenado() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", 50);

			// Gravados fora de ordem de proposito.
			gravar(novoAgendamento(barbeiro, servico, DIA, LocalTime.of(15, 0), AppointmentStatus.PENDING));
			gravar(novoAgendamento(barbeiro, servico, DIA, LocalTime.of(9, 0), AppointmentStatus.CONFIRMED));
			gravar(novoAgendamento(barbeiro, servico, DIA, LocalTime.of(12, 0), AppointmentStatus.PENDING));

			assertThat(buscarODiaInteiro(barbeiro))
					.extracting(agendamento -> mapper.toHoraDeBrasilia(agendamento.getScheduledAt()))
					.containsExactly(LocalTime.of(9, 0), LocalTime.of(12, 0), LocalTime.of(15, 0));
		}

	}

	// =======================================================================
	// A consulta alimentando o mapper
	// =======================================================================

	@Nested
	@DisplayName("consulta + mapper: o que chega ao SlotCalculator")
	class ConsultaAlimentandoOCalculo {

		@Test
		@DisplayName("os itens vem carregados no mesmo SELECT, e a duracao sai somada e capada")
		void itensVemNoFetchEDuracaoSaiCapada() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service corte = novoServico("Corte", 50);
			Service pigmentacao = novoServico("Pigmentacao", 10);

			Appointment comDoisItens = novoAgendamento(
					barbeiro, corte, DIA, LocalTime.of(9, 0), AppointmentStatus.PENDING);
			comDoisItens.adicionarServico(item(corte, 50));
			comDoisItens.adicionarServico(item(pigmentacao, 10));
			gravar(comDoisItens);

			Appointment semItens = novoAgendamento(
					barbeiro, corte, DIA, LocalTime.of(15, 0), AppointmentStatus.CONFIRMED);
			gravar(semItens);

			// Fora de transacao: se os itens nao viessem no join fetch, a soma
			// levantaria LazyInitializationException em vez de dar 50.
			List<BookedSlot> ocupados = mapper.toBookedSlots(buscarODiaInteiro(barbeiro));

			assertThat(ocupados).containsExactly(
					new BookedSlot(LocalTime.of(9, 0), 50),
					new BookedSlot(LocalTime.of(15, 0), 30));
		}

		@Test
		@DisplayName("o join dos itens nao duplica o agendamento no resultado")
		void joinNaoDuplica() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", 10);

			Appointment comTresItens = novoAgendamento(
					barbeiro, servico, DIA, LocalTime.of(9, 0), AppointmentStatus.PENDING);
			comTresItens.adicionarServico(item(servico, 10));
			comTresItens.adicionarServico(item(servico, 10));
			comTresItens.adicionarServico(item(servico, 10));
			gravar(comTresItens);

			assertThat(buscarODiaInteiro(barbeiro)).hasSize(1);
		}

		@Test
		@DisplayName("slot_duration_minutes gravado bate com o que o mapper recalcula")
		void colunaMaterializadaBateComOMapper() {
			// As duas pontas da mesma regra: o que esta na coluna (e que a
			// constraint de exclusao usa) e o que o calculo le.
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service corte = novoServico("Corte", 50);
			Service pigmentacao = novoServico("Pigmentacao", 10);

			Appointment agendamento = novoAgendamento(
					barbeiro, corte, DIA, LocalTime.of(9, 0), AppointmentStatus.PENDING);
			agendamento.adicionarServico(item(corte, 50));
			agendamento.adicionarServico(item(pigmentacao, 10));
			String id = gravar(agendamento).getId();

			int naColuna = jdbc.queryForObject(
					"SELECT \"slot_duration_minutes\" FROM \"appointments\" WHERE \"id\" = ?",
					Integer.class, id);

			assertThat(naColuna).isEqualTo(50);
			// Pela consulta com join fetch, nao por findById: fora de transacao
			// os itens de um findById sao uma colecao lazy sem sessao, e o
			// mapper levantaria LazyInitializationException. E exatamente por
			// isso que o fetch esta na consulta do calculo.
			assertThat(buscarODiaInteiro(barbeiro))
					.singleElement()
					.satisfies(lido -> assertThat(mapper.slotDurationMinutes(lido)).isEqualTo(naColuna));
			assertThat(id).isNotBlank();
		}

	}

	// =======================================================================
	// cancel_token
	// =======================================================================

	@Nested
	@DisplayName("busca por cancel_token")
	class PorCancelToken {

		@Test
		@DisplayName("encontra o agendamento pelo token do link de cancelamento")
		void encontraPeloToken() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", 50);
			Appointment agendamento = novoAgendamento(
					barbeiro, servico, DIA, LocalTime.of(9, 0), AppointmentStatus.PENDING);
			agendamento.setCancelToken("token-abc-123");
			String id = gravar(agendamento).getId();

			assertThat(appointments.findByCancelToken("token-abc-123"))
					.get()
					.extracting(Appointment::getId)
					.isEqualTo(id);
		}

		@Test
		@DisplayName("token desconhecido devolve vazio")
		void tokenDesconhecido() {
			assertThat(appointments.findByCancelToken("nao-existe")).isEmpty();
		}

		@Test
		@DisplayName("encontra tambem um CANCELLED: cancelar e idempotente, nao filtra status")
		void achaCanceladoTambem() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", 50);
			Appointment agendamento = novoAgendamento(
					barbeiro, servico, DIA, LocalTime.of(9, 0), AppointmentStatus.CANCELLED);
			agendamento.setCancelToken("token-ja-cancelado");
			agendamento.setCancelledAt(Instant.parse("2026-03-09T12:00:00Z"));
			gravar(agendamento);

			// Diferente de buscarQueOcupamAgenda: aqui nao se filtra status, ou
			// o cliente que clica duas vezes no link receberia "nao encontrado"
			// em vez de "ja cancelado".
			assertThat(appointments.findByCancelToken("token-ja-cancelado")).isPresent();
		}

	}

	// =======================================================================
	// Apoio
	// =======================================================================

	/** O dia inteiro em hora de Brasilia, no intervalo que o repositorio espera. */
	private List<Appointment> buscarODiaInteiro(Barber barbeiro) {
		return appointments.buscarQueOcupamAgenda(
				barbeiro.getId(),
				mapper.toInstantUtc(DIA, LocalTime.MIDNIGHT),
				mapper.toInstantUtc(DIA.plusDays(1), LocalTime.MIDNIGHT));
	}

	private Instant instante(LocalTime hora) {
		return mapper.toInstantUtc(DIA, hora);
	}

	private Barber comAgendamentoAs(LocalTime hora) {
		Barber barbeiro = novoBarbeiro("Jhonatan");
		Service servico = novoServico("Corte", 50);
		gravar(novoAgendamento(barbeiro, servico, DIA, hora, AppointmentStatus.PENDING));
		return barbeiro;
	}

}
