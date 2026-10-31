package com.cauacosenza.barbearia;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Todo {@code .java} declara um {@code package} que corresponde a sua pasta.
 *
 * <p><b>Nao e regra de ArchUnit, e nao poderia ser.</b> O ArchUnit le
 * bytecode, e no bytecode o pacote <i>e</i> o caminho — a divergencia some na
 * compilacao e seria invisivel ali. Esta checagem precisa olhar o
 * codigo-fonte.
 *
 * <p><b>Por que vale um teste.</b> O javac usa a declaracao de
 * {@code package} e ignora a pasta, entao um arquivo no diretorio errado
 * compila e roda sem reclamar. O estrago aparece no <b>build incremental</b>:
 * o {@code maven-compiler-plugin} decide o que recompilar comparando o
 * caminho do fonte com o do {@code .class}, e com a divergencia o
 * {@code .class} antigo fica num diretorio e o novo em outro. A aplicacao
 * morre com {@code ClassNotFoundException} apontando para uma classe que
 * obviamente existe.
 *
 * <p>O detalhe que torna isto traicoeiro: <b>{@code clean verify} e o CI nao
 * pegam</b>, porque sempre partem do zero. So morde quem esta desenvolvendo
 * local, e com uma mensagem que nao aponta para a causa. Ja aconteceu uma vez
 * neste projeto, com {@code UpdateAppointmentStatusRequest}, depois de um
 * "move class" de IDE que moveu o arquivo e nao atualizou a declaracao.
 */
class PacoteRefleteAPastaTest {

	/** As duas raizes de codigo-fonte Java do projeto. */
	private static final List<Path> RAIZES =
			List.of(Path.of("src", "main", "java"), Path.of("src", "test", "java"));

	/**
	 * A primeira declaracao de {@code package} do arquivo.
	 *
	 * <p>{@code MULTILINE} para que {@code ^} case o inicio de cada linha, e
	 * nao so o do arquivo — a declaracao vem depois do cabecalho de
	 * comentario em alguns arquivos.
	 */
	private static final Pattern DECLARACAO_DE_PACOTE =
			Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

	@Test
	@DisplayName("o package declarado corresponde à pasta, em todo arquivo .java")
	void todoArquivoDeclaraOPacoteDaSuaPasta() {
		List<String> divergentes = RAIZES.stream()
				.filter(Files::isDirectory)
				.flatMap(PacoteRefleteAPastaTest::arquivosJava)
				.map(PacoteRefleteAPastaTest::descreverSeDivergente)
				.filter(descricao -> descricao != null)
				.toList();

		assertThat(divergentes)
				.as("""
						Arquivo .java cuja pasta nao corresponde ao package \
						declarado. Compila e roda, mas quebra o build \
						incremental: o .class antigo fica na pasta antiga e a \
						aplicacao morre com ClassNotFoundException. Mova o \
						ARQUIVO para a pasta do package -- a declaracao \
						costuma estar certa, quem errou foi o move da IDE""")
				.isEmpty();
	}

	@Test
	@DisplayName("a varredura realmente leu os arquivos do projeto")
	void aVarreduraEncontraArquivos() {
		// Sem isto, um erro de caminho faria a varredura percorrer zero
		// arquivos e o teste acima passaria sem verificar nada -- o pior tipo
		// de teste verde.
		long quantidade = RAIZES.stream()
				.filter(Files::isDirectory)
				.flatMap(PacoteRefleteAPastaTest::arquivosJava)
				.count();

		assertThat(quantidade)
				.as("a varredura deveria achar as classes de producao e de teste")
				.isGreaterThan(100);
	}

	// =======================================================================

	/**
	 * Descreve a divergencia, ou {@code null} quando o arquivo esta correto.
	 *
	 * <p>A descricao traz o caminho, o pacote que a pasta sugere e o que o
	 * arquivo declara: tudo que alguem precisa para corrigir sem abrir o
	 * arquivo.
	 */
	private static String descreverSeDivergente(ArquivoNaRaiz arquivo) {
		Matcher declaracao = DECLARACAO_DE_PACOTE.matcher(ler(arquivo.caminho()));
		if (!declaracao.find()) {
			// package-info.java sem declaracao, ou arquivo no pacote default:
			// nenhum dos dois existe neste projeto, e se aparecer e para
			// falhar e alguem olhar.
			return arquivo.caminho() + ": nenhuma declaracao de package";
		}

		String declarado = declaracao.group(1);
		String esperado = pacoteDaPasta(arquivo);
		if (declarado.equals(esperado)) {
			return null;
		}
		return "%s%n            pasta sugere : %s%n            package diz  : %s"
				.formatted(arquivo.caminho(), esperado, declarado);
	}

	/** O pacote que a pasta do arquivo representa. */
	private static String pacoteDaPasta(ArquivoNaRaiz arquivo) {
		Path pastaRelativa = arquivo.raiz().relativize(arquivo.caminho().getParent());
		return String.join(".", pastaRelativa.toString().split("[/\\\\]"));
	}

	private static Stream<ArquivoNaRaiz> arquivosJava(Path raiz) {
		try (Stream<Path> caminhos = Files.walk(raiz)) {
			return caminhos
					.filter(Files::isRegularFile)
					.filter(caminho -> caminho.getFileName().toString().endsWith(".java"))
					.map(caminho -> new ArquivoNaRaiz(raiz, caminho))
					// toList antes de fechar o stream do walk.
					.toList()
					.stream();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String ler(Path caminho) {
		try {
			return Files.readString(caminho);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** Um arquivo e a raiz de fontes a que ele pertence. */
	private record ArquivoNaRaiz(Path raiz, Path caminho) {

	}

}
