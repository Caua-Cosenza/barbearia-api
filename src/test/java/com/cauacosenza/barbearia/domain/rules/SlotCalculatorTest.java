package com.cauacosenza.barbearia.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Testes do calculo de horarios disponiveis.
 *
 * <p>Os valores esperados nao foram deduzidos deste codigo: foram gerados
 * rodando a parte pura do {@code availableTimesController.ts} do Node (linhas
 * 96-149) em um script de referencia. Onde o Java divergir do Node, e o Java
 * que esta errado.
 *
 * <p>Teste puro — sem Spring, sem mock, sem banco. Roda em milissegundos.
 */
class SlotCalculatorTest {

	private final SlotCalculator calculator = new SlotCalculator();

	/** 4 de junho de 2026 — a data do incidente do slot das 10:00. */
	private static final LocalDate DATA = LocalDate.of(2026, 6, 4);

	private static final LocalTime ABERTURA = LocalTime.of(8, 40);

	private static final LocalTime FECHAMENTO = LocalTime.of(19, 30);

	// =======================================================================
	// Helpers
	// =======================================================================

	/** Jornada do Jhonatan, 08:40-19:30, sempre no dia da semana de {@link #DATA}. */
	private static WorkingHours jornada() {
		return jornada(ABERTURA, FECHAMENTO);
	}

	private static WorkingHours jornada(LocalTime abre, LocalTime fecha) {
		return new WorkingHours(WorkingHours.dayOfWeekOf(DATA), abre, fecha);
	}

	private static LocalTime h(String hhmm) {
		return LocalTime.parse(hhmm);
	}

	private static BookedSlot agendado(String hhmm, int duracaoBruta) {
		return new BookedSlot(h(hhmm), duracaoBruta);
	}

	private static TimeBlock bloqueio(String inicio, String fim) {
		return new TimeBlock(h(inicio), h(fim));
	}

	private List<LocalTime> calcular(WorkingHours jornada, List<BookedSlot> agendados,
			List<TimeBlock> bloqueios, int duracao, LocalTime agora) {
		return this.calculator.calcular(jornada, agendados, bloqueios,
				new SlotRequest(DATA, duracao), agora);
	}

	/** Atalho para o caso mais comum: jornada cheia, nada bloqueado, data futura. */
	private List<LocalTime> calcular(List<BookedSlot> agendados, int duracao) {
		return calcular(jornada(), agendados, List.of(), duracao, null);
	}

	private static List<LocalTime> horarios(String... hhmm) {
		return List.of(hhmm).stream().map(SlotCalculatorTest::h).toList();
	}

	// =======================================================================
	// O bug real
	// =======================================================================

	@Nested
	@DisplayName("O bug do slot das 10:00 (4 de junho)")
	class BugDoSlotDas10 {

		/**
		 * Agenda do dia do incidente: 09:00 de 50 min, 09:10 com Corte 50 +
		 * Pigmentacao 20 = 70 brutos, e 10:50 de 50 min.
		 */
		private List<BookedSlot> agendaDoIncidente() {
			return List.of(
					agendado("09:00", 50),
					agendado("09:10", 70),
					agendado("10:50", 50));
		}

		@Test
		@DisplayName("as 10:00 aparecem na lista")
		void dezHorasAparece() {
			List<LocalTime> livres = calcular(agendaDoIncidente(), 50);

			assertThat(livres).contains(h("10:00"));
		}

		@Test
		@DisplayName("a lista inteira bate com a do Node")
		void listaInteiraBateComONode() {
			List<LocalTime> livres = calcular(agendaDoIncidente(), 50);

			assertThat(livres).containsExactlyElementsOf(horarios(
					"10:00", "11:40", "12:30", "13:20", "14:10",
					"15:00", "15:50", "16:40", "17:30", "18:20"));
		}

		@Test
		@DisplayName("sem o cap na leitura, as 10:00 desapareceriam (reproduz o bug)")
		void semCapNaLeituraOSlotDesapareceria() {
			// Simula o bug trocando o agendamento de 70 brutos por um de 70
			// efetivos: dois agendados em sequencia que, juntos, ocupam de 09:10
			// a 10:20 sem passar pelo cap de um unico registro.
			List<BookedSlot> comBlocoDe70 = List.of(
					agendado("09:00", 50),
					agendado("09:10", 50),
					agendado("10:00", 20),
					agendado("10:50", 50));

			List<LocalTime> comBug = calcular(comBlocoDe70, 50);

			// O vao entre 10:20 e 10:50 tem 30 minutos: nao cabe um slot de 50.
			assertThat(comBug).doesNotContain(h("10:00"));
			// E a prova de que o cap e o que salva: com o cap, aparece.
			assertThat(calcular(agendaDoIncidente(), 50)).contains(h("10:00"));
		}
	}

	// =======================================================================
	// Cap de 50 minutos
	// =======================================================================

	@Nested
	@DisplayName("Cap de 50 minutos")
	class CapDe50 {

		@Test
		@DisplayName("servico de 50 + servico de 10 ocupa 50, nao 60")
		void somaAcimaDoTetoOcupa50() {
			// Bloco 09:00-09:50 (capado). Se somasse 60, o bloco iria a 10:00 e
			// o primeiro horario do vao seguinte seria 10:00.
			List<LocalTime> livres = calcular(List.of(agendado("09:00", 60)), 50);

			assertThat(livres).contains(h("09:50"));
			assertThat(livres).doesNotContain(h("10:00"));
		}

		@Test
		@DisplayName("servico de 15 + servico de 10 ocupa 25 (abaixo do teto, usa o real)")
		void somaAbaixoDoTetoUsaOValorReal() {
			List<LocalTime> livres = calcular(List.of(agendado("09:00", 25)), 50);

			assertThat(livres).startsWith(h("09:25"));
			assertThat(livres).containsExactlyElementsOf(horarios(
					"09:25", "10:15", "11:05", "11:55", "12:45", "13:35",
					"14:25", "15:15", "16:05", "16:55", "17:45", "18:35"));
		}

		@Test
		@DisplayName("servico unico de 50 ocupa 50")
		void servicoUnicoDe50Ocupa50() {
			List<LocalTime> livres = calcular(List.of(agendado("09:00", 50)), 50);

			assertThat(livres).contains(h("09:50"));
		}

		@Test
		@DisplayName("a duracao SOLICITADA tambem e capada: pedir 70 se comporta como 50")
		void duracaoSolicitadaTambemECapada() {
			assertThat(calcular(List.of(), 70)).isEqualTo(calcular(List.of(), 50));
		}

		@ParameterizedTest(name = "duracao bruta de {0} min ocupa {1} min")
		@CsvSource({ "60, 50", "70, 50", "999, 50", "50, 50", "25, 25", "10, 10" })
		@DisplayName("o bloco ocupado e sempre min(duracao, 50)")
		void blocoOcupadoESempreMin50(int bruta, int esperada) {
			// O primeiro horario do vao depois do agendamento revela o tamanho
			// real do bloco: 09:00 + duracaoEfetiva.
			List<LocalTime> livres = calcular(List.of(agendado("09:00", bruta)), 10);

			assertThat(livres).contains(h("09:00").plusMinutes(esperada));
		}
	}

	// =======================================================================
	// Bordas de colisao
	// =======================================================================

	@Nested
	@DisplayName("Bordas de colisao com bloqueio")
	class BordasDeColisao {

		/**
		 * Para cada candidato, a jornada abre exatamente no horario testado,
		 * de modo que ele seja o primeiro horario gerado. Assim o teste isola a
		 * colisao da geracao da grade.
		 */
		private List<LocalTime> comJornadaAbrindoEm(String candidato) {
			return calcular(jornada(h(candidato), FECHAMENTO), List.of(),
					List.of(bloqueio("13:00", "14:00")), 50, null);
		}

		@ParameterizedTest(name = "slot {0} esta LIVRE")
		@ValueSource(strings = { "12:10", "14:00" })
		@DisplayName("encostar no bloqueio nao colide")
		void encostarNaoColide(String candidato) {
			// 12:10 termina exatamente as 13:00; 14:00 comeca exatamente no fim.
			assertThat(comJornadaAbrindoEm(candidato)).contains(h(candidato));
		}

		@ParameterizedTest(name = "slot {0} esta OCUPADO")
		@ValueSource(strings = { "12:30", "13:40", "13:10" })
		@DisplayName("invadir o bloqueio colide")
		void invadirColide(String candidato) {
			assertThat(comJornadaAbrindoEm(candidato)).doesNotContain(h(candidato));
		}

		@Test
		@DisplayName("o bloqueio abre um buraco mas nao realinha os horarios seguintes")
		void bloqueioNaoRealinhaAGrade() {
			List<LocalTime> livres = calcular(jornada(), List.of(),
					List.of(bloqueio("13:00", "14:00")), 50, null);

			// 12:50 e 13:40 sairiam da grade 08:40+50k, mas colidem; os
			// seguintes continuam na MESMA grade (14:30, nao 14:00).
			assertThat(livres).containsExactlyElementsOf(horarios(
					"08:40", "09:30", "10:20", "11:10", "12:00",
					"14:30", "15:20", "16:10", "17:00", "17:50", "18:40"));
		}

		@Test
		@DisplayName("multiplos bloqueios sao todos aplicados")
		void multiplosBloqueios() {
			List<LocalTime> livres = calcular(jornada(), List.of(),
					List.of(bloqueio("09:00", "10:00"), bloqueio("17:00", "18:00")), 50, null);

			// 08:40 tambem cai: o slot 08:40-09:30 invade o bloqueio das 09:00.
			// Encostar nao colide, mas aqui ha sobreposicao de 30 minutos.
			assertThat(livres).doesNotContain(h("08:40"), h("09:30"), h("17:00"), h("17:50"));
			assertThat(livres).containsExactlyElementsOf(horarios(
					"10:20", "11:10", "12:00", "12:50", "13:40",
					"14:30", "15:20", "16:10", "18:40"));
		}

		@Test
		@DisplayName("agendamento tambem colide por sobreposicao, nao por igualdade")
		void agendamentoColidePorSobreposicao() {
			// Agendamento 12:30-13:20 engole a faixa; o slot 12:00-12:50
			// sobrepoe e o vao nao o acomoda.
			List<LocalTime> livres = calcular(List.of(agendado("12:30", 50)), 50);

			assertThat(livres).doesNotContain(h("12:00"), h("12:30"), h("13:00"));
			assertThat(livres).contains(h("13:20"));
		}
	}

	// =======================================================================
	// Jornada
	// =======================================================================

	@Nested
	@DisplayName("Jornada")
	class Jornada {

		@Test
		@DisplayName("dia sem WorkingHours devolve lista vazia (sexta e sabado do Jhonatan)")
		void diaSemJornadaDevolveVazio() {
			List<LocalTime> livres = calcular(null, List.of(), List.of(), 50, null);

			assertThat(livres).isEmpty();
		}

		@Test
		@DisplayName("jornada sem nada agendado comeca em 08:40")
		void primeiroHorarioEAAbertura() {
			assertThat(calcular(List.of(), 50)).startsWith(ABERTURA);
		}

		@Test
		@DisplayName("o ultimo horario cabe inteiro antes de 19:30 — com 50 min, e 18:40")
		void ultimoHorarioCabeInteiro() {
			List<LocalTime> livres = calcular(List.of(), 50);

			assertThat(livres).endsWith(h("18:40"));
			assertThat(h("18:40").plusMinutes(50)).isEqualTo(FECHAMENTO);
		}

		@ParameterizedTest(name = "com duracao de {0} min, nenhum horario passa do fechamento")
		@ValueSource(ints = { 10, 25, 30, 50 })
		@DisplayName("nenhum horario ultrapassa o fim da jornada")
		void nenhumHorarioUltrapassaOFechamento(int duracao) {
			List<LocalTime> livres = calcular(List.of(), duracao);

			assertThat(livres).isNotEmpty();
			assertThat(livres).allSatisfy(horario ->
					assertThat(horario.plusMinutes(duracao)).isBeforeOrEqualTo(FECHAMENTO));
			assertThat(livres).allSatisfy(horario ->
					assertThat(horario).isAfterOrEqualTo(ABERTURA));
		}

		@Test
		@DisplayName("a lista inteira da jornada vazia bate com a do Node")
		void jornadaVaziaBateComONode() {
			assertThat(calcular(List.of(), 50)).containsExactlyElementsOf(horarios(
					"08:40", "09:30", "10:20", "11:10", "12:00", "12:50", "13:40",
					"14:30", "15:20", "16:10", "17:00", "17:50", "18:40"));
		}

		@Test
		@DisplayName("jornada invertida (fecha antes de abrir) devolve vazio")
		void jornadaInvertidaDevolveVazio() {
			List<LocalTime> livres = calcular(jornada(h("19:30"), h("08:40")),
					List.of(), List.of(), 50, null);

			assertThat(livres).isEmpty();
		}

		@Test
		@DisplayName("jornada curta demais para a duracao devolve vazio")
		void jornadaCurtaDemaisDevolveVazio() {
			List<LocalTime> livres = calcular(jornada(h("09:00"), h("09:30")),
					List.of(), List.of(), 50, null);

			assertThat(livres).isEmpty();
		}

		@Test
		@DisplayName("jornada do tamanho exato da duracao gera um horario")
		void jornadaExataGeraUmHorario() {
			List<LocalTime> livres = calcular(jornada(h("09:00"), h("09:50")),
					List.of(), List.of(), 50, null);

			assertThat(livres).containsExactly(h("09:00"));
		}

		@Test
		@DisplayName("a jornada tem que ser a do dia da data pedida")
		void jornadaDeDiaErradoERecusada() {
			int diaCerto = WorkingHours.dayOfWeekOf(DATA);
			WorkingHours deOutroDia = new WorkingHours((diaCerto + 1) % 7, ABERTURA, FECHAMENTO);

			assertThatThrownBy(() -> calcular(deOutroDia, List.of(), List.of(), 50, null))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("dayOfWeek");
		}
	}

	// =======================================================================
	// Hoje
	// =======================================================================

	@Nested
	@DisplayName("Antecedencia minima no proprio dia")
	class Hoje {

		@Test
		@DisplayName("agora 10:00 remove os horarios anteriores a 10:15")
		void removeOsAnterioresAoCorte() {
			List<LocalTime> livres = calcular(jornada(), List.of(), List.of(), 50, h("10:00"));

			assertThat(livres).doesNotContain(h("08:40"), h("09:30"));
			assertThat(livres).allSatisfy(horario ->
					assertThat(horario).isAfterOrEqualTo(h("10:15")));
		}

		@Test
		@DisplayName("agora 10:00 mantem 10:20")
		void mantemODepoisDoCorte() {
			List<LocalTime> livres = calcular(jornada(), List.of(), List.of(), 50, h("10:00"));

			assertThat(livres).contains(h("10:20"));
			assertThat(livres).startsWith(h("10:20"));
		}

		@Test
		@DisplayName("o horario exatamente em agora+15 e MANTIDO")
		void exatamenteNoCorteEMantido() {
			// Jornada abrindo 10:15 faz desse o primeiro horario gerado.
			List<LocalTime> livres = calcular(jornada(h("10:15"), FECHAMENTO),
					List.of(), List.of(), 50, h("10:00"));

			assertThat(livres).contains(h("10:15"));
		}

		@Test
		@DisplayName("um minuto antes do corte e removido")
		void umMinutoAntesDoCorteERemovido() {
			List<LocalTime> livres = calcular(jornada(h("10:14"), FECHAMENTO),
					List.of(), List.of(), 50, h("10:00"));

			assertThat(livres).doesNotContain(h("10:14"));
		}

		@Test
		@DisplayName("data futura (agoraSeForHoje nulo) nao filtra nada")
		void dataFuturaNaoFiltra() {
			List<LocalTime> comFiltro = calcular(jornada(), List.of(), List.of(), 50, h("10:00"));
			List<LocalTime> semFiltro = calcular(List.of(), 50);

			assertThat(semFiltro).contains(h("08:40"));
			assertThat(semFiltro).hasSizeGreaterThan(comFiltro.size());
		}

		@Test
		@DisplayName("agora depois do fechamento esvazia o dia")
		void agoraDepoisDoFechamentoEsvazia() {
			List<LocalTime> livres = calcular(jornada(), List.of(), List.of(), 50, h("19:00"));

			assertThat(livres).isEmpty();
		}

		@Test
		@DisplayName("a lista de hoje com agora 10:00 bate com a do Node")
		void hojeBateComONode() {
			List<LocalTime> livres = calcular(jornada(), List.of(), List.of(), 50, h("10:00"));

			assertThat(livres).containsExactlyElementsOf(horarios(
					"10:20", "11:10", "12:00", "12:50", "13:40",
					"14:30", "15:20", "16:10", "17:00", "17:50", "18:40"));
		}
	}

	// =======================================================================
	// Vaos
	// =======================================================================

	/**
	 * <b>Nota de mutation testing — quatro mutantes vivos aqui, de
	 * proposito.</b>
	 *
	 * <p>O PIT reporta como sobreviventes as duas guardas de
	 * {@code SlotCalculator.vaosLivres}: {@code if (cursor < bloco[0])} e
	 * {@code if (cursor < fechamento)}, tanto trocando {@code <} por
	 * {@code <=} quanto substituindo o {@code if} inteiro por {@code true}.
	 *
	 * <p><b>Sao mutantes equivalentes, e nenhum teste pode mata-los.</b> As
	 * duas guardas sao otimizacao, nao regra: quem de fato filtra vao
	 * degenerado e o laco que gera os horarios,
	 * {@code inicio + duracaoSlot <= vao[1]}. Como {@code duracaoSlot >= 1}
	 * sempre — {@code SlotRequest} exige duracao positiva e o teto e
	 * {@code min(x, 50)} — um vao de largura zero, ou invertido, nao produz
	 * horario nenhum. Nao ha saida observavel diferente para assertar.
	 *
	 * <p>Escrever teste para isso seria perseguir score: custo de manutencao
	 * sem proteger comportamento que alguem usa. Os casos que importam —
	 * agendamentos sobrepostos, aninhados e encostados — ja estao cobertos
	 * abaixo, e sao eles que provam que o colapso de vaos funciona.
	 */
	@Nested
	@DisplayName("Vaos entre agendamentos")
	class Vaos {

		@Test
		@DisplayName("agenda vazia da um vao unico, da abertura ao fechamento")
		void agendaVaziaDaVaoUnico() {
			List<LocalTime> livres = calcular(List.of(), 50);

			assertThat(livres).startsWith(ABERTURA).endsWith(h("18:40")).hasSize(13);
		}

		@Test
		@DisplayName("vao menor que a duracao solicitada nao gera horario")
		void vaoMenorQueADuracaoNaoGeraHorario() {
			// 09:00-09:50 e 10:30-11:20 deixam um vao de 40 min entre 09:50 e 10:30.
			List<LocalTime> livres = calcular(
					List.of(agendado("09:00", 50), agendado("10:30", 50)), 50);

			assertThat(livres).doesNotContain(h("09:50"));
			assertThat(livres).startsWith(h("11:20"));
		}

		@Test
		@DisplayName("vao exatamente do tamanho da duracao gera exatamente um horario")
		void vaoExatoGeraUmHorario() {
			// 09:00-09:50 e 10:40-11:30 deixam um vao de 50 min: cabe um.
			List<LocalTime> livres = calcular(
					List.of(agendado("09:00", 50), agendado("10:40", 50)), 50);

			assertThat(livres).contains(h("09:50"));
			assertThat(livres).containsExactlyElementsOf(horarios(
					"09:50", "11:30", "12:20", "13:10", "14:00",
					"14:50", "15:40", "16:30", "17:20", "18:10"));
		}

		@Test
		@DisplayName("dois agendamentos colados nao deixam vao entre eles")
		void doisAgendamentosColadosNaoDeixamVao() {
			List<LocalTime> livres = calcular(
					List.of(agendado("09:00", 50), agendado("09:50", 50)), 50);

			assertThat(livres).doesNotContain(h("09:50"));
			assertThat(livres).startsWith(h("10:40"));
		}

		@Test
		@DisplayName("o vao antes do primeiro agendamento e considerado")
		void vaoAntesDoPrimeiroEConsiderado() {
			// 09:30-10:20 deixa 08:40-09:30 livre: 50 min, cabe um.
			List<LocalTime> livres = calcular(List.of(agendado("09:30", 50)), 50);

			assertThat(livres).startsWith(ABERTURA);
		}

		@Test
		@DisplayName("agendamentos fora de ordem sao ordenados antes do calculo")
		void agendamentosForaDeOrdemSaoOrdenados() {
			List<BookedSlot> foraDeOrdem = List.of(
					agendado("10:50", 50), agendado("09:00", 50), agendado("09:10", 70));
			List<BookedSlot> emOrdem = List.of(
					agendado("09:00", 50), agendado("09:10", 70), agendado("10:50", 50));

			assertThat(calcular(foraDeOrdem, 50)).isEqualTo(calcular(emOrdem, 50));
		}

		@Test
		@DisplayName("agendamentos sobrepostos colapsam em uma regiao, sem vao fantasma")
		void agendamentosSobrepostosColapsam() {
			// Dois no mesmo horario (overbooking) e um aninhado.
			List<LocalTime> livres = calcular(List.of(
					agendado("09:00", 50), agendado("09:00", 50), agendado("09:10", 20)), 50);

			assertThat(livres).doesNotContain(h("09:00"), h("09:10"), h("09:30"));
			assertThat(livres).startsWith(h("09:50"));
		}

		@Test
		@DisplayName("agendamento antes da abertura nao cria vao negativo")
		void agendamentoAntesDaAberturaNaoCriaVaoNegativo() {
			List<LocalTime> livres = calcular(List.of(agendado("07:00", 50)), 50);

			assertThat(livres).startsWith(ABERTURA);
			assertThat(livres).allSatisfy(horario ->
					assertThat(horario).isAfterOrEqualTo(ABERTURA));
		}

		@Test
		@DisplayName("agendamento que avanca alem do fechamento encerra o dia")
		void agendamentoAlemDoFechamentoEncerraODia() {
			List<LocalTime> livres = calcular(List.of(agendado("19:00", 50)), 50);

			// O bloco vai de 19:00 a 19:50, passando do fechamento: nao sobra vao.
			assertThat(livres).endsWith(h("17:50"));
			assertThat(livres).doesNotContain(h("18:40"));
		}
	}

	// =======================================================================
	// Entrada degenerada
	// =======================================================================

	@Nested
	@DisplayName("Entrada degenerada")
	class EntradaDegenerada {

		@Test
		@DisplayName("listas vazias nao quebram")
		void listasVaziasNaoQuebram() {
			assertThatCode(() -> calcular(jornada(), List.of(), List.of(), 50, null))
					.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("duracao solicitada zero ou negativa e recusada na construcao do SlotRequest")
		void duracaoNaoPositivaERecusada() {
			// O laco de geracao avanca pela duracao: zero seria laco infinito.
			// No Node o Zod recusa na borda com 400, antes do calculo rodar.
			assertThatThrownBy(() -> new SlotRequest(DATA, 0))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("positivo");
			assertThatThrownBy(() -> new SlotRequest(DATA, -30))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("positivo");
		}

		@Test
		@DisplayName("agendamento de duracao zero nao bloqueia tempo, mas REALINHA a grade")
		void agendamentoDeDuracaoZeroRealinhaAGrade() {
			// Comportamento confirmado contra o Node, e nada obvio: o bloco tem
			// largura zero (09:00-09:00), entao nao impede nenhum horario — mas
			// o cursor avanca para 09:00, o vao seguinte comeca ali e a grade
			// passa a contar de 09:00 em vez de 08:40.
			List<LocalTime> livres = calcular(List.of(agendado("09:00", 0)), 50);

			assertThat(livres).containsExactlyElementsOf(horarios(
					"09:00", "09:50", "10:40", "11:30", "12:20", "13:10",
					"14:00", "14:50", "15:40", "16:30", "17:20", "18:10"));
			// Ou seja: NAO e equivalente a agenda vazia.
			assertThat(livres).isNotEqualTo(calcular(List.of(), 50));
		}

		@Test
		@DisplayName("agendamento de duracao negativa e recusado")
		void agendamentoNegativoERecusado() {
			assertThatThrownBy(() -> new BookedSlot(h("09:00"), -10))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("negativo");
		}

		@Test
		@DisplayName("bloqueio invertido e recusado")
		void bloqueioInvertidoERecusado() {
			assertThatThrownBy(() -> new TimeBlock(h("14:00"), h("13:00")))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> new TimeBlock(h("13:00"), h("13:00")))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("dayOfWeek fora de 0..6 e recusado")
		void dayOfWeekInvalidoERecusado() {
			assertThatThrownBy(() -> new WorkingHours(7, ABERTURA, FECHAMENTO))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> new WorkingHours(-1, ABERTURA, FECHAMENTO))
					.isInstanceOf(IllegalArgumentException.class);
		}

		/**
		 * Os dois extremos <b>validos</b>: domingo (0) e sabado (6).
		 *
		 * <p>Faltavam, e o buraco tem uma explicacao de producao: a tabela
		 * {@code professional_availability} so tem os dias 1 a 4 — sexta e
		 * sabado sao por ordem de chegada e domingo e fechado — entao nenhuma
		 * fixture precisou construir 0 ou 6, e o teste de borda so usava os
		 * invalidos (7 e -1).
		 *
		 * <p>Sem isto, trocar {@code dayOfWeek > 6} por {@code >= 6} passava
		 * pela suite inteira: sabado deixava de ser um dia valido e <b>nenhum
		 * dos 487 testes reclamava</b>. Verificado a mao antes de escrever
		 * este teste.
		 *
		 * <p>A regra vale mesmo sem jornada cadastrada nesses dias: o record
		 * e do dominio e aceita a semana inteira. Se um dia a barbearia abrir
		 * no sabado, o cadastro nao pode esbarrar num limite errado.
		 */
		@ParameterizedTest(name = "dayOfWeek {0} e valido")
		@ValueSource(ints = { 0, 1, 2, 3, 4, 5, 6 })
		@DisplayName("os sete dias da semana sao aceitos, extremos inclusive")
		void dayOfWeekValidoEmTodaASemana(int dia) {
			WorkingHours jornada = new WorkingHours(dia, ABERTURA, FECHAMENTO);

			assertThat(jornada.dayOfWeek()).isEqualTo(dia);
		}

		@Test
		@DisplayName("nulos obrigatorios sao recusados")
		void nulosSaoRecusados() {
			assertThatThrownBy(() -> new BookedSlot(null, 50))
					.isInstanceOf(NullPointerException.class);
			assertThatThrownBy(() -> new TimeBlock(null, h("13:00")))
					.isInstanceOf(NullPointerException.class);
			assertThatThrownBy(() -> new SlotRequest(null, 50))
					.isInstanceOf(NullPointerException.class);
			assertThatThrownBy(() -> new WorkingHours(0, null, FECHAMENTO))
					.isInstanceOf(NullPointerException.class);
			assertThatThrownBy(() -> SlotCalculatorTest.this.calculator
					.calcular(jornada(), null, List.of(), new SlotRequest(DATA, 50), null))
					.isInstanceOf(NullPointerException.class);
			assertThatThrownBy(() -> SlotCalculatorTest.this.calculator
					.calcular(jornada(), List.of(), null, new SlotRequest(DATA, 50), null))
					.isInstanceOf(NullPointerException.class);
			assertThatThrownBy(() -> SlotCalculatorTest.this.calculator
					.calcular(jornada(), List.of(), List.of(), null, null))
					.isInstanceOf(NullPointerException.class);
		}
	}

	// =======================================================================
	// Contrato da saida
	// =======================================================================

	@Nested
	@DisplayName("Contrato da saida")
	class ContratoDaSaida {

		@Test
		@DisplayName("a saida vem em ordem crescente e sem repeticao")
		void saidaOrdenadaESemRepeticao() {
			List<LocalTime> livres = calcular(
					List.of(agendado("10:50", 50), agendado("09:00", 50), agendado("09:10", 70)), 50);

			assertThat(livres).isSorted();
			assertThat(livres).doesNotHaveDuplicates();
		}

		@Test
		@DisplayName("a saida e imutavel")
		void saidaEImutavel() {
			List<LocalTime> livres = calcular(List.of(), 50);

			assertThatThrownBy(() -> livres.add(h("20:00")))
					.isInstanceOf(UnsupportedOperationException.class);
		}

		@Test
		@DisplayName("o calculo nao modifica as listas de entrada")
		void naoModificaAsEntradas() {
			List<BookedSlot> agendados = new java.util.ArrayList<>(
					List.of(agendado("09:00", 50), agendado("08:00", 50)));
			List<BookedSlot> copia = List.copyOf(agendados);

			calcular(jornada(), agendados, List.of(), 50, null);

			assertThat(agendados).containsExactlyElementsOf(copia);
		}

		@Test
		@DisplayName("o calculo e deterministico: mesma entrada, mesma saida")
		void calculoEDeterministico() {
			List<BookedSlot> agenda = List.of(agendado("09:00", 50), agendado("09:10", 70));

			assertThat(calcular(agenda, 50)).isEqualTo(calcular(agenda, 50));
		}

		@Test
		@DisplayName("as constantes de negocio estao declaradas e valem 50 e 15")
		void constantesDeNegocio() {
			assertThat(SlotCalculator.MAX_SLOT_MINUTES).isEqualTo(50);
			assertThat(SlotCalculator.CUTOFF_MINUTES).isEqualTo(15);
		}

		@Test
		@DisplayName("dayOfWeekOf usa a convencao do banco: domingo = 0")
		void dayOfWeekOfUsaConvencaoDoBanco() {
			// 2026-06-07 e um domingo.
			assertThat(WorkingHours.dayOfWeekOf(LocalDate.of(2026, 6, 7))).isZero();
			assertThat(WorkingHours.dayOfWeekOf(LocalDate.of(2026, 6, 8))).isEqualTo(1);
			assertThat(WorkingHours.dayOfWeekOf(LocalDate.of(2026, 6, 13))).isEqualTo(6);
		}
	}

}
