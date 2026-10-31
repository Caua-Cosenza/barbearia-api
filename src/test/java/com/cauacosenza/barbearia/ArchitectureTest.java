package com.cauacosenza.barbearia;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.springframework.data.repository.Repository;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;

/**
 * As invariantes de arquitetura do projeto, como teste.
 *
 * <p>Antes disto elas existiam so na cabeca de quem escreveu o codigo, e
 * foram verificadas a mao algumas vezes — com {@code grep}, que ve apenas a
 * linha do {@code import}. O ArchUnit le o <b>bytecode</b>, e por isso pega o
 * que o grep nao pega: uso de nome totalmente qualificado sem import,
 * anotacao, tipo de parametro generico, tipo de retorno, campo e excecao
 * declarada.
 *
 * <p>Cada regra carrega um {@code because(...)} longo de proposito. A
 * mensagem de falha do ArchUnit inclui esse texto, entao quem quebrar uma
 * destas daqui a seis meses recebe o <b>motivo</b> junto com o fato — nao um
 * "violacao de arquitetura" seco que convida a apagar o teste.
 *
 * <p>So classes de producao sao analisadas ({@link ImportOption.DoNotIncludeTests}):
 * teste pode e deve depender de tudo.
 */
@AnalyzeClasses(
		packages = "com.cauacosenza.barbearia",
		importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	private static final String RULES = "com.cauacosenza.barbearia.domain.rules..";

	private static final String ENUMS = "com.cauacosenza.barbearia.domain.enums..";

	private static final String DOMAIN = "com.cauacosenza.barbearia.domain..";

	private static final String SERVICE = "com.cauacosenza.barbearia.service..";

	private static final String CONTROLLER = "com.cauacosenza.barbearia.controller..";

	private static final String REPOSITORY = "com.cauacosenza.barbearia.repository..";

	private static final String DTO = "com.cauacosenza.barbearia.dto..";

	// =======================================================================
	// 1 — a regra que justifica o teste existir
	// =======================================================================

	/**
	 * {@code domain.rules} nao conhece framework nenhum.
	 *
	 * <p>Esta e a regra principal. O pacote e logica de negocio <b>pura</b>:
	 * recebe listas, devolve listas. E isso que permite cobrir o calculo de
	 * horarios com dezenas de casos de borda em milissegundos, sem subir
	 * contexto do Spring nem container de banco.
	 *
	 * <p>A permissao inclui {@code domain.enums} porque ele e igualmente puro
	 * (sao enums sem dependencia alguma). <b>Nao</b> inclui
	 * {@code domain.exception}, que importa {@code HttpStatus} do Spring e
	 * portanto nao e puro — apesar de morar em {@code domain}.
	 */
	@ArchTest
	static final ArchRule regraDeOuro_calculoDeHorariosNaoDependeDeFramework =
			noClasses()
					.that().resideInAPackage(RULES)
					.should().dependOnClassesThat()
					.resideOutsideOfPackages("java..", RULES, ENUMS)
					.because("""
							domain.rules e logica de negocio PURA, e e isso que \
							torna o calculo de horarios testavel em milissegundos \
							sem subir contexto do Spring nem container de banco. \
							Qualquer import de Spring, Jakarta, JPA, Hibernate ou \
							Jackson aqui transforma o teste mais barato e mais \
							importante do projeto num teste de integracao lento. \
							Se o calculo precisa de um dado novo, quem busca esse \
							dado e a camada de cima: o SlotQueryMapper converte \
							entity em record e entrega pronto. Note que \
							domain.exception NAO e permitido -- ele depende de \
							HttpStatus do Spring e so mora em domain por \
							conveniencia""");

	// =======================================================================
	// 2 — a dependencia aponta para dentro
	// =======================================================================

	/**
	 * O dominio nao sabe que existe uma aplicacao web em volta dele.
	 *
	 * <p>{@code dto}, {@code controller}, {@code service} e
	 * {@code repository} podem depender de {@code domain}; o contrario, nunca.
	 * Uma entity que conhecesse um DTO de resposta ficaria acoplada ao formato
	 * do JSON que o frontend consome hoje.
	 */
	@ArchTest
	static final ArchRule dominioNaoConheceAsCamadasDeFora =
			noClasses()
					.that().resideInAPackage(DOMAIN)
					.should().dependOnClassesThat()
					.resideInAnyPackage(SERVICE, CONTROLLER, REPOSITORY, DTO)
					.because("""
							a dependencia aponta so para dentro: as camadas de \
							fora conhecem o dominio, o dominio nao conhece \
							nenhuma delas. Uma entity que dependesse de um DTO \
							ficaria presa ao formato do JSON que o frontend \
							consome hoje, e mudar a resposta da API passaria a \
							exigir mudar o modelo. Se o dominio parece precisar \
							de algo da camada de fora, o que falta geralmente e \
							um tipo proprio do dominio -- como os records de \
							domain.rules e os eventos de domain.event""");

	// =======================================================================
	// 3 — repositorio e assunto do service
	// =======================================================================

	/**
	 * Repositorio so e tocado por {@code service}.
	 *
	 * <p>A regra usa {@code Repository} do Spring Data, a super-interface de
	 * {@code JpaRepository}: pega os repositorios deste projeto
	 * independentemente de estenderem {@code JpaRepository} direto ou nao, e
	 * tambem os anotados com {@code @Repository}.
	 *
	 * <p>O proprio pacote {@code repository} entra na lista de permitidos
	 * porque um repositorio referencia outro em {@code default method} e em
	 * constantes compartilhadas.
	 */
	@ArchTest
	static final ArchRule repositorioSoEhAcessadoPorService =
			classes()
					.that().areAssignableTo(Repository.class)
					.or().areAnnotatedWith(org.springframework.stereotype.Repository.class)
					.should().onlyHaveDependentClassesThat()
					.resideInAnyPackage(SERVICE, REPOSITORY)
					.because("""
							repositorio e assunto do service, nunca do \
							controller. O controller que fala com o banco \
							direto pula a transacao, pula a regra de negocio e \
							pula a traducao de excecao -- neste projeto e no \
							service que vivem o @Transactional, as oito \
							validacoes de criacao de agendamento e a traducao \
							das DUAS excecoes que o banco lanca quando duas \
							pessoas disputam o mesmo horario. Um controller \
							acessando o repositorio devolveria 500 onde deveria \
							devolver 409""");

	// =======================================================================
	// 4 — log e via logger
	// =======================================================================

	/**
	 * Nada de {@code System.out} ou {@code System.err}.
	 *
	 * <p>A regra pronta do ArchUnit cobre os dois e tambem
	 * {@code Throwable.printStackTrace()}, que escreve em {@code System.err}
	 * pelas costas — e que neste projeto seria o pior dos tres, porque uma
	 * stack trace carrega mensagem de excecao, e mensagem de excecao aqui pode
	 * carregar PII.
	 */
	@ArchTest
	static final ArchRule nadaDeEscreverNoConsole =
			GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
					.because("""
							log e via logger, nunca System.out ou System.err. \
							Escrita direta no console escapa do nivel de log, do \
							formato e de qualquer filtro -- e este projeto ja \
							teve um incidente de log vazando conteudo de \
							conversa de WhatsApp, entao o que vai para a saida \
							precisa passar por um ponto controlado. A regra pega \
							tambem printStackTrace(), que escreve em System.err \
							sem parecer que escreve, e que imprime a mensagem da \
							excecao -- justamente o que pode carregar PII""");

}
