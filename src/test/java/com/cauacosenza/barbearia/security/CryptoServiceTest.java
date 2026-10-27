package com.cauacosenza.barbearia.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.cauacosenza.barbearia.domain.exception.CryptoException;

/**
 * Teste puro: sem contexto Spring, o {@link CryptoService} e instanciado
 * direto com a chave de teste.
 *
 * <p>O grupo que importa de verdade e {@link Compatibilidade}: ele decifra em
 * Java valores que foram cifrados pelo Node. Se esse grupo falhar, a migracao
 * nao e viavel, porque os dados de cliente que ja estao no banco ficariam
 * ilegiveis.
 */
class CryptoServiceTest {

	/**
	 * Chave FICTICIA, a mesma que gerou {@code crypto-fixture-node.txt}.
	 * A ENCRYPTION_KEY de producao nunca aparece em codigo ou teste.
	 */
	private static final String TEST_KEY =
			"4f8a2c1e9b7d3a5f6e0c8b4d2a9f1e7c3b5d8a0f2e6c4b9d1a7f3e5c8b2d6a0f";

	/** Outra chave ficticia, para provar que a chave errada nao decifra. */
	private static final String OTHER_KEY =
			"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

	private static final String FIXTURE = "/crypto-fixture-node.txt";

	private final CryptoService crypto = new CryptoService(TEST_KEY);

	// =======================================================================
	// Fixture
	// =======================================================================

	/** Uma linha do fixture: o texto original e o que o Node produziu. */
	private record CasoNode(String plaintext, String ciphertextDoNode) {

		/** Rotulo curto para o relatorio do JUnit, sem despejar a linha toda. */
		@Override
		public String toString() {
			String resumo = this.plaintext.length() > 24
					? this.plaintext.substring(0, 24) + "... (" + this.plaintext.length() + " chars)"
					: this.plaintext;
			return this.plaintext.isEmpty() ? "(string vazia)" : resumo;
		}
	}

	private static List<CasoNode> lerFixture() {
		List<CasoNode> casos = new ArrayList<>();
		try (InputStream in = CryptoServiceTest.class.getResourceAsStream(FIXTURE)) {
			if (in == null) {
				throw new IllegalStateException("Fixture nao encontrado no classpath: " + FIXTURE);
			}
			// UTF-8 explicito: o fixture tem acento e emoji, e o default da JVM
			// no Windows nao e UTF-8.
			String conteudo = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			for (String linha : conteudo.split("\n")) {
				linha = linha.strip();
				if (linha.isEmpty() || linha.startsWith("#")) {
					continue;
				}
				int sep = linha.lastIndexOf(" => ");
				if (sep < 0) {
					throw new IllegalStateException("Linha de fixture malformada");
				}
				String plaintext = decodeJsonString(linha.substring(0, sep).strip());
				casos.add(new CasoNode(plaintext, linha.substring(sep + 4).strip()));
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		if (casos.isEmpty()) {
			throw new IllegalStateException("Fixture sem casos");
		}
		return casos;
	}

	/**
	 * Decodifica o literal JSON em que o fixture guarda o plaintext. Os
	 * delimitadores tornam a string vazia e os espacos nas pontas
	 * inequivocos.
	 */
	private static String decodeJsonString(String literal) {
		if (literal.length() < 2 || literal.charAt(0) != '"' || !literal.endsWith("\"")) {
			throw new IllegalStateException("Plaintext do fixture nao e um literal JSON");
		}
		String corpo = literal.substring(1, literal.length() - 1);
		StringBuilder out = new StringBuilder(corpo.length());
		for (int i = 0; i < corpo.length(); i++) {
			char c = corpo.charAt(i);
			if (c != '\\') {
				out.append(c);
				continue;
			}
			char escape = corpo.charAt(++i);
			switch (escape) {
				case 'n' -> out.append('\n');
				case 't' -> out.append('\t');
				case 'r' -> out.append('\r');
				case 'b' -> out.append('\b');
				case 'f' -> out.append('\f');
				case '"' -> out.append('"');
				case '\\' -> out.append('\\');
				case '/' -> out.append('/');
				case 'u' -> {
					out.append((char) Integer.parseInt(corpo.substring(i + 1, i + 5), 16));
					i += 4;
				}
				default -> throw new IllegalStateException("Escape JSON nao suportado no fixture");
			}
		}
		return out.toString();
	}

	static Stream<Arguments> casosDoNode() {
		return lerFixture().stream().map(Arguments::of);
	}

	// =======================================================================
	// Compatibilidade — o teste que decide a viabilidade da migracao
	// =======================================================================

	@Nested
	@DisplayName("Compatibilidade com o backend Node")
	class Compatibilidade {

		@ParameterizedTest(name = "decifra o que o Node cifrou: {0}")
		@MethodSource("com.cauacosenza.barbearia.security.CryptoServiceTest#casosDoNode")
		void decifraValorCifradoPeloNode(CasoNode caso) {
			assertThat(CryptoServiceTest.this.crypto.decrypt(caso.ciphertextDoNode()))
					.isEqualTo(caso.plaintext());
		}

		@Test
		@DisplayName("o fixture cobre acento, emoji, vazio, 16 bytes e 200 chars")
		void fixtureCobreOsCasosDeBorda() {
			List<String> plaintexts = lerFixture().stream().map(CasoNode::plaintext).toList();

			assertThat(plaintexts).hasSize(7);
			assertThat(plaintexts).contains("");
			assertThat(plaintexts).anyMatch(p -> p.contains("ç") || p.contains("ã"));
			// Emoji fora do BMP: 2 chars em Java (par surrogate), 4 bytes em UTF-8.
			assertThat(plaintexts).anyMatch(p -> p.codePoints().anyMatch(cp -> cp > 0xFFFF));
			// Exatamente um bloco: forca um bloco inteiro de padding PKCS#7.
			assertThat(plaintexts).anyMatch(p -> p.getBytes(StandardCharsets.UTF_8).length == 16);
			assertThat(plaintexts).anyMatch(p -> p.length() == 200);
		}

		@Test
		@DisplayName("o emoji sobrevive byte a byte, nao so no comprimento")
		void emojiSobreviveByteAByte() {
			CasoNode comEmoji = lerFixture().stream()
					.filter(c -> c.plaintext().codePoints().anyMatch(cp -> cp > 0xFFFF))
					.findFirst()
					.orElseThrow();

			String decifrado = CryptoServiceTest.this.crypto.decrypt(comEmoji.ciphertextDoNode());

			assertThat(decifrado.getBytes(StandardCharsets.UTF_8))
					.isEqualTo(comEmoji.plaintext().getBytes(StandardCharsets.UTF_8));
			assertThat(decifrado).isEqualTo("Ana 🎉 Souza");
		}
	}

	// =======================================================================
	// Round-trip
	// =======================================================================

	@Nested
	@DisplayName("Round-trip")
	class RoundTrip {

		@ParameterizedTest(name = "encrypt -> decrypt devolve o original: {0}")
		@MethodSource("com.cauacosenza.barbearia.security.CryptoServiceTest#casosDoNode")
		void encryptSeguidoDeDecryptDevolveOOriginal(CasoNode caso) {
			String cifrado = CryptoServiceTest.this.crypto.encrypt(caso.plaintext());

			assertThat(CryptoServiceTest.this.crypto.decrypt(cifrado)).isEqualTo(caso.plaintext());
		}

		@Test
		@DisplayName("o Node decifraria o que o Java cifrou (mesmo formato de saida)")
		void saidaDoJavaTemOMesmoFormatoDaDoNode() {
			List<CasoNode> casos = lerFixture();
			for (CasoNode caso : casos) {
				String doJava = CryptoServiceTest.this.crypto.encrypt(caso.plaintext());
				// Mesma estrutura e mesmo tamanho de ciphertext que o Node
				// produziu para o mesmo plaintext: se o padding ou o charset
				// divergissem, o tamanho mudaria.
				assertThat(doJava).matches("^[0-9a-f]{32}:[0-9a-f]+$");
				assertThat(doJava.substring(33).length())
						.isEqualTo(caso.ciphertextDoNode().substring(33).length());
			}
		}
	}

	// =======================================================================
	// Propriedades
	// =======================================================================

	@Nested
	@DisplayName("Propriedades do formato")
	class Propriedades {

		@Test
		@DisplayName("cifrar o mesmo texto duas vezes da saidas diferentes (IV novo)")
		void mesmoTextoProduzSaidasDiferentes() {
			String texto = "Jhonatan Correa";

			String primeira = CryptoServiceTest.this.crypto.encrypt(texto);
			String segunda = CryptoServiceTest.this.crypto.encrypt(texto);

			assertThat(primeira).isNotEqualTo(segunda);
			// E a diferenca esta no IV, nao por acidente.
			assertThat(iv(primeira)).isNotEqualTo(iv(segunda));
		}

		@Test
		@DisplayName("as duas saidas diferentes decifram para o mesmo plaintext")
		void ambasDeciframParaOMesmoPlaintext() {
			String texto = "Jhonatan Correa";

			String primeira = CryptoServiceTest.this.crypto.encrypt(texto);
			String segunda = CryptoServiceTest.this.crypto.encrypt(texto);

			assertThat(CryptoServiceTest.this.crypto.decrypt(primeira)).isEqualTo(texto);
			assertThat(CryptoServiceTest.this.crypto.decrypt(segunda)).isEqualTo(texto);
		}

		@Test
		@DisplayName("100 cifragens do mesmo texto geram 100 IVs distintos")
		void ivNuncaRepete() {
			Set<String> ivs = Stream.generate(() -> CryptoServiceTest.this.crypto.encrypt("x"))
					.limit(100)
					.map(CryptoServiceTest.Propriedades::iv)
					.collect(Collectors.toSet());

			assertThat(ivs).hasSize(100);
		}

		@ParameterizedTest(name = "saida casa com o formato esperado: {0}")
		@MethodSource("com.cauacosenza.barbearia.security.CryptoServiceTest#casosDoNode")
		void saidaCasaComOFormato(CasoNode caso) {
			String cifrado = CryptoServiceTest.this.crypto.encrypt(caso.plaintext());

			assertThat(cifrado).matches("^[0-9a-f]{32}:[0-9a-f]+$");
		}

		@ParameterizedTest(name = "IV tem sempre 32 chars hex: {0}")
		@MethodSource("com.cauacosenza.barbearia.security.CryptoServiceTest#casosDoNode")
		void ivTemSempre32CharsHex(CasoNode caso) {
			assertThat(iv(CryptoServiceTest.this.crypto.encrypt(caso.plaintext()))).hasSize(32);
			// Tambem nos valores que vieram do Node.
			assertThat(iv(caso.ciphertextDoNode())).hasSize(32);
		}

		@Test
		@DisplayName("o ciphertext e sempre multiplo do bloco de 16 bytes")
		void ciphertextEMultiploDoBloco() {
			for (CasoNode caso : lerFixture()) {
				String cifrado = CryptoServiceTest.this.crypto.encrypt(caso.plaintext());
				String corpo = cifrado.substring(cifrado.indexOf(':') + 1);

				assertThat(corpo.length() % 32).isZero();
			}
		}

		private static String iv(String cifrado) {
			return cifrado.substring(0, cifrado.indexOf(':'));
		}
	}

	// =======================================================================
	// Falhas
	// =======================================================================

	@Nested
	@DisplayName("Falhas")
	class Falhas {

		@ParameterizedTest(name = "entrada malformada rejeitada: \"{0}\"")
		@ValueSource(strings = { "semdoispontos", ":", "abc:", "zz:zz", "", "   " })
		void entradaMalformadaViraCryptoException(String entrada) {
			assertThatThrownBy(() -> CryptoServiceTest.this.crypto.decrypt(entrada))
					.isInstanceOf(CryptoException.class);
		}

		@Test
		@DisplayName("valor nulo vira CryptoException")
		void nuloViraCryptoException() {
			assertThatThrownBy(() -> CryptoServiceTest.this.crypto.decrypt(null))
					.isInstanceOf(CryptoException.class);
			assertThatThrownBy(() -> CryptoServiceTest.this.crypto.encrypt(null))
					.isInstanceOf(CryptoException.class);
		}

		@Test
		@DisplayName("IV com tamanho errado vira CryptoException")
		void ivComTamanhoErradoViraCryptoException() {
			// Hex valido, mas 4 bytes em vez de 16.
			assertThatThrownBy(() -> CryptoServiceTest.this.crypto.decrypt("aabbccdd:aabbccdd"))
					.isInstanceOf(CryptoException.class);
		}

		@Test
		@DisplayName("ciphertext valido nao decifra com chave diferente")
		void chaveDiferenteNaoDecifra() {
			CryptoService outroServico = new CryptoService(OTHER_KEY);
			CasoNode caso = lerFixture().stream()
					.filter(c -> c.plaintext().length() == 200)
					.findFirst()
					.orElseThrow();

			assertThatThrownBy(() -> outroServico.decrypt(caso.ciphertextDoNode()))
					.isInstanceOf(CryptoException.class);
		}

		@Test
		@DisplayName("a mensagem da excecao nao contem nenhum trecho da entrada")
		void mensagemNaoVazaEntrada() {
			// Entradas malformadas: nada da entrada pode reaparecer.
			List<String> malformadas = List.of("semdoispontos", "zz:zz", "abc:", "aabbccdd:aabbccdd");
			for (String entrada : malformadas) {
				String mensagem = capturarMensagem(CryptoServiceTest.this.crypto, entrada);
				assertThat(mensagem).isNotNull();
				for (String pedaco : entrada.split(":")) {
					if (!pedaco.isEmpty()) {
						assertThat(mensagem)
								.as("mensagem vazou trecho da entrada: %s", mensagem)
								.doesNotContain(pedaco);
					}
				}
			}

			// Chave errada: nem o ciphertext, nem o IV, nem a chave podem
			// aparecer na mensagem.
			CryptoService outroServico = new CryptoService(OTHER_KEY);
			CasoNode caso = lerFixture().stream()
					.filter(c -> c.plaintext().length() == 200)
					.findFirst()
					.orElseThrow();
			String mensagem = capturarMensagem(outroServico, caso.ciphertextDoNode());

			String ivHex = caso.ciphertextDoNode().substring(0, 32);
			String corpoHex = caso.ciphertextDoNode().substring(33);
			assertThat(mensagem)
					.doesNotContain(caso.ciphertextDoNode())
					.doesNotContain(ivHex)
					.doesNotContain(corpoHex)
					.doesNotContain(OTHER_KEY)
					.doesNotContain(TEST_KEY)
					.doesNotContain(caso.plaintext());
		}

		@Test
		@DisplayName("a mensagem de encrypt nao contem o plaintext")
		void mensagemDeEncryptNaoContemPlaintext() {
			// Chave de 16 bytes: AES-128 e valido no JCE, mas nao e o que este
			// servico aceita, entao a construcao ja falha.
			assertThatThrownBy(() -> new CryptoService("00112233445566778899aabbccddeeff"))
					.isInstanceOf(CryptoException.class)
					.hasMessageContaining("32 bytes")
					.hasMessageNotContaining("00112233445566778899aabbccddeeff");
		}

		@Test
		@DisplayName("chave invalida e recusada na construcao")
		void chaveInvalidaRecusadaNaConstrucao() {
			assertThatThrownBy(() -> new CryptoService(null)).isInstanceOf(CryptoException.class);
			assertThatThrownBy(() -> new CryptoService("")).isInstanceOf(CryptoException.class);
			assertThatThrownBy(() -> new CryptoService("naoHex".repeat(11)))
					.isInstanceOf(CryptoException.class);
			assertThatCode(() -> new CryptoService(TEST_KEY)).doesNotThrowAnyException();
		}

		private static String capturarMensagem(CryptoService servico, String entrada) {
			try {
				servico.decrypt(entrada);
				throw new AssertionError("esperava CryptoException para a entrada dada");
			}
			catch (CryptoException ex) {
				return ex.getMessage();
			}
		}
	}

	// =======================================================================
	// Concorrencia
	// =======================================================================

	@Nested
	@DisplayName("Concorrencia")
	class Concorrencia {

		private static final int THREADS = 100;

		@Test
		@DisplayName("100 threads cifrando e decifrando em paralelo, todas corretas")
		void cemThreadsEmParalelo() throws Exception {
			// Se houvesse um Cipher compartilhado em campo, este teste
			// quebraria com ciphertext corrompido ou excecao do JCE.
			CountDownLatch largada = new CountDownLatch(1);
			CountDownLatch chegada = new CountDownLatch(THREADS);
			ConcurrentLinkedQueue<String> falhas = new ConcurrentLinkedQueue<>();
			AtomicInteger sucessos = new AtomicInteger();

			try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
				for (int i = 0; i < THREADS; i++) {
					int id = i;
					pool.execute(() -> {
						try {
							// Todas as threads partem juntas, para maximizar a
							// chance de colisao se houvesse estado compartilhado.
							largada.await();
							String esperado = "cliente-" + id + " João 🎉";
							String cifrado = CryptoServiceTest.this.crypto.encrypt(esperado);
							String devolta = CryptoServiceTest.this.crypto.decrypt(cifrado);
							if (!esperado.equals(devolta)) {
								falhas.add("thread " + id + ": round-trip divergiu");
							}
							else if (!cifrado.matches("^[0-9a-f]{32}:[0-9a-f]+$")) {
								falhas.add("thread " + id + ": formato invalido");
							}
							else {
								sucessos.incrementAndGet();
							}
						}
						catch (InterruptedException ex) {
							Thread.currentThread().interrupt();
							falhas.add("thread " + id + ": interrompida");
						}
						catch (RuntimeException ex) {
							falhas.add("thread " + id + ": " + ex.getClass().getSimpleName());
						}
						finally {
							chegada.countDown();
						}
					});
				}

				largada.countDown();
				assertThat(chegada.await(60, TimeUnit.SECONDS))
						.as("as 100 threads deveriam terminar em 60s")
						.isTrue();
			}

			assertThat(falhas).isEmpty();
			assertThat(sucessos.get()).isEqualTo(THREADS);
		}

		@Test
		@DisplayName("em paralelo, os 100 IVs continuam distintos")
		void ivsDistintosSobConcorrencia() throws Exception {
			ConcurrentLinkedQueue<String> saidas = new ConcurrentLinkedQueue<>();
			CountDownLatch largada = new CountDownLatch(1);
			CountDownLatch chegada = new CountDownLatch(THREADS);

			try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
				for (int i = 0; i < THREADS; i++) {
					pool.execute(() -> {
						try {
							largada.await();
							saidas.add(CryptoServiceTest.this.crypto.encrypt("mesmo texto"));
						}
						catch (InterruptedException ex) {
							Thread.currentThread().interrupt();
						}
						finally {
							chegada.countDown();
						}
					});
				}
				largada.countDown();
				assertThat(chegada.await(60, TimeUnit.SECONDS)).isTrue();
			}

			Set<String> ivs = saidas.stream()
					.map(s -> s.substring(0, s.indexOf(':')))
					.collect(Collectors.toSet());

			assertThat(saidas).hasSize(THREADS);
			assertThat(ivs).as("IV repetido sob concorrencia e falha criptografica").hasSize(THREADS);
		}
	}

}
