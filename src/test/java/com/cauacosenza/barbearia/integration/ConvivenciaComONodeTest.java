package com.cauacosenza.barbearia.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.cauacosenza.barbearia.domain.entity.Barber;
import com.cauacosenza.barbearia.domain.entity.Service;

/**
 * O INSERT do backend Node sobrevive as migrations do Java.
 *
 * <p>Entre a aplicacao das migrations em producao e a troca do dominio para o
 * Java, quem atende cliente ainda e o Node, escrevendo no mesmo banco. O
 * Prisma dele nao conhece {@code slot_duration_minutes}: o INSERT que ele
 * monta nao menciona a coluna. Como a V2 a criou {@code NOT NULL} sem default,
 * nesse intervalo <b>todo</b> agendamento criado pelo Node falharia — nao
 * degradacao, indisponibilidade total de agendamento. O {@code DEFAULT 50} da
 * V4 e o que fecha essa janela.
 *
 * <h2>Por que o INSERT e cru, e nao pelo repositorio</h2>
 *
 * <p>Passar pelo {@code AppointmentRepository} testaria o caminho do Java, que
 * <b>sempre</b> informa a duracao explicitamente — e o default nunca seria
 * exercitado. O que precisa ser provado aqui e o comportamento do banco diante
 * de um INSERT que omite a coluna, que e exatamente o que o Prisma emite.
 * Entao o teste escreve SQL, nomeando so as colunas que o
 * {@code schema.prisma} declara.
 *
 * <p>As colunas listadas em {@link #INSERT_DO_PRISMA} sao as que o
 * {@code schema.prisma} do Node tem para {@code appointments}. Se um dia o
 * Prisma passar a conhecer {@code slot_duration_minutes}, este teste deixa de
 * descrever a realidade — e a V4 deixa de ser necessaria.
 */
class ConvivenciaComONodeTest extends AbstractPostgresIntegrationTest {

	/**
	 * Um INSERT como o do Prisma: sem {@code slot_duration_minutes}.
	 *
	 * <p>{@code status} fica de fora para cair no {@code DEFAULT 'PENDING'} da
	 * V1, que e o que o Node grava num agendamento novo — e e um dos dois
	 * status que a constraint de exclusao da V2 enxerga.
	 */
	private static final String INSERT_DO_PRISMA = """
			INSERT INTO "appointments"
			    ("id", "professional_id", "service_id", "amount_cents",
			     "scheduled_at", "guest_name_encrypted", "created_at", "updated_at")
			VALUES (?, ?, ?, 5000, ?::timestamp, 'cifrado-ficticio',
			        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
			""";

	@Test
	@DisplayName("INSERT sem a coluna grava 50: o Node continua agendando")
	void insertSemAColunaGravaOTeto() {
		Barber barbeiro = novoBarbeiro("Jhonatan");
		Service servico = novoServico("Corte", 30);

		this.jdbc.update(INSERT_DO_PRISMA, "ag-do-node", barbeiro.getId(), servico.getId(),
				"2027-03-15 10:00:00");

		Integer gravado = this.jdbc.queryForObject(
				"SELECT \"slot_duration_minutes\" FROM \"appointments\" WHERE \"id\" = ?",
				Integer.class, "ag-do-node");

		assertThat(gravado)
				.as("sem o default da V4 este INSERT nem teria completado")
				.isEqualTo(50);
	}

	/**
	 * O default nao atropela quem informa o valor.
	 *
	 * <p>Guarda a leitura obvia e errada de "a coluna agora vale 50": o
	 * caminho do Java informa a duracao calculada pelo
	 * {@code SlotQueryMapper}, e e ela que tem que ir para o indice. Se um dia
	 * o default passasse a prevalecer, a grade e a constraint divergiriam em
	 * TODA linha, e nao apenas nas do Node.
	 */
	@Test
	@DisplayName("valor informado prevalece sobre o default")
	void valorExplicitoNaoEhSobrescrito() {
		Barber barbeiro = novoBarbeiro("Jhonatan");
		Service servico = novoServico("Corte", 30);

		this.jdbc.update("""
				INSERT INTO "appointments"
				    ("id", "professional_id", "service_id", "amount_cents",
				     "scheduled_at", "slot_duration_minutes", "created_at", "updated_at")
				VALUES (?, ?, ?, 5000, ?::timestamp, 30,
				        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", "ag-do-java", barbeiro.getId(), servico.getId(), "2027-03-15 10:00:00");

		Integer gravado = this.jdbc.queryForObject(
				"SELECT \"slot_duration_minutes\" FROM \"appointments\" WHERE \"id\" = ?",
				Integer.class, "ag-do-java");

		assertThat(gravado).isEqualTo(30);
	}

	/**
	 * A linha escrita pelo Node fica protegida pela constraint da V2.
	 *
	 * <p>E a razao de o default ser 50 e nao 30. A coluna so alimenta o indice
	 * da constraint de exclusao, entao a pergunta nao e qual valor descreve
	 * melhor o atendimento, e sim qual erra para o lado certo: um intervalo
	 * maior recusa mais, nunca menos. Aqui as 10:30 cai dentro de
	 * {@code 10:00 -> 10:50} e e recusada.
	 *
	 * <p>Com default 30 este segundo INSERT <b>passaria</b>, e dois clientes
	 * do Node ficariam marcados em horarios sobrepostos — que e precisamente o
	 * que a V2 existe para impedir.
	 */
	@Test
	@DisplayName("duas linhas do Node sobrepostas: a segunda e recusada")
	void aLinhaDoNodeEntraNaConstraint() {
		Barber barbeiro = novoBarbeiro("Jhonatan");
		Service servico = novoServico("Corte", 30);

		this.jdbc.update(INSERT_DO_PRISMA, "node-1", barbeiro.getId(), servico.getId(),
				"2027-03-15 10:00:00");

		assertThatThrownBy(() -> this.jdbc.update(INSERT_DO_PRISMA, "node-2",
				barbeiro.getId(), servico.getId(), "2027-03-15 10:30:00"))
				.hasMessageContaining("appointments_sem_sobreposicao");
	}

}
