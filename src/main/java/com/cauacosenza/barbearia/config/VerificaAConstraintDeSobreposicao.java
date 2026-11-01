package com.cauacosenza.barbearia.config;

import java.util.List;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Confirma, no boot, que a constraint de exclusao de {@code appointments}
 * existe no banco.
 *
 * <h2>Por que isto nao e paranoia</h2>
 *
 * <p>A garantia contra duplo agendamento <b>nao esta no codigo</b>: esta no
 * indice. E a {@code appointments_sem_sobreposicao}, uma
 * {@code EXCLUDE USING gist} criada pela V2 do Flyway. O service apenas
 * traduz as duas excecoes que o banco lanca quando ela dispara.
 *
 * <p>O {@code schema.prisma} do backend Node <b>nao conhece</b> essa
 * constraint — ela nunca existiu em Prisma, so em SQL. E o script de
 * {@code start} daquele backend e
 * {@code prisma db push && node dist/index.js}. O {@code db push} reconcilia
 * o banco com o schema dele, o que significa que <b>todo deploy do Node
 * remove a constraint</b>, em silencio: sem erro, sem log, sem migration
 * reversa.
 *
 * <h2>Por que nada mais pega isso</h2>
 *
 * <ul>
 *   <li><b>{@code flyway validate} nao pega.</b> Ele compara o checksum dos
 *       arquivos de migration com o historico em
 *       {@code flyway_schema_history}. A V2 continua listada como aplicada
 *       com sucesso, porque ela <i>foi</i> aplicada — o que mudou depois foi o
 *       banco, e o Flyway nao verifica se os objetos ainda estao la.
 *   <li><b>{@code ddl-auto: validate} do Hibernate nao pega.</b> Ele confere
 *       tabelas, colunas e tipos contra as entities. Constraint de exclusao
 *       nao e mapeada por entity nenhuma, entao nao entra na comparacao.
 *   <li><b>A suite de testes nao pega.</b> Os containers de teste constroem o
 *       schema a partir das migrations, sempre do zero: a constraint esta
 *       sempre presente ali. O {@code ConcurrentBookingHttpTest} passaria
 *       verde contra um banco de producao sem a constraint.
 * </ul>
 *
 * <p>Ou seja: a protecao pode desaparecer e <b>todos os indicadores continuam
 * verdes</b>. O unico sintoma seria a corrida por horario passar a produzir
 * dois 201 e duas linhas — percebido pelo cliente que chega e encontra o
 * horario tomado, nao por um alerta.
 *
 * <h2>Onde isto roda</h2>
 *
 * <p>{@code @PostConstruct} de um singleton, com
 * {@code @DependsOn("flywayInitializer")}. Os dois detalhes importam:
 *
 * <ul>
 *   <li>o {@code @DependsOn} garante que a migration ja rodou — senao a
 *       verificacao poderia reclamar da ausencia de algo que o Flyway estava
 *       a ponto de criar;
 *   <li>{@code @PostConstruct} de singleton roda dentro do
 *       {@code refresh()} do contexto, antes de o conector do Tomcat comecar
 *       a aceitar conexao. Falhar aqui significa que <b>nenhuma requisicao
 *       foi atendida</b> — nao uma janela em que o site responde sem a
 *       garantia.
 * </ul>
 *
 * <p>Em {@code prod} a ausencia <b>impede a aplicacao de subir</b>; fora de
 * {@code prod} sai um WARN e a aplicacao sobe. Mesma polaridade do
 * {@link EnvironmentValidator}: falhar cedo e alto, com mensagem que nomeia a
 * causa provavel em vez de descrever o sintoma.
 */
@Component
@DependsOn("flywayInitializer")
public class VerificaAConstraintDeSobreposicao {

	private static final Logger log =
			LoggerFactory.getLogger(VerificaAConstraintDeSobreposicao.class);

	/** O nome que a V2 da a constraint. */
	static final String CONSTRAINT = "appointments_sem_sobreposicao";

	/**
	 * As constraints de exclusao de {@code appointments}, pelo catalogo.
	 *
	 * <p>{@code contype = 'x'} e exclusao. Consultar o catalogo, e nao tentar
	 * um {@code INSERT} que viole a regra, e o que permite verificar sem
	 * escrever nada no banco de producao.
	 */
	private static final String CONSULTA = """
			SELECT conname FROM pg_constraint
			 WHERE conrelid = 'appointments'::regclass AND contype = 'x'
			""";

	private final JdbcTemplate jdbc;

	private final Environment ambiente;

	public VerificaAConstraintDeSobreposicao(JdbcTemplate jdbc, Environment ambiente) {
		this.jdbc = jdbc;
		this.ambiente = ambiente;
	}

	@PostConstruct
	void verificar() {
		List<String> exclusoes = this.jdbc.queryForList(CONSULTA, String.class);
		if (exclusoes.contains(CONSTRAINT)) {
			log.debug("Constraint {} presente.", CONSTRAINT);
			return;
		}

		if (this.ambiente.matchesProfiles("prod")) {
			throw new IllegalStateException(mensagem(exclusoes));
		}
		// Fora de producao a ausencia e esperada em banco antigo, e exigir a
		// constraint impediria de subir para investigar justamente o problema.
		log.warn("{}", mensagem(exclusoes));
	}

	/**
	 * A mensagem, igual nos dois perfis.
	 *
	 * <p>Nomeia a constraint, a causa provavel e a consequencia concreta.
	 * "Constraint ausente" sozinho manda quem leu procurar o que isso
	 * significa; o objetivo e que a primeira linha do log ja diga o que
	 * aconteceu e o que fazer.
	 */
	private static String mensagem(List<String> encontradas) {
		return """
				A constraint %s nao existe em "appointments".

				E ela que impede duas reservas sobrepostas para o mesmo \
				profissional, e a garantia esta NELA, nao no codigo: sem a \
				constraint, duas requisicoes simultaneas para o mesmo horario \
				recebem 201 as duas e o banco fica com duas linhas.

				CAUSA PROVAVEL: um `prisma db push` do backend Node. O \
				schema.prisma nao conhece esta constraint, entao o push a \
				remove em silencio -- e o `start` daquele backend e \
				`prisma db push && node dist/index.js`, ou seja, todo deploy \
				dele a remove de novo.

				COMO CORRIGIR: reaplicar a V2 (ou so o ALTER TABLE ... ADD \
				CONSTRAINT dela) e retirar o `db push` do script de start.

				Constraints de exclusao encontradas em "appointments": %s"""
				.formatted(CONSTRAINT, encontradas.isEmpty() ? "nenhuma" : encontradas);
	}

}
