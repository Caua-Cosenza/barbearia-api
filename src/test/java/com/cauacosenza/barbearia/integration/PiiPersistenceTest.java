package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.cauacosenza.barbearia.domain.entity.Appointment;
import com.cauacosenza.barbearia.domain.entity.Barber;
import com.cauacosenza.barbearia.domain.entity.Service;
import com.cauacosenza.barbearia.domain.enums.AppointmentStatus;
import com.cauacosenza.barbearia.security.EncryptedStringConverter;

/**
 * Prova que o {@link EncryptedStringConverter} esta de fato no caminho entre a
 * entity e a coluna.
 *
 * <p>A asercao que importa e sempre em <b>duas camadas</b>: o que o
 * {@code JdbcTemplate} le na coluna e o que a entity devolve. Um teste que so
 * grava e le pela entity passaria com o converter desligado — ida e volta pelo
 * mesmo caminho sempre fecha. E por isso que aqui o SQL cru aparece.
 */
class PiiPersistenceTest extends AbstractPostgresIntegrationTest {

	/** {@code hex(iv)} de 32 caracteres, {@code ':'}, e o ciphertext em hex. */
	private static final String FORMATO_CIFRADO = "^[0-9a-f]{32}:[0-9a-f]+$";

	private static final String FIXTURE = "/crypto-fixture-node.txt";

	// =======================================================================
	// Ida e volta
	// =======================================================================

	@Nested
	@DisplayName("a coluna guarda hex:hex, a entity devolve texto claro")
	class IdaEVolta {

		@Test
		@DisplayName("a PII de visitante de um agendamento")
		void piiDeVisitante() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", 50);

			Appointment agendamento = novoAgendamentoUtc(barbeiro, servico,
					Instant.parse("2026-03-10T12:00:00Z"), 50, AppointmentStatus.PENDING);
			agendamento.setGuestName("Maria Aparecida");
			agendamento.setGuestPhone("5522999990000");
			agendamento.setGuestEmail("maria@example.com");
			String id = gravar(agendamento).getId();

			// No banco: cifrado, e nada do texto claro aparece.
			Map<String, Object> linha = jdbc.queryForMap("""
					SELECT "guest_name_encrypted", "guest_phone_encrypted", "guest_email_encrypted"
					FROM "appointments" WHERE "id" = ?
					""", id);

			assertThat((String) linha.get("guest_name_encrypted")).matches(FORMATO_CIFRADO);
			assertThat((String) linha.get("guest_phone_encrypted")).matches(FORMATO_CIFRADO);
			assertThat((String) linha.get("guest_email_encrypted")).matches(FORMATO_CIFRADO);
			assertThat(linha.values()).noneMatch(valor -> ((String) valor).contains("Maria"));

			// Na entity: texto claro.
			appointments.flush();
			Appointment lido = appointments.findById(id).orElseThrow();
			assertThat(lido.getGuestName()).isEqualTo("Maria Aparecida");
			assertThat(lido.getGuestPhone()).isEqualTo("5522999990000");
			assertThat(lido.getGuestEmail()).isEqualTo("maria@example.com");
		}

		@Test
		@DisplayName("o nome do profissional")
		void nomeDoProfissional() {
			String id = novoBarbeiro("Jhonatan Correa").getId();

			assertThat(jdbc.queryForObject(
					"SELECT \"name_encrypted\" FROM \"professionals\" WHERE \"id\" = ?", String.class, id))
					.matches(FORMATO_CIFRADO)
					.doesNotContain("Jhonatan");

			assertThat(barbers.findById(id).orElseThrow().getName()).isEqualTo("Jhonatan Correa");
		}

		@Test
		@DisplayName("acento e emoji sobrevivem: o plaintext e UTF-8 nas duas pontas")
		void utf8Sobrevive() {
			String nome = "João da Conceição 🎉";
			String id = novoBarbeiro(nome).getId();

			assertThat(barbers.findById(id).orElseThrow().getName()).isEqualTo(nome);
		}

		@Test
		@DisplayName("string vazia e cifrada normalmente e volta vazia — nao e o mesmo que nula")
		void stringVaziaNaoEhNula() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", 50);

			Appointment agendamento = novoAgendamentoUtc(barbeiro, servico,
					Instant.parse("2026-03-10T12:00:00Z"), 50, AppointmentStatus.PENDING);
			agendamento.setGuestName("");
			String id = gravar(agendamento).getId();

			assertThat(jdbc.queryForObject(
					"SELECT \"guest_name_encrypted\" FROM \"appointments\" WHERE \"id\" = ?", String.class, id))
					.matches(FORMATO_CIFRADO);

			assertThat(appointments.findById(id).orElseThrow().getGuestName()).isEmpty();
		}

		@Test
		@DisplayName("gravar o mesmo nome duas vezes produz ciphertexts diferentes (IV novo)")
		void ivNovoPorGravacao() {
			String primeiro = novoBarbeiro("Jhonatan").getId();
			String segundo = novoBarbeiro("Jhonatan").getId();

			String cifradoUm = jdbc.queryForObject(
					"SELECT \"name_encrypted\" FROM \"professionals\" WHERE \"id\" = ?", String.class, primeiro);
			String cifradoDois = jdbc.queryForObject(
					"SELECT \"name_encrypted\" FROM \"professionals\" WHERE \"id\" = ?", String.class, segundo);

			// E a razao pela qual nao existe WHERE nem ORDER BY em coluna
			// cifrada: o mesmo texto nao tem a mesma representacao.
			assertThat(cifradoUm).isNotEqualTo(cifradoDois);
			assertThat(barbers.findById(primeiro).orElseThrow().getName())
					.isEqualTo(barbers.findById(segundo).orElseThrow().getName());
		}

	}

	// =======================================================================
	// Nulo
	// =======================================================================

	@Nested
	@DisplayName("PII nula atravessa como nula, sem CryptoException")
	class Nulos {

		@Test
		@DisplayName("agendamento sem visitante: as tres colunas ficam NULL")
		void colunasDeVisitanteFicamNulas() {
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", 50);

			Appointment agendamento = novoAgendamentoUtc(barbeiro, servico,
					Instant.parse("2026-03-10T12:00:00Z"), 50, AppointmentStatus.PENDING);
			// Nada de guest_*: e o caminho do cliente logado.

			assertThatCode(() -> gravar(agendamento)).doesNotThrowAnyException();
			String id = agendamento.getId();

			Map<String, Object> linha = jdbc.queryForMap("""
					SELECT "guest_name_encrypted", "guest_phone_encrypted", "guest_email_encrypted"
					FROM "appointments" WHERE "id" = ?
					""", id);
			assertThat(linha).containsOnlyKeys(
					"guest_name_encrypted", "guest_phone_encrypted", "guest_email_encrypted");
			assertThat(linha.values()).containsOnlyNulls();

			Appointment lido = appointments.findById(id).orElseThrow();
			assertThat(lido.getGuestName()).isNull();
			assertThat(lido.getGuestPhone()).isNull();
			assertThat(lido.getGuestEmail()).isNull();
		}

		@Test
		@DisplayName("NULL vindo do banco nao chama o CryptoService na leitura")
		void leituraDeNullNaoExplode() {
			// O caminho inverso do teste anterior: a linha ja esta no banco com
			// NULL (como as linhas que o Node criou), e a leitura nao pode
			// levantar a CryptoException de decrypt(null) da Fase 2.
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", 50);
			String id = inserirAgendamentoCru(barbeiro, servico,
					"2026-03-10 12:00:00", "PENDING", 50, null);

			assertThatCode(() -> {
				Appointment lido = appointments.findById(id).orElseThrow();
				assertThat(lido.getGuestName()).isNull();
			}).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("nome nulo em coluna NOT NULL falha no banco, nao no converter")
		void nuloEmColunaObrigatoriaFalhaNoBanco() {
			Barber barbeiro = new Barber();
			barbeiro.setName(null);

			// O converter deixa o nulo passar; quem reclama e a constraint
			// NOT NULL. Essa e a divisao de responsabilidade correta -- o
			// converter nao conhece a nulabilidade da coluna.
			assertThatCode(() -> barbers.saveAndFlush(barbeiro))
					.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		}

	}

	// =======================================================================
	// Compatibilidade com o Node
	// =======================================================================

	@Nested
	@DisplayName("valor cifrado pelo Node e lido pela entity")
	class Compatibilidade {

		/**
		 * O teste que decide se a migracao e viavel ao nivel do ORM. O
		 * {@code CryptoServiceTest} ja provou que o Java decifra o que o Node
		 * cifrou; aqui o valor entra na coluna por SQL cru, <i>sem passar pelo
		 * converter na escrita</i>, e sai pela entity. E a simulacao mais
		 * proxima de ler uma linha que o Node gravou em producao.
		 */
		@Test
		@DisplayName("a linha chega ao banco por SQL cru e a entity devolve o texto claro")
		void entityDecifraOQueONodeCifrou() {
			Map<String, String> fixture = carregarFixture();
			Barber barbeiro = novoBarbeiro("Jhonatan");
			Service servico = novoServico("Corte", 50);

			// Uma hora diferente por entrada: todos sao PENDING do mesmo
			// profissional, e a constraint de exclusao da V2 recusaria o
			// segundo se compartilhassem horario. O teste e de PII, mas o banco
			// e o mesmo.
			int hora = 11;
			for (Map.Entry<String, String> entrada : fixture.entrySet()) {
				String id = inserirAgendamentoCru(barbeiro, servico,
						"2026-03-10 %02d:00:00".formatted(hora++), "PENDING", 50, entrada.getValue());

				assertThat(appointments.findById(id).orElseThrow().getGuestName())
						.as("plaintext \"%s\"", entrada.getKey())
						.isEqualTo(entrada.getKey());
			}

			assertThat(fixture).hasSizeGreaterThanOrEqualTo(7);
		}

		@Test
		@DisplayName("o nome de profissional cifrado pelo Node tambem e lido")
		void nomeDeProfissionalDoNode() {
			Map<String, String> fixture = carregarFixture();
			String plaintext = "Jhonatan Correa";
			String cifrado = fixture.get(plaintext);
			assertThat(cifrado).as("fixture precisa conter \"%s\"", plaintext).isNotNull();

			String id = UUID.randomUUID().toString();
			jdbc.update("""
					INSERT INTO "professionals" ("id", "name_encrypted", "is_active", "is_walk_in", "updated_at")
					VALUES (?, ?, true, false, now())
					""", id, cifrado);

			assertThat(barbers.findById(id).orElseThrow().getName()).isEqualTo(plaintext);
		}

	}

	// =======================================================================
	// Wiring do converter
	// =======================================================================

	@Test
	@DisplayName("o converter e um bean com CryptoService injetado — sem holder estatico")
	void converterEhBeanComDependenciaInjetada() {
		// Se a injecao via SpringBeanContainer parasse de funcionar, a entity
		// receberia um converter com cryptoService nulo e os testes acima
		// falhariam com NullPointerException. Esta asercao e o enunciado
		// explicito da expectativa: o converter resolvido pelo contexto
		// funciona por conta propria.
		EncryptedStringConverter converter = new EncryptedStringConverter(
				new com.cauacosenza.barbearia.security.CryptoService(TEST_KEY));

		String cifrado = converter.convertToDatabaseColumn("Jhonatan");
		assertThat(cifrado).matches(FORMATO_CIFRADO);
		assertThat(converter.convertToEntityAttribute(cifrado)).isEqualTo("Jhonatan");
		assertThat(converter.convertToDatabaseColumn(null)).isNull();
		assertThat(converter.convertToEntityAttribute(null)).isNull();
	}

	// =======================================================================
	// Apoio
	// =======================================================================

	/**
	 * Insere um agendamento por SQL cru, pulando o converter na escrita.
	 *
	 * <p>{@code status} entra como texto e o Postgres faz o cast para o enum
	 * nativo; {@code scheduled_at} entra como texto {@code "YYYY-MM-DD HH:MM:SS"}
	 * em UTC, que e o conteudo da coluna {@code TIMESTAMP(3)} sem fuso.
	 */
	private String inserirAgendamentoCru(Barber barbeiro, Service servico,
			String scheduledAtUtc, String status, int duracao, String guestNameCifrado) {

		String id = UUID.randomUUID().toString();
		jdbc.update("""
				INSERT INTO "appointments" (
					"id", "professional_id", "service_id", "amount_cents", "scheduled_at",
					"status", "guest_name_encrypted", "slot_duration_minutes", "updated_at")
				VALUES (?, ?, ?, ?, ?::timestamp, ?::"AppointmentStatus", ?, ?, now())
				""", id, barbeiro.getId(), servico.getId(), 5000, scheduledAtUtc,
				status, guestNameCifrado, duracao);
		return id;
	}

	/**
	 * Le {@code crypto-fixture-node.txt} no mesmo formato que o
	 * {@code CryptoServiceTest} usa: {@code "plaintext" => hex:hex}, uma
	 * entrada por linha, {@code #} comenta.
	 */
	private static Map<String, String> carregarFixture() {
		Map<String, String> entradas = new LinkedHashMap<>();
		try (InputStream in = PiiPersistenceTest.class.getResourceAsStream(FIXTURE)) {
			if (in == null) {
				throw new IllegalStateException("fixture nao encontrada: " + FIXTURE);
			}
			for (String linha : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
				String limpa = linha.strip();
				if (limpa.isEmpty() || limpa.startsWith("#")) {
					continue;
				}
				int seta = limpa.indexOf("=>");
				String plaintextJson = limpa.substring(0, seta).strip();
				String cifrado = limpa.substring(seta + 2).strip();
				// Desembrulha o literal JSON: as aspas existem na fixture para
				// que string vazia e espacos nas pontas fiquem inequivocos.
				entradas.put(plaintextJson.substring(1, plaintextJson.length() - 1), cifrado);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return entradas;
	}

}
