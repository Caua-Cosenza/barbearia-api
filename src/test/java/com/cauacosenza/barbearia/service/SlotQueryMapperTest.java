package com.cauacosenza.barbearia.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.cauacosenza.barbearia.domain.entity.Appointment;
import com.cauacosenza.barbearia.domain.entity.BarberSchedule;
import com.cauacosenza.barbearia.domain.entity.BookedService;
import com.cauacosenza.barbearia.domain.entity.ScheduleBlock;
import com.cauacosenza.barbearia.domain.entity.Service;
import com.cauacosenza.barbearia.domain.rules.BookedSlot;
import com.cauacosenza.barbearia.domain.rules.SlotCalculator;
import com.cauacosenza.barbearia.domain.rules.TimeBlock;
import com.cauacosenza.barbearia.domain.rules.WorkingHours;

/**
 * Teste puro do mapper: sem contexto Spring e sem banco.
 *
 * <p>As entities JPA sao POJOs com setter, e o mapper nao tem estado nem
 * dependencia — da para exercitar as quatro conversoes com a aritmetica toda a
 * vista. Os erros que este teste procura (um dia de diferenca na semana, tres
 * horas de diferenca no fuso) sao silenciosos: nao levantam excecao, so
 * devolvem a resposta errada.
 */
class SlotQueryMapperTest {

	private final SlotQueryMapper mapper = new SlotQueryMapper();

	// =======================================================================
	// Dia da semana
	// =======================================================================

	@Nested
	@DisplayName("dayOfWeek: convencao do Java (1=segunda..7=domingo) -> banco (0=domingo..6=sabado)")
	class DiaDaSemana {

		/**
		 * Os sete dias, com datas reais de uma semana completa. 2026-03-01 e um
		 * domingo; a semana segue ate 2026-03-07, sabado.
		 */
		@ParameterizedTest(name = "{0} -> dayOfWeek {1}")
		@CsvSource({
				"2026-03-01, 0",
				"2026-03-02, 1",
				"2026-03-03, 2",
				"2026-03-04, 3",
				"2026-03-05, 4",
				"2026-03-06, 5",
				"2026-03-07, 6"
		})
		void osSeteDiasConvertemCerto(LocalDate data, int esperado) {
			assertThat(mapper.dayOfWeekDoBanco(data)).isEqualTo(esperado);
		}

		@Test
		@DisplayName("domingo e o caso que o modulo 7 resolve: Java diz 7, o banco espera 0")
		void domingoViraZero() {
			LocalDate domingo = LocalDate.of(2026, 3, 1);
			assertThat(domingo.getDayOfWeek().getValue()).isEqualTo(7);
			assertThat(mapper.dayOfWeekDoBanco(domingo)).isZero();
		}

		@Test
		@DisplayName("nenhuma data cai fora de 0..6")
		void sempreNaFaixaDoBanco() {
			LocalDate data = LocalDate.of(2026, 1, 1);
			for (int i = 0; i < 400; i++) {
				assertThat(mapper.dayOfWeekDoBanco(data.plusDays(i))).isBetween(0, 6);
			}
		}

		@Test
		@DisplayName("concorda com WorkingHours.dayOfWeekOf, que e a implementacao delegada")
		void concordaComODominio() {
			LocalDate data = LocalDate.of(2026, 3, 1);
			for (int i = 0; i < 70; i++) {
				LocalDate dia = data.plusDays(i);
				assertThat(mapper.dayOfWeekDoBanco(dia)).isEqualTo(WorkingHours.dayOfWeekOf(dia));
			}
		}

	}

	// =======================================================================
	// Fuso
	// =======================================================================

	@Nested
	@DisplayName("scheduled_at em UTC -> hora de Brasilia")
	class Fuso {

		@ParameterizedTest(name = "{0} -> {1} em Brasilia")
		@CsvSource({
				// Brasilia esta em UTC-3 desde o fim do horario de verao (2019).
				"2026-03-10T12:00:00Z, 09:00",
				"2026-03-10T11:40:00Z, 08:40",
				"2026-03-10T22:30:00Z, 19:30",
				// A virada do dia: 03:00 UTC e meia-noite em Brasilia.
				"2026-03-10T03:00:00Z, 00:00",
				// E antes disso ainda e o dia anterior.
				"2026-03-10T02:00:00Z, 23:00",
				"2026-03-10T00:30:00Z, 21:30"
		})
		void horaConverteComDeslocamentoDeTresHoras(Instant utc, LocalTime esperado) {
			assertThat(mapper.toHoraDeBrasilia(utc)).isEqualTo(esperado);
		}

		@Test
		@DisplayName("03:00 UTC e 00:00 do MESMO dia; 02:00 UTC e 23:00 do dia ANTERIOR")
		void viradaDeDiaMudaAData() {
			assertThat(mapper.toDataDeBrasilia(Instant.parse("2026-03-10T03:00:00Z")))
					.isEqualTo(LocalDate.of(2026, 3, 10));
			assertThat(mapper.toDataDeBrasilia(Instant.parse("2026-03-10T02:59:59Z")))
					.isEqualTo(LocalDate.of(2026, 3, 9));
			assertThat(mapper.toHoraDeBrasilia(Instant.parse("2026-03-10T02:59:59Z")))
					.isEqualTo(LocalTime.of(23, 59, 59));
		}

		@Test
		@DisplayName("ida e volta: Brasilia -> UTC -> Brasilia nao escorrega")
		void idaEVoltaEEstavel() {
			LocalDate dia = LocalDate.of(2026, 7, 15);
			for (int minuto = 0; minuto < 24 * 60; minuto++) {
				LocalTime hora = LocalTime.MIDNIGHT.plusMinutes(minuto);
				Instant utc = mapper.toInstantUtc(dia, hora);
				assertThat(mapper.toHoraDeBrasilia(utc)).isEqualTo(hora);
				assertThat(mapper.toDataDeBrasilia(utc)).isEqualTo(dia);
			}
		}

		@Test
		@DisplayName("um horario de Brasilia vira um Instant tres horas adiante em UTC")
		void conversaoParaUtcSomaTresHoras() {
			Instant utc = mapper.toInstantUtc(LocalDate.of(2026, 3, 10), LocalTime.of(8, 40));
			assertThat(utc).isEqualTo(Instant.parse("2026-03-10T11:40:00Z"));
		}

		@Test
		@DisplayName("a madrugada de Brasilia cai no dia seguinte em UTC")
		void madrugadaAtravessaOMeioDaNoiteEmUtc() {
			Instant utc = mapper.toInstantUtc(LocalDate.of(2026, 3, 10), LocalTime.of(22, 0));
			assertThat(utc).isEqualTo(Instant.parse("2026-03-11T01:00:00Z"));
		}

		@Test
		@DisplayName("o fuso e nomeado, nao um deslocamento fixo")
		void fusoENomeado() {
			// Se alguem trocar por ZoneOffset.ofHours(-3), esta asercao cai -- e
			// e o aviso de que uma volta do horario de verao deslocaria a agenda.
			assertThat(SlotQueryMapper.FUSO_BRASILIA.getId()).isEqualTo("America/Sao_Paulo");
		}

	}

	// =======================================================================
	// Duracao: teto e fallback
	// =======================================================================

	@Nested
	@DisplayName("slotDurationMinutes: soma dos itens, teto de 50, fallback de 30")
	class Duracao {

		@Test
		@DisplayName("um item: a propria duracao")
		void umItem() {
			assertThat(mapper.slotDurationMinutes(agendamentoCom(30))).isEqualTo(30);
		}

		@Test
		@DisplayName("varios itens: a soma")
		void somaDosItens() {
			assertThat(mapper.slotDurationMinutes(agendamentoCom(20, 15))).isEqualTo(35);
		}

		@Test
		@DisplayName("a soma que da exatamente 50 passa inteira")
		void somaNoLimite() {
			assertThat(mapper.slotDurationMinutes(agendamentoCom(40, 10))).isEqualTo(50);
		}

		@Test
		@DisplayName("corte de 50 + pigmentacao de 10 somam 60 e ocupam 50 — a regra do teto")
		void tetoDeCinquenta() {
			assertThat(mapper.slotDurationMinutes(agendamentoCom(50, 10)))
					.isEqualTo(SlotCalculator.MAX_SLOT_MINUTES)
					.isEqualTo(50);
		}

		@Test
		@DisplayName("soma muito acima do teto tambem para em 50")
		void tetoAguentaSomaGrande() {
			assertThat(mapper.slotDurationMinutes(agendamentoCom(50, 50, 50, 50))).isEqualTo(50);
		}

		@Test
		@DisplayName("sem nenhum item: 30 minutos, nao zero")
		void fallbackDeTrinta() {
			Appointment semItens = new Appointment();
			assertThat(semItens.getBookedServices()).isEmpty();
			assertThat(mapper.slotDurationMinutes(semItens))
					.isEqualTo(SlotQueryMapper.DURACAO_PADRAO_MINUTOS)
					.isEqualTo(30);
		}

		@Test
		@DisplayName("lista nula (associacao nunca carregada) tambem cai no fallback")
		void listaNulaCaiNoFallback() {
			Appointment agendamento = new Appointment();
			agendamento.setBookedServices(null);
			assertThat(mapper.slotDurationMinutes(agendamento)).isEqualTo(30);
		}

		@Test
		@DisplayName("um item de duracao zero NAO e o mesmo que nenhum item")
		void itemZeradoNaoEFallback() {
			// Caso de borda que o fallback nao deve capturar: a lista tem item, a
			// soma e 0. Trocar isEmpty() por soma == 0 no mapper quebraria aqui.
			assertThat(mapper.slotDurationMinutes(agendamentoCom(0))).isZero();
		}

	}

	// =======================================================================
	// Entity -> record
	// =======================================================================

	@Nested
	@DisplayName("entities viram os records de domain/rules")
	class Conversoes {

		@Test
		@DisplayName("BarberSchedule -> WorkingHours, com as horas em TEXT parseadas")
		void jornada() {
			BarberSchedule entity = new BarberSchedule();
			entity.setDayOfWeek(2);
			entity.setStartTime("08:40");
			entity.setEndTime("19:30");

			WorkingHours jornada = mapper.toWorkingHours(entity);

			assertThat(jornada.dayOfWeek()).isEqualTo(2);
			assertThat(jornada.startsAt()).isEqualTo(LocalTime.of(8, 40));
			assertThat(jornada.endsAt()).isEqualTo(LocalTime.of(19, 30));
		}

		@Test
		@DisplayName("ScheduleBlock -> TimeBlock")
		void bloqueioPontual() {
			TimeBlock bloco = mapper.toTimeBlock(bloqueio("12:00", "13:00"));

			assertThat(bloco.startsAt()).isEqualTo(LocalTime.NOON);
			assertThat(bloco.endsAt()).isEqualTo(LocalTime.of(13, 0));
		}

		@Test
		@DisplayName("lista de bloqueios converte inteira e volta imutavel")
		void listaDeBloqueios() {
			List<TimeBlock> blocos = mapper.toTimeBlocks(
					List.of(bloqueio("12:00", "13:00"), bloqueio("15:00", "15:30")));

			assertThat(blocos).containsExactly(
					new TimeBlock(LocalTime.of(12, 0), LocalTime.of(13, 0)),
					new TimeBlock(LocalTime.of(15, 0), LocalTime.of(15, 30)));
			assertThatThrownBy(() -> blocos.add(null)).isInstanceOf(UnsupportedOperationException.class);
		}

		@Test
		@DisplayName("Appointment -> BookedSlot junta fuso e duracao")
		void agendamento() {
			Appointment entity = agendamentoCom(50, 10);
			entity.setScheduledAt(Instant.parse("2026-03-10T13:30:00Z"));

			BookedSlot ocupado = mapper.toBookedSlot(entity);

			assertThat(ocupado.startsAt()).isEqualTo(LocalTime.of(10, 30));
			assertThat(ocupado.durationMinutes()).isEqualTo(50);
		}

		@Test
		@DisplayName("lista de agendamentos converte inteira")
		void listaDeAgendamentos() {
			Appointment um = agendamentoCom(30);
			um.setScheduledAt(Instant.parse("2026-03-10T11:40:00Z"));
			Appointment outro = agendamentoCom(20, 20);
			outro.setScheduledAt(Instant.parse("2026-03-10T15:00:00Z"));

			assertThat(mapper.toBookedSlots(List.of(um, outro))).containsExactly(
					new BookedSlot(LocalTime.of(8, 40), 30),
					new BookedSlot(LocalTime.of(12, 0), 40));
		}

		@Test
		@DisplayName("toDateKey produz o YYYY-MM-DD que blocked_slots.date guarda")
		void chaveDeData() {
			assertThat(mapper.toDateKey(LocalDate.of(2026, 3, 9))).isEqualTo("2026-03-09");
			assertThat(mapper.toDateKey(LocalDate.of(2026, 12, 31))).isEqualTo("2026-12-31");
		}

	}

	// =======================================================================
	// Dado torto no banco
	// =======================================================================

	@Nested
	@DisplayName("hora em TEXT malformada falha alto, citando a coluna")
	class HoraInvalida {

		@ParameterizedTest(name = "\"{0}\"")
		@ValueSource(strings = { "", "8:40", "0840", "25:00", "08:70", "08:40:00", "manha", "08-40" })
		void recusaFormatoForaDeHhMm(String valor) {
			BarberSchedule entity = new BarberSchedule();
			entity.setDayOfWeek(1);
			entity.setStartTime(valor);
			entity.setEndTime("19:30");

			assertThatThrownBy(() -> mapper.toWorkingHours(entity))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("professional_availability.startTime")
					.hasMessageContaining(valor);
		}

		@Test
		@DisplayName("espaco nas pontas e tolerado: e sujeira, nao erro de formato")
		void espacoNasPontasPassa() {
			BarberSchedule entity = new BarberSchedule();
			entity.setDayOfWeek(1);
			entity.setStartTime(" 08:40 ");
			entity.setEndTime("19:30");

			assertThat(mapper.toWorkingHours(entity).startsAt()).isEqualTo(LocalTime.of(8, 40));
		}

		@Test
		@DisplayName("a mensagem nomeia a coluna de blocked_slots quando o erro vem de la")
		void mensagemNomeiaAColunaCerta() {
			assertThatThrownBy(() -> mapper.toTimeBlock(bloqueio("12:00", "treze horas")))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("blocked_slots.end_time");
		}

	}

	// =======================================================================
	// Fabricas
	// =======================================================================

	private static Appointment agendamentoCom(int... duracoes) {
		Service servico = new Service();
		servico.setName("Corte");
		servico.setAmountCents(5000);

		Appointment agendamento = new Appointment();
		agendamento.setScheduledAt(Instant.parse("2026-03-10T12:00:00Z"));
		for (int duracao : duracoes) {
			BookedService item = new BookedService();
			item.setService(servico);
			item.setAmountCents(servico.getAmountCents());
			item.setDurationMinutes(duracao);
			agendamento.adicionarServico(item);
		}
		return agendamento;
	}

	private static ScheduleBlock bloqueio(String inicio, String fim) {
		ScheduleBlock entity = new ScheduleBlock();
		entity.setDate("2026-03-10");
		entity.setStartTime(inicio);
		entity.setEndTime(fim);
		return entity;
	}

}
