package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;

import com.cauacosenza.barbearia.domain.entity.Appointment;
import com.cauacosenza.barbearia.domain.entity.Barber;
import com.cauacosenza.barbearia.domain.entity.Service;
import com.cauacosenza.barbearia.domain.enums.AppointmentStatus;

/**
 * A constraint de exclusao da V2 — o teste que importa desta fase.
 *
 * <p>Nenhuma linha de codigo Java impede duplo agendamento. A garantia e do
 * schema: {@code EXCLUDE USING gist} sobre
 * {@code (professional_id, tsrange(scheduled_at, scheduled_at + duracao))},
 * parcial em {@code status IN ('PENDING','CONFIRMED')}. O que se verifica aqui
 * sao as quatro faces dessa definicao — mesmo profissional conflita,
 * profissionais diferentes nao, cancelado nao conta, encostado nao conflita —
 * porque cada uma delas e uma decisao explicita do {@code EXCLUDE} e um
 * caminho que o cliente percorre todo dia.
 *
 * <p>Sem {@code @Transactional}: a constraint e verificada no commit.
 */
class OverlapConstraintTest extends AbstractPostgresIntegrationTest {

	private static final LocalDate DIA = LocalDate.of(2026, 3, 10);

	/** Duracao padrao usada aqui: 50 minutos, o atendimento cheio. */
	private static final int CINQUENTA = 50;

	// =======================================================================
	// Mesmo profissional
	// =======================================================================

	@Nested
	@DisplayName("mesmo profissional, horarios sobrepostos")
	class MesmoProfissional {

		@Test
		@DisplayName("o segundo agendamento no mesmo horario falha com violacao de constraint")
		void mesmoHorarioExatoFalha() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", CINQUENTA);

			gravar(as(barbeiro, servico, LocalTime.of(10, 0), AppointmentStatus.PENDING));

			assertThatThrownBy(() -> gravar(as(barbeiro, servico, LocalTime.of(10, 0), AppointmentStatus.PENDING)))
					.isInstanceOf(DataIntegrityViolationException.class)
					.hasMessageContaining("appointments_sem_sobreposicao");

			assertThat(appointments.count()).isEqualTo(1);
		}

		@Test
		@DisplayName("sobreposicao parcial pela frente falha")
		void sobreposicaoParcialFalha() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", CINQUENTA);

			// 10:00-10:50 e 10:30-11:20 compartilham 20 minutos.
			gravar(as(barbeiro, servico, LocalTime.of(10, 0), AppointmentStatus.PENDING));

			assertThatThrownBy(() -> gravar(as(barbeiro, servico, LocalTime.of(10, 30), AppointmentStatus.PENDING)))
					.isInstanceOf(DataIntegrityViolationException.class);
		}

		@Test
		@DisplayName("um minuto de sobreposicao e suficiente para falhar")
		void umMinutoDeSobreposicaoFalha() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", CINQUENTA);

			// 10:00-10:50 e 10:49-11:39.
			gravar(as(barbeiro, servico, LocalTime.of(10, 0), AppointmentStatus.PENDING));

			assertThatThrownBy(() -> gravar(as(barbeiro, servico, LocalTime.of(10, 49), AppointmentStatus.PENDING)))
					.isInstanceOf(DataIntegrityViolationException.class);
		}

		@Test
		@DisplayName("um agendamento contido inteiro dentro do outro falha")
		void contidoFalha() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service longo = novoServico("Combo", CINQUENTA);
			Service curto = novoServico("Barba", 10);

			gravar(as(barbeiro, longo, LocalTime.of(10, 0), AppointmentStatus.PENDING));

			Appointment dentro = novoAgendamento(barbeiro, curto, DIA, LocalTime.of(10, 20),
					AppointmentStatus.PENDING);
			dentro.adicionarServico(item(curto, 10));

			assertThatThrownBy(() -> gravar(dentro))
					.isInstanceOf(DataIntegrityViolationException.class);
		}

		@ParameterizedTest(name = "um {0} sobreposto a um PENDING falha")
		@EnumSource(value = AppointmentStatus.class, names = { "PENDING", "CONFIRMED" })
		void osDoisStatusQueOcupamConflitamEntreSi(AppointmentStatus status) {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", CINQUENTA);

			gravar(as(barbeiro, servico, LocalTime.of(10, 0), AppointmentStatus.PENDING));

			assertThatThrownBy(() -> gravar(as(barbeiro, servico, LocalTime.of(10, 0), status)))
					.isInstanceOf(DataIntegrityViolationException.class);
		}

		@Test
		@DisplayName("o horario volta a ficar livre depois do cancelamento")
		void cancelarLiberaOHorario() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", CINQUENTA);

			Appointment primeiro = gravar(as(barbeiro, servico, LocalTime.of(10, 0), AppointmentStatus.PENDING));

			// Com ele PENDING, o horario esta ocupado.
			assertThatThrownBy(() -> gravar(as(barbeiro, servico, LocalTime.of(10, 0), AppointmentStatus.PENDING)))
					.isInstanceOf(DataIntegrityViolationException.class);

			primeiro.setStatus(AppointmentStatus.CANCELLED);
			appointments.saveAndFlush(primeiro);

			// Fora do WHERE parcial, ele sai do indice e o horario e revendido.
			assertThatCode(() -> gravar(as(barbeiro, servico, LocalTime.of(10, 0), AppointmentStatus.PENDING)))
					.doesNotThrowAnyException();
		}

	}

	// =======================================================================
	// Profissionais diferentes
	// =======================================================================

	@Nested
	@DisplayName("profissionais diferentes nao disputam horario")
	class ProfissionaisDiferentes {

		@Test
		@DisplayName("o mesmo horario para dois profissionais: os dois passam")
		void mesmoHorarioProfissionaisDiferentesPassa() {
			Barber jhonatan = novoBarbeiro("Jhonatan");
			Barber outro = novoBarbeiro("Outro");
			Service servico = novoServico("Corte", CINQUENTA);

			assertThatCode(() -> {
				gravar(as(jhonatan, servico, LocalTime.of(10, 0), AppointmentStatus.PENDING));
				gravar(as(outro, servico, LocalTime.of(10, 0), AppointmentStatus.PENDING));
			}).doesNotThrowAnyException();

			assertThat(appointments.count()).isEqualTo(2);
		}

		@Test
		@DisplayName("tres profissionais no mesmo horario tambem passam")
		void varriosProfissionaisNoMesmoHorario() {
			Service servico = novoServico("Corte", CINQUENTA);
			for (int i = 0; i < 3; i++) {
				Barber barbeiro = novoBarbeiro("Barbeiro " + i);
				gravar(as(barbeiro, servico, LocalTime.of(10, 0), AppointmentStatus.PENDING));
			}
			assertThat(appointments.count()).isEqualTo(3);
		}

	}

	// =======================================================================
	// O WHERE parcial
	// =======================================================================

	@Nested
	@DisplayName("status que nao ocupam agenda ficam fora do indice")
	class StatusQueNaoOcupam {

		@ParameterizedTest(name = "um {0} sobreposto a um PENDING passa")
		@EnumSource(value = AppointmentStatus.class, names = { "CANCELLED", "COMPLETED", "NO_SHOW" })
		void sobrepostoAUmPendingPassa(AppointmentStatus status) {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", CINQUENTA);

			gravar(as(barbeiro, servico, LocalTime.of(10, 0), AppointmentStatus.PENDING));

			assertThatCode(() -> gravar(as(barbeiro, servico, LocalTime.of(10, 0), status)))
					.doesNotThrowAnyException();

			assertThat(appointments.count()).isEqualTo(2);
		}

		@Test
		@DisplayName("dois CANCELLED no mesmo horario passam: nenhum dos dois esta no indice")
		void doisCanceladosNoMesmoHorario() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", CINQUENTA);

			assertThatCode(() -> {
				gravar(as(barbeiro, servico, LocalTime.of(10, 0), AppointmentStatus.CANCELLED));
				gravar(as(barbeiro, servico, LocalTime.of(10, 0), AppointmentStatus.CANCELLED));
			}).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("reativar um CANCELLED sobre um PENDING falha no UPDATE")
		void reativarSobreUmPendingFalha() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", CINQUENTA);

			gravar(as(barbeiro, servico, LocalTime.of(10, 0), AppointmentStatus.PENDING));
			Appointment cancelado = gravar(as(barbeiro, servico, LocalTime.of(10, 0), AppointmentStatus.CANCELLED));

			// A constraint nao vigia apenas o INSERT. Trazer o cancelado de
			// volta para dentro do WHERE parcial o coloca no indice, e ele
			// colide com o PENDING que ja estava la.
			cancelado.setStatus(AppointmentStatus.CONFIRMED);

			assertThatThrownBy(() -> appointments.saveAndFlush(cancelado))
					.isInstanceOf(DataIntegrityViolationException.class);
		}

	}

	// =======================================================================
	// Bordas do intervalo
	// =======================================================================

	@Nested
	@DisplayName("agendamentos encostados nao conflitam: o range e [inicio, fim)")
	class Encostados {

		@Test
		@DisplayName("um termina exatamente quando o outro comeca: os dois passam")
		void encostadoPorCimaPassa() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", CINQUENTA);

			// 10:00-10:50 e 10:50-11:40.
			assertThatCode(() -> {
				gravar(as(barbeiro, servico, LocalTime.of(10, 0), AppointmentStatus.PENDING));
				gravar(as(barbeiro, servico, LocalTime.of(10, 50), AppointmentStatus.PENDING));
			}).doesNotThrowAnyException();

			assertThat(appointments.count()).isEqualTo(2);
		}

		@Test
		@DisplayName("encostado por baixo tambem passa")
		void encostadoPorBaixoPassa() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", CINQUENTA);

			// Gravado o de 10:50 primeiro, depois o de 10:00.
			assertThatCode(() -> {
				gravar(as(barbeiro, servico, LocalTime.of(10, 50), AppointmentStatus.PENDING));
				gravar(as(barbeiro, servico, LocalTime.of(10, 0), AppointmentStatus.PENDING));
			}).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("uma grade inteira de 50 em 50 cabe sem conflito")
		void gradeInteiraCabe() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", CINQUENTA);

			// 08:40, 09:30, ... 18:40 -- a grade que o SlotCalculator gera para
			// a jornada 08:40-19:30 com agenda vazia. Se a constraint recusasse
			// encostados, a agenda do dia nao caberia no proprio dia.
			LocalTime hora = LocalTime.of(8, 40);
			int gravados = 0;
			while (hora.plusMinutes(CINQUENTA).isBefore(LocalTime.of(19, 31))) {
				gravar(as(barbeiro, servico, hora, AppointmentStatus.PENDING));
				hora = hora.plusMinutes(CINQUENTA);
				gravados++;
			}

			assertThat(gravados).isEqualTo(13);
			assertThat(appointments.count()).isEqualTo(13);
		}

		@Test
		@DisplayName("duracoes diferentes: o fim de cada um e o seu proprio")
		void duracoesDiferentesEncostam() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service barba = novoServico("Barba", 10);

			// Um de 10 minutos (10:00-10:10) e um de 50 (10:10-11:00): cada
			// linha monta o seu intervalo com a propria slot_duration_minutes.
			Appointment curto = novoAgendamento(barbeiro, barba, DIA, LocalTime.of(10, 0),
					AppointmentStatus.PENDING);
			curto.adicionarServico(item(barba, 10));
			gravar(curto);

			assertThatCode(() -> gravar(as(barbeiro, barba, LocalTime.of(10, 10), AppointmentStatus.PENDING)))
					.doesNotThrowAnyException();

			// E um minuto antes do fim do curto ainda conflita.
			assertThatThrownBy(() -> gravar(as(barbeiro, barba, LocalTime.of(10, 9), AppointmentStatus.PENDING)))
					.isInstanceOf(DataIntegrityViolationException.class);
		}

		@Test
		@DisplayName("dias diferentes no mesmo horario nao conflitam")
		void diasDiferentesNaoConflitam() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", CINQUENTA);

			assertThatCode(() -> {
				gravar(as(barbeiro, servico, LocalTime.of(10, 0), AppointmentStatus.PENDING));
				gravar(novoAgendamento(barbeiro, servico, DIA.plusDays(1), LocalTime.of(10, 0),
						AppointmentStatus.PENDING));
			}).doesNotThrowAnyException();
		}

	}

	// =======================================================================
	// A coluna materializada
	// =======================================================================

	@Nested
	@DisplayName("a constraint usa slot_duration_minutes, nao a soma dos itens")
	class DuracaoMaterializada {

		@Test
		@DisplayName("o fallback de 30 define um bloco de 30 minutos")
		void fallbackDefineBlocoDeTrinta() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", CINQUENTA);

			// Sem itens: 30 minutos, 10:00-10:30.
			Appointment sozinho = gravar(semItens(barbeiro, servico, LocalTime.of(10, 0),
					AppointmentStatus.PENDING));
			assertThat(sozinho.getSlotDurationMinutes()).isEqualTo(30);

			// 10:29 ainda esta dentro do bloco de 30 minutos.
			assertThatThrownBy(() -> gravar(semItens(barbeiro, servico, LocalTime.of(10, 29),
					AppointmentStatus.PENDING)))
					.isInstanceOf(DataIntegrityViolationException.class);

			// 10:30 encosta e passa.
			assertThatCode(() -> gravar(semItens(barbeiro, servico, LocalTime.of(10, 30),
					AppointmentStatus.PENDING)))
					.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("o teto de 50 encurta o bloco: 60 minutos de servico ocupam 50")
		void tetoEncurtaOBloco() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service corte = novoServico("Corte", CINQUENTA);
			Service pigmentacao = novoServico("Pigmentacao", 10);

			Appointment combo = novoAgendamento(barbeiro, corte, DIA, LocalTime.of(10, 0),
					AppointmentStatus.PENDING);
			combo.adicionarServico(item(corte, 50));
			combo.adicionarServico(item(pigmentacao, 10));
			assertThat(gravar(combo).getSlotDurationMinutes()).isEqualTo(50);

			// Sem o teto o bloco iria ate 11:00 e as 10:50 conflitaria. E o bug
			// real das 10:00 que desaparecia da tela, visto pelo lado da
			// escrita.
			assertThatCode(() -> gravar(as(barbeiro, corte, LocalTime.of(10, 50), AppointmentStatus.PENDING)))
					.doesNotThrowAnyException();
		}

		/**
		 * A coluna continua {@code NOT NULL}: nulo explicito e recusado.
		 *
		 * <p><b>Mudou o que este teste afirma, e vale saber por que.</b> Antes
		 * ele dizia "a V2 nao deu default" e provava isso <i>omitindo</i> a
		 * coluna no INSERT. A V4 passou a dar default 50, para que o INSERT do
		 * Prisma -- que nao conhece a coluna -- sobreviva a convivencia com o
		 * backend Node; omitir deixou de falhar. O caso da omissao esta em
		 * {@code ConvivenciaComONodeTest}, que e quem cuida dele.
		 *
		 * <p>O que importa para a constraint, e que esta classe guarda, e
		 * outra coisa: a coluna nunca pode ser NULA, porque
		 * {@code make_interval(mins => NULL)} produziria um range nulo e a
		 * linha sairia do indice -- deixando de ocupar a agenda sem que nada
		 * reclamasse. O default cobre quem esquece de informar; o NOT NULL
		 * cobre quem informa nulo. Sao duas protecoes diferentes, e so a
		 * segunda e desta classe.
		 */
		@Test
		@DisplayName("slot_duration_minutes e NOT NULL — nulo explicito e recusado")
		void colunaNaoAceitaNulo() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", CINQUENTA);

			assertThatThrownBy(() -> jdbc.update("""
					INSERT INTO "appointments" ("id", "professional_id", "service_id", "amount_cents",
					                            "scheduled_at", "status", "slot_duration_minutes",
					                            "updated_at")
					VALUES ('duracao-nula', ?, ?, 5000, '2026-03-10 13:00:00'::timestamp,
					        'PENDING'::"AppointmentStatus", NULL, now())
					""", barbeiro.getId(), servico.getId()))
					.isInstanceOf(DataIntegrityViolationException.class);
		}

	}

	// =======================================================================
	// Apoio
	// =======================================================================

	/**
	 * Um agendamento de <b>50 minutos</b> na hora dada, ainda nao gravado.
	 *
	 * <p>O item de 50 minutos e explicito de proposito: a duracao e o que
	 * define o intervalo que a constraint compara, entao deixa-la implicita
	 * tornaria cada asercao de sobreposicao ilegivel. Para o caso sem item
	 * nenhum existe {@link #semItens}.
	 */
	private Appointment as(Barber barbeiro, Service servico, LocalTime hora, AppointmentStatus status) {
		Appointment agendamento = novoAgendamento(barbeiro, servico, DIA, hora, status);
		agendamento.adicionarServico(item(servico, CINQUENTA));
		return agendamento;
	}

	/** Um agendamento sem item algum: cai no fallback de 30 minutos. */
	private Appointment semItens(Barber barbeiro, Service servico, LocalTime hora, AppointmentStatus status) {
		return novoAgendamento(barbeiro, servico, DIA, hora, status);
	}

}
