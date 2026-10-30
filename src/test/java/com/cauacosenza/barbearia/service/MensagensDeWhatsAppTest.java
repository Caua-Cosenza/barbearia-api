package com.cauacosenza.barbearia.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * O texto das mensagens, comparado com o que o Node produz.
 *
 * <p>O fixture {@code whatsapp-fixture-node.json} foi gerado <b>executando o
 * {@code whatsappService.ts}</b> do backend Node com o {@code fetch} global
 * interceptado — nenhuma mensagem saiu para a rede. Comparar contra ele e
 * diferente de comparar contra o que eu li no codigo: pega erro de locale, de
 * fuso e de formatacao de numero que a leitura nao pega.
 *
 * <p>A comparacao e de <b>string inteira</b>, nao de pedaços. Um emoji
 * trocado, um asterisco fora de lugar ou uma linha em branco a menos mudam o
 * que o cliente ve no celular, e so a igualdade exata prova que nada disso
 * aconteceu.
 */
class MensagensDeWhatsAppTest {

	/** 2026-11-03 10:30 em Brasilia (terca-feira). O mesmo do fixture. */
	private static final Instant QUANDO = Instant.parse("2026-11-03T13:30:00Z");

	private static final List<String> SERVICOS =
			List.of("Corte Máquina e Tesoura", "Barba Simples");

	private static final JsonNode FIXTURE = carregarFixture();

	// =======================================================================
	// Igualdade com o Node
	// =======================================================================

	@Nested
	@DisplayName("identico ao Node, caractere a caractere")
	class IgualAoNode {

		@Test
		@DisplayName("confirmação ao cliente")
		void confirmacaoAoCliente() {
			String nosso = MensagensDeWhatsApp.confirmacaoAoCliente(
					"Maria Aparecida", QUANDO, SERVICOS, 6000,
					MensagensDeWhatsApp.linkDeCancelamento(
							"https://barbeariajhonatan.com",
							"11111111-1111-4111-8111-111111111111"));

			assertThat(nosso).isEqualTo(textoDoFixture(0));
		}

		@Test
		@DisplayName("novo agendamento ao barbeiro")
		void novoAgendamentoAoBarbeiro() {
			String nosso = MensagensDeWhatsApp.novoAgendamentoAoBarbeiro(
					"Maria Aparecida", "5522999990000", QUANDO, SERVICOS, 6000);

			assertThat(nosso).isEqualTo(textoDoFixture(1));
		}

		@Test
		@DisplayName("cancelamento ao barbeiro")
		void cancelamentoAoBarbeiro() {
			String nosso = MensagensDeWhatsApp.cancelamentoAoBarbeiro(
					"Maria Aparecida", QUANDO, SERVICOS);

			assertThat(nosso).isEqualTo(textoDoFixture(2));
		}

		@Test
		@DisplayName("um serviço só, e valor de centavos quebrado")
		void umServicoSo() {
			String nosso = MensagensDeWhatsApp.cancelamentoAoBarbeiro(
					"Teste Sem 55", QUANDO, List.of("Sobrancelha"));

			assertThat(nosso).isEqualTo(textoDoFixture(3));
		}

		@Test
		@DisplayName("telefone fixo de 10 dígitos e valor de R$ 7,00")
		void telefoneFixo() {
			String nosso = MensagensDeWhatsApp.novoAgendamentoAoBarbeiro(
					"Cliente Fixo", "2233334444", QUANDO, List.of("Sobrancelha"), 700);

			assertThat(nosso).isEqualTo(textoDoFixture(4));
		}

	}

	// =======================================================================
	// Os detalhes de formatação, isolados
	// =======================================================================

	@Nested
	@DisplayName("formatação de valor")
	class Valor {

		@ParameterizedTest(name = "{0} centavos -> \"{1}\"")
		@CsvSource({
				"6000, '60,00'",
				"700, '7,00'",
				"4500, '45,00'",
				// Centavos quebrados: o caso em que ponto flutuante erraria.
				"4999, '49,99'",
				"1, '0,01'",
				"10, '0,10'",
				"0, '0,00'",
				"100000, '1000,00'",
		})
		void formata(int centavos, String esperado) {
			assertThat(MensagensDeWhatsApp.formatarValor(centavos)).isEqualTo(esperado);
		}

		@Test
		@DisplayName("não passa por ponto flutuante")
		void semPontoFlutuante() {
			// A divisao e inteira e o resto e formatado a parte. Com double,
			// alguns valores arredondariam torto -- e isto e dinheiro.
			for (int centavos = 0; centavos <= 20000; centavos++) {
				String formatado = MensagensDeWhatsApp.formatarValor(centavos);
				assertThat(formatado)
						.as("%d centavos", centavos)
						.matches("^\\d+,\\d{2}$");
			}
		}

	}

	@Nested
	@DisplayName("formatação do telefone exibido ao barbeiro")
	class Telefone {

		@ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
		@CsvSource({
				// 13 digitos com DDI: o 55 sai.
				"5522999990000, '(22) 99999-0000'",
				// 11 digitos sem DDI: celular.
				"22999990000, '(22) 99999-0000'",
				// 10 digitos sem DDI: fixo.
				"2233334444, '(22) 3333-4444'",
				// Com mascara: os nao-digitos somem antes.
				"'(22) 99999-0000', '(22) 99999-0000'",
				"+55 22 99999-0000, '(22) 99999-0000'",
		})
		void formata(String entrada, String esperado) {
			assertThat(MensagensDeWhatsApp.formatarTelefone(entrada)).isEqualTo(esperado);
		}

		@Test
		@DisplayName("o 55 NÃO sai de um número de 11 dígitos — o teto importa")
		void naoTiraDdiDeOnzeDigitos() {
			// A condicao do Node e startsWith('55') && length > 11. Um celular
			// que por coincidencia comeca com 55 e tem 11 digitos e um numero
			// local, nao um numero com DDI: tirar o prefixo o destruiria.
			assertThat(MensagensDeWhatsApp.formatarTelefone("55987654321"))
					.isEqualTo("(55) 98765-4321");
		}

	}

	@Nested
	@DisplayName("link de cancelamento")
	class Link {

		@Test
		@DisplayName("usa a rota /cancelar/:token do React")
		void rotaDoFrontend() {
			assertThat(MensagensDeWhatsApp.linkDeCancelamento("https://x.com", "abc"))
					.isEqualTo("https://x.com/cancelar/abc");
		}

		@Test
		@DisplayName("FRONTEND_URL ausente produz URL relativa — como no Node")
		void semFrontendUrl() {
			// Inutil num WhatsApp, e e o que o Node faz (`env.FRONTEND_URL ?? ''`).
			// Reproduzir e melhor do que inventar um default errado.
			assertThat(MensagensDeWhatsApp.linkDeCancelamento(null, "abc"))
					.isEqualTo("/cancelar/abc");
			assertThat(MensagensDeWhatsApp.linkDeCancelamento("", "abc"))
					.isEqualTo("/cancelar/abc");
		}

	}

	@Nested
	@DisplayName("data e hora saem em Brasília")
	class Fuso {

		@Test
		@DisplayName("03:00 UTC é meia-noite em Brasília — o dia não adianta")
		void viradaDoDia() {
			// 2026-11-03T02:59Z ainda e 2 de novembro em Brasilia.
			String antes = MensagensDeWhatsApp.cancelamentoAoBarbeiro(
					"X", Instant.parse("2026-11-03T02:59:00Z"), List.of("Corte"));
			assertThat(antes).contains("segunda-feira, 2 de novembro de 2026", "23:59");

			String depois = MensagensDeWhatsApp.cancelamentoAoBarbeiro(
					"X", Instant.parse("2026-11-03T03:00:00Z"), List.of("Corte"));
			assertThat(depois).contains("terça-feira, 3 de novembro de 2026", "00:00");
		}

		@Test
		@DisplayName("o dia sai sem zero à esquerda, como o 'numeric' do Intl")
		void diaSemZero() {
			assertThat(MensagensDeWhatsApp.cancelamentoAoBarbeiro(
					"X", Instant.parse("2026-11-03T13:30:00Z"), List.of("Corte")))
					.contains("3 de novembro")
					.doesNotContain("03 de novembro");
		}

		@Test
		@DisplayName("a hora sai COM zero à esquerda")
		void horaComZero() {
			assertThat(MensagensDeWhatsApp.cancelamentoAoBarbeiro(
					"X", Instant.parse("2026-11-03T12:05:00Z"), List.of("Corte")))
					.contains("09:05");
		}

	}

	// =======================================================================
	// Apoio
	// =======================================================================

	private static String textoDoFixture(int indice) {
		return FIXTURE.get("capturas").get(indice).get("body").get("text").asString();
	}

	/** O {@code number} que o Node enviou, para o teste do adaptador. */
	static String numeroDoFixture(int indice) {
		return FIXTURE.get("capturas").get(indice).get("body").get("number").asString();
	}

	static JsonNode fixture() {
		return FIXTURE;
	}

	private static JsonNode carregarFixture() {
		try (InputStream in = MensagensDeWhatsAppTest.class
				.getResourceAsStream("/whatsapp-fixture-node.json")) {

			if (in == null) {
				throw new IllegalStateException("fixture /whatsapp-fixture-node.json nao encontrada");
			}
			return new ObjectMapper().readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
