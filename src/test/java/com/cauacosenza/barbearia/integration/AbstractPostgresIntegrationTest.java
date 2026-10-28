package com.cauacosenza.barbearia.integration;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.cauacosenza.barbearia.domain.entity.Appointment;
import com.cauacosenza.barbearia.domain.entity.Barber;
import com.cauacosenza.barbearia.domain.entity.BookedService;
import com.cauacosenza.barbearia.domain.entity.Service;
import com.cauacosenza.barbearia.domain.enums.AppointmentStatus;
import com.cauacosenza.barbearia.repository.AppointmentRepository;
import com.cauacosenza.barbearia.repository.BarberRepository;
import com.cauacosenza.barbearia.repository.ServiceRepository;
import com.cauacosenza.barbearia.service.SlotQueryMapper;

/**
 * Base dos testes que precisam de Postgres de verdade.
 *
 * <p>Sobe o contexto <b>inteiro</b>, e nao {@code @DataJpaTest}, por dois
 * motivos que vale enunciar:
 *
 * <ul>
 *   <li>{@code ddl-auto: validate} so roda com o contexto completo. Cada teste
 *       que herda desta classe confere de graca que as entities continuam
 *       alinhadas com V1 + V2.
 *   <li>{@code @DataJpaTest} envolve cada teste em uma transacao com rollback.
 *       Uma constraint de exclusao e verificada no commit, e uma condicao de
 *       corrida entre threads exige commits reais em conexoes distintas —
 *       nenhum dos dois cabe dentro de uma transacao de teste. <b>Nada aqui e
 *       {@code @Transactional}</b>, e cada {@code save} comita.
 * </ul>
 *
 * <p>As anotacoes sao identicas em todas as subclasses de proposito: o Spring
 * cacheia o contexto por conjunto de anotacoes, entao os containers de
 * Postgres e Redis sobem uma unica vez para a suite toda.
 *
 * <p>A {@code ENCRYPTION_KEY} e a mesma chave FICTICIA que gerou
 * {@code crypto-fixture-node.txt} — necessario para que o teste de leitura de
 * valor cifrado pelo Node funcione. Nenhuma chave de producao aparece em
 * teste.
 */
@SpringBootTest(properties = {
		"ENCRYPTION_KEY=4f8a2c1e9b7d3a5f6e0c8b4d2a9f1e7c3b5d8a0f2e6c4b9d1a7f3e5c8b2d6a0f"
})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class AbstractPostgresIntegrationTest {

	/** A mesma chave do {@code @SpringBootTest} acima, para uso direto. */
	protected static final String TEST_KEY =
			"4f8a2c1e9b7d3a5f6e0c8b4d2a9f1e7c3b5d8a0f2e6c4b9d1a7f3e5c8b2d6a0f";

	@Autowired
	protected AppointmentRepository appointments;

	@Autowired
	protected BarberRepository barbers;

	@Autowired
	protected ServiceRepository services;

	@Autowired
	protected SlotQueryMapper mapper;

	@Autowired
	protected JdbcTemplate jdbc;

	/**
	 * Limpa as tabelas antes de cada teste.
	 *
	 * <p>Sem {@code @Transactional} nao ha rollback, entao a limpeza e
	 * explicita. {@code TRUNCATE ... CASCADE} em vez de
	 * {@code deleteAll()}: e uma instrucao, respeita as FKs e nao depende de
	 * ordem de remocao. {@code users} entra na lista porque
	 * {@code appointments.user_id} aponta para ela.
	 */
	@BeforeEach
	void limparBanco() {
		this.jdbc.execute("""
				TRUNCATE TABLE "appointment_services", "appointments", "blocked_slots",
				               "professional_availability", "refresh_tokens",
				               "professionals", "services", "users" CASCADE
				""");
	}

	// =======================================================================
	// Fabricas de dados de teste
	//
	// Nomes e telefones sao inventados: as colunas sao de PII real em
	// producao, e nenhum valor de cliente entra em teste.
	// =======================================================================

	protected Barber novoBarbeiro(String nome) {
		Barber barbeiro = new Barber();
		barbeiro.setName(nome);
		barbeiro.setActive(true);
		return this.barbers.saveAndFlush(barbeiro);
	}

	protected Service novoServico(String nome, int duracaoMinutos) {
		Service servico = new Service();
		servico.setName(nome);
		servico.setAmountCents(5000);
		servico.setDurationMinutes(duracaoMinutos);
		servico.setActive(true);
		return this.services.saveAndFlush(servico);
	}

	/**
	 * Monta um agendamento <b>sem gravar</b>, com a duracao de slot ja
	 * coerente com os itens. Os itens sao acrescentados pelo chamador antes do
	 * save, e {@code slotDurationMinutes} recalculado por
	 * {@link #gravar(Appointment)}.
	 */
	protected Appointment novoAgendamento(Barber barbeiro, Service servico,
			LocalDate dia, LocalTime horaBrasilia, AppointmentStatus status) {

		Appointment agendamento = new Appointment();
		agendamento.setProfessional(barbeiro);
		agendamento.setService(servico);
		agendamento.setAmountCents(servico.getAmountCents());
		agendamento.setScheduledAt(this.mapper.toInstantUtc(dia, horaBrasilia));
		agendamento.setStatus(status);
		agendamento.setSlotDurationMinutes(SlotQueryMapper.DURACAO_PADRAO_MINUTOS);
		return agendamento;
	}

	/** Igual ao anterior, mas com {@code scheduled_at} dado em UTC direto. */
	protected Appointment novoAgendamentoUtc(Barber barbeiro, Service servico,
			Instant quandoUtc, int duracaoMinutos, AppointmentStatus status) {

		Appointment agendamento = new Appointment();
		agendamento.setProfessional(barbeiro);
		agendamento.setService(servico);
		agendamento.setAmountCents(servico.getAmountCents());
		agendamento.setScheduledAt(quandoUtc);
		agendamento.setStatus(status);
		agendamento.setSlotDurationMinutes(duracaoMinutos);
		return agendamento;
	}

	protected BookedService item(Service servico, int duracaoMinutos) {
		BookedService item = new BookedService();
		item.setService(servico);
		item.setAmountCents(servico.getAmountCents());
		item.setDurationMinutes(duracaoMinutos);
		return item;
	}

	/**
	 * Grava o agendamento depois de sincronizar
	 * {@code slot_duration_minutes} com os itens — a mesma regra que a V2
	 * aplicou no backfill. Toda escrita de agendamento passa por aqui para que
	 * nenhum teste invente uma linha que a constraint de exclusao enxergaria
	 * de forma diferente do calculo.
	 */
	protected Appointment gravar(Appointment agendamento) {
		agendamento.setSlotDurationMinutes(this.mapper.slotDurationMinutes(agendamento));
		return this.appointments.saveAndFlush(agendamento);
	}

}
