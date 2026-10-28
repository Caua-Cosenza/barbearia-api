package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;

import com.cauacosenza.barbearia.domain.entity.Appointment;
import com.cauacosenza.barbearia.domain.entity.Barber;
import com.cauacosenza.barbearia.domain.entity.Service;
import com.cauacosenza.barbearia.domain.enums.AppointmentStatus;

/**
 * Duas threads disputando o mesmo horario, de verdade.
 *
 * <p>Este e o teste que o {@link OverlapConstraintTest} nao substitui. La os
 * inserts sao sequenciais, e um {@code SELECT} antes do {@code INSERT} tambem
 * passaria — a janela entre a checagem e a gravacao nunca se abre quando so ha
 * uma thread. Aqui as tentativas partem do mesmo {@link CountDownLatch} e
 * correm em conexoes distintas do pool, que e a forma de reproduzir dois
 * clientes tocando "confirmar" no mesmo segundo.
 *
 * <p><b>O perdedor pode perder de duas formas, e as duas sao corretas.</b>
 * Isto nao e detalhe de teste, e a descoberta que a Fase 5 precisa levar em
 * conta:
 *
 * <ul>
 *   <li><b>Violacao da constraint</b> ({@link DataIntegrityViolationException},
 *       mensagem citando {@code appointments_sem_sobreposicao}) quando o
 *       vencedor <i>ja comitou</i> antes de o perdedor chegar ao indice.
 *   <li><b>Deadlock</b> ({@link CannotAcquireLockException}, mensagem
 *       <i>"deadlock detected ... while checking exclusion constraint"</i>)
 *       quando as duas transacoes chegam ao indice ainda abertas. Cada uma
 *       encontra a tupla nao comitada da outra, e uma constraint de exclusao
 *       nesse caso <b>espera</b> pela transacao concorrente em vez de decidir
 *       na hora — nao da para saber se aquela linha vai existir. As duas
 *       esperam uma pela outra, e o detector de deadlock do Postgres mata uma.
 * </ul>
 *
 * <p>Nos dois caminhos o resultado observavel e o mesmo e e o que importa:
 * <b>exatamente uma linha no banco</b>. Nenhum duplo agendamento escapa. Mas a
 * camada de servico da Fase 5 nao pode tratar so
 * {@code DataIntegrityViolationException}: um deadlock deixaria vazar um erro
 * 500 em vez de "esse horario acabou de ser ocupado". Qual dos dois acontece
 * depende de escalonamento e nao e previsivel, por isso o teste aceita os dois
 * e conta apenas quantos perderam.
 *
 * <p><b>Por que 20 iteracoes.</b> Uma condicao de corrida que "funcionou uma
 * vez" nao provou nada: o resultado pode ter vindo de um escalonamento
 * favoravel. Vinte repeticoes com a mesma asercao e o que distingue garantia
 * de sorte — e foi so repetindo que a variante de deadlock apareceu.
 *
 * <p><b>Nao ha lock no codigo da aplicacao.</b> Sem
 * {@code SELECT ... FOR UPDATE}, sem lock pessimista por profissional, sem
 * retry. O indice GiST da V2 decide dentro do commit.
 */
class OverlapRaceConditionTest extends AbstractPostgresIntegrationTest {

	private static final LocalDate DIA = LocalDate.of(2026, 3, 10);

	private static final LocalTime HORA_DISPUTADA = LocalTime.of(10, 0);

	/** Teto de espera por iteracao: generoso, so para o teste nao travar. */
	private static final int TIMEOUT_SEGUNDOS = 30;

	@RepeatedTest(value = 20, name = "iteracao {currentRepetition} de {totalRepetitions}")
	@DisplayName("duas threads, um horario: exatamente uma vence")
	void exatamenteUmaVence() throws Exception {
		Barber barbeiro = novoBarbeiro("Jhonatan");
		Service servico = novoServico("Corte", 50);

		Resultado resultado = disputar(barbeiro, servico, 2);

		assertThat(resultado.inesperados()).as("erros fora do esperado: %s", resultado.mensagens()).isEmpty();
		assertThat(resultado.vencedores()).as("agendamentos gravados").isEqualTo(1);
		assertThat(resultado.perdedores()).as("tentativas recusadas pelo banco").isEqualTo(1);

		// A asercao que fecha o raciocinio: o banco tem uma linha, nao duas.
		assertThat(appointments.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("oito threads disputando: uma vence, sete sao recusadas")
	void comMaisThreadsAindaSoUmaVence() throws Exception {
		// Duas threads provam que a janela fechou; oito provam que fechou para
		// todo mundo e nao por um empate de duas vias.
		Barber barbeiro = novoBarbeiro("Jhonatan");
		Service servico = novoServico("Corte", 50);

		Resultado resultado = disputar(barbeiro, servico, 8);

		assertThat(resultado.inesperados()).as("%s", resultado.mensagens()).isEmpty();
		assertThat(resultado.vencedores()).isEqualTo(1);
		assertThat(resultado.perdedores()).isEqualTo(7);
		assertThat(appointments.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("threads disputando horarios encostados: todas vencem")
	void horariosEncostadosNaoDisputam() throws Exception {
		// O contraponto necessario: a constraint tem que recusar o conflito sem
		// serializar o que nao conflita. Se ela bloqueasse encostados, montar
		// uma agenda cheia em paralelo seria impossivel.
		Barber barbeiro = novoBarbeiro("Jhonatan");
		Service servico = novoServico("Corte", 50);

		int threads = 8;
		CountDownLatch largada = new CountDownLatch(1);
		List<Callable<Throwable>> tentativas = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			tentativas.add(tentativa(barbeiro, servico, HORA_DISPUTADA.plusMinutes(50L * i), largada));
		}

		Resultado resultado = executar(tentativas, largada);

		assertThat(resultado.inesperados()).as("%s", resultado.mensagens()).isEmpty();
		assertThat(resultado.vencedores()).isEqualTo(threads);
		assertThat(resultado.perdedores()).isZero();
		assertThat(appointments.count()).isEqualTo(threads);
	}

	// =======================================================================
	// Mecanica da disputa
	// =======================================================================

	/**
	 * O resultado agregado de uma rodada.
	 *
	 * <p>{@code violacoes} e {@code deadlocks} sao contados separadamente para
	 * ficarem visiveis no diagnostico, mas nao sao asseverados: a proporcao
	 * entre os dois depende de escalonamento. O que se afirma e a soma, em
	 * {@link #perdedores()}.
	 */
	private record Resultado(int vencedores, int violacoes, int deadlocks, List<String> inesperados) {

		int perdedores() {
			return this.violacoes + this.deadlocks;
		}

		String mensagens() {
			return String.join(" | ", this.inesperados);
		}

	}

	private Resultado disputar(Barber barbeiro, Service servico, int threads) throws Exception {
		CountDownLatch largada = new CountDownLatch(1);
		List<Callable<Throwable>> tentativas = new ArrayList<>(threads);
		for (int i = 0; i < threads; i++) {
			tentativas.add(tentativa(barbeiro, servico, HORA_DISPUTADA, largada));
		}
		return executar(tentativas, largada);
	}

	/**
	 * Uma tentativa de gravar: espera a largada e devolve {@code null} em caso
	 * de sucesso, ou a excecao que recebeu.
	 *
	 * <p>A entity e montada <b>antes</b> do {@code await}, para que depois da
	 * largada sobre apenas o {@code INSERT}. Montar dentro da secao
	 * cronometrada diluiria a simultaneidade em construcao de objeto.
	 */
	private Callable<Throwable> tentativa(Barber barbeiro, Service servico, LocalTime hora,
			CountDownLatch largada) {

		Appointment agendamento = novoAgendamento(barbeiro, servico, DIA, hora, AppointmentStatus.PENDING);
		agendamento.adicionarServico(item(servico, 50));
		agendamento.setSlotDurationMinutes(this.mapper.slotDurationMinutes(agendamento));

		return () -> {
			largada.await(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS);
			try {
				this.appointments.saveAndFlush(agendamento);
				return null;
			}
			catch (RuntimeException ex) {
				return ex;
			}
		};
	}

	private Resultado executar(List<Callable<Throwable>> tentativas, CountDownLatch largada) throws Exception {
		int vencedores = 0;
		int violacoes = 0;
		int deadlocks = 0;
		List<String> inesperados = new ArrayList<>();

		// Uma thread de plataforma por tentativa: cada uma precisa da sua
		// conexao do pool.
		try (ExecutorService executor = Executors.newFixedThreadPool(tentativas.size())) {
			List<Future<Throwable>> futuros = new ArrayList<>(tentativas.size());
			for (Callable<Throwable> tentativa : tentativas) {
				futuros.add(executor.submit(tentativa));
			}

			// Todas as threads ja estao no await: a largada e simultanea.
			largada.countDown();

			for (Future<Throwable> futuro : futuros) {
				Throwable erro = futuro.get(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS);
				String detalhe = cadeiaDeMensagens(erro);

				if (erro == null) {
					vencedores++;
				}
				else if (erro instanceof DataIntegrityViolationException
						&& detalhe.contains("appointments_sem_sobreposicao")) {
					violacoes++;
				}
				else if (erro instanceof PessimisticLockingFailureException
						&& detalhe.contains("deadlock detected")) {
					// O caminho documentado no javadoc da classe: as duas
					// transacoes chegaram ao indice ainda abertas.
					deadlocks++;
				}
				else {
					inesperados.add(erro.getClass().getSimpleName() + ": " + erro.getMessage());
				}
			}

			executor.shutdown();
			if (!executor.awaitTermination(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS)) {
				fail("as threads da disputa nao terminaram");
			}
		}

		return new Resultado(vencedores, violacoes, deadlocks, List.copyOf(inesperados));
	}

	/** Concatena a mensagem da excecao e de todas as suas causas. */
	private static String cadeiaDeMensagens(Throwable erro) {
		StringBuilder mensagens = new StringBuilder();
		for (Throwable atual = erro; atual != null && atual != atual.getCause(); atual = atual.getCause()) {
			mensagens.append(atual.getMessage()).append('\n');
		}
		return mensagens.toString();
	}

}
