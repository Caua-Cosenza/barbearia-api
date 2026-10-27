package com.cauacosenza.barbearia.domain.rules;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Calcula os horarios livres de um profissional em um dia.
 *
 * <p>Esta classe e a traducao de
 * {@code availableTimesController.ts} (linhas 96-149) do backend Node, que e a
 * especificacao. Ela e <b>pura</b>: sem Spring, sem JPA, sem relogio. Recebe
 * listas, devolve lista. E por isso que da para cobrir as dezenas de casos de
 * borda em milissegundos, sem subir contexto nem banco.
 *
 * <h2>O algoritmo</h2>
 * <ol>
 *   <li>Sem jornada para o dia, nao ha horarios. E assim que sexta e sabado do
 *       Jhonatan funcionam como ordem de chegada: a ausencia de linha em
 *       {@code professional_availability} e a propria regra.
 *   <li>Cada agendamento ocupa {@code min(somaDasDuracoes, 50)} minutos.
 *   <li>Caminha pelos blocos ocupados, em ordem de inicio, e coleta os vaos
 *       livres — antes do primeiro, entre eles, e depois do ultimo ate o
 *       fechamento.
 *   <li>Dentro de cada vao, gera horarios avancando pela duracao do slot
 *       enquanto ela couber inteira no vao.
 *   <li>Remove os que ficam a menos de {@link #CUTOFF_MINUTES} minutos de
 *       agora, se a data for hoje.
 *   <li>Remove os que colidem com bloqueio.
 * </ol>
 *
 * <h2>Duas consequencias que nao sao obvias</h2>
 * <p><b>A grade nao e fixa.</b> Cada vao recomeca a contagem no seu proprio
 * inicio, que e o fim do agendamento anterior. Com jornada 08:40-19:30 e
 * agenda vazia os horarios sao 08:40, 09:30, ... 18:40; com um agendamento
 * terminando 11:40, o vao seguinte produz 11:40, 12:30, ... 18:20.
 *
 * <p><b>Bloqueio nao realinha.</b> Bloqueios nao entram na formacao dos vaos
 * (passo 3): sao apenas um filtro no fim. Um bloqueio abre um buraco na lista
 * e os horarios depois dele seguem a grade de antes.
 *
 * <h2>Fuso horario</h2>
 * <p>Tudo entra e sai em hora local de Brasilia. A conversao de UTC e problema
 * da camada de cima. Internamente o calculo usa minutos desde a meia-noite,
 * em {@code int}, e nao {@link LocalTime}: {@code LocalTime.plusMinutes} da a
 * volta na meia-noite em silencio, e um agendamento perto das 23:00 passaria a
 * ocupar o inicio do dia.
 */
public final class SlotCalculator {

	/**
	 * Teto da duracao de um atendimento, em minutos.
	 *
	 * <p>Regra de negocio do Jhonatan: mais servico no mesmo tempo. Corte de 50
	 * + Pigmentacao de 10 soma 60, mas o atendimento ocupa 50.
	 *
	 * <p>O teto vale na <b>escrita e na leitura</b>. Esse foi um bug real: o
	 * agendamento era criado com o teto, mas o calculo de horarios livres
	 * somava a duracao real. Um cliente com 70 minutos somados bloqueava 70, o
	 * vao seguinte encurtava, e o horario das 10:00 desaparecia da tela.
	 */
	public static final int MAX_SLOT_MINUTES = 50;

	/**
	 * Antecedencia minima para agendar no proprio dia, em minutos.
	 *
	 * <p>Um horario e mantido quando esta a {@code agora + 15} ou mais; o
	 * limite em si fica na lista. Confere com o Node, que filtra por
	 * {@code parseTime(slot) >= currentMinutes + 15}.
	 */
	public static final int CUTOFF_MINUTES = 15;

	/**
	 * Calcula os horarios livres.
	 *
	 * @param jornada jornada do dia, ou {@code null} se o profissional nao
	 *     atende nesse dia da semana — devolve lista vazia
	 * @param agendados agendamentos que ja ocupam a agenda (apenas os que
	 *     contam: ver {@link BookedSlot})
	 * @param bloqueios bloqueios criados pelo profissional
	 * @param solicitacao data e duracao pedidas
	 * @param agoraSeForHoje hora atual em Brasilia quando
	 *     {@code solicitacao.date()} e hoje; {@code null} caso contrario, e
	 *     entao nada e filtrado por antecedencia
	 * @return horarios livres em ordem crescente, lista imutavel
	 */
	public List<LocalTime> calcular(
			WorkingHours jornada,
			List<BookedSlot> agendados,
			List<TimeBlock> bloqueios,
			SlotRequest solicitacao,
			LocalTime agoraSeForHoje) {

		Objects.requireNonNull(solicitacao, "solicitacao nao pode ser nula");
		Objects.requireNonNull(agendados, "agendados nao pode ser nulo (use lista vazia)");
		Objects.requireNonNull(bloqueios, "bloqueios nao pode ser nulo (use lista vazia)");

		// Invariante D: sem jornada, sem horarios.
		if (jornada == null) {
			return List.of();
		}

		// A jornada tem que ser a do dia pedido. O Node seleciona a
		// disponibilidade por dayOfWeek antes de chamar o calculo; aqui a
		// jornada chega pronta, e uma conversao errada de dia da semana na
		// camada de cima (1=segunda do Java vs 0=domingo do banco) passaria
		// despercebida e devolveria os horarios do dia errado.
		int diaPedido = WorkingHours.dayOfWeekOf(solicitacao.date());
		if (jornada.dayOfWeek() != diaPedido) {
			throw new IllegalArgumentException("Jornada e de dayOfWeek=" + jornada.dayOfWeek()
					+ ", mas a data pedida cai em dayOfWeek=" + diaPedido);
		}

		// Invariante A: a duracao pedida tambem e capada, e e esse valor capado
		// que serve de passo do avanco e de tamanho do slot na colisao.
		int duracaoSlot = Math.min(solicitacao.requestedDurationMinutes(), MAX_SLOT_MINUTES);
		int abertura = minutos(jornada.startsAt());
		int fechamento = minutos(jornada.endsAt());

		List<int[]> ocupados = blocosOcupados(agendados);
		List<int[]> vaos = vaosLivres(ocupados, abertura, fechamento);

		List<LocalTime> livres = new ArrayList<>();
		for (int[] vao : vaos) {
			// Invariante: o slot tem que caber inteiro no vao.
			for (int inicio = vao[0]; inicio + duracaoSlot <= vao[1]; inicio += duracaoSlot) {
				livres.add(deMinutos(inicio));
			}
		}

		// Invariante F: no proprio dia, respeita a antecedencia minima.
		if (agoraSeForHoje != null) {
			int corte = minutos(agoraSeForHoje) + CUTOFF_MINUTES;
			livres.removeIf(horario -> minutos(horario) < corte);
		}

		// Invariante C: remove o que colide com bloqueio.
		livres.removeIf(horario -> colideComBloqueio(minutos(horario), duracaoSlot, bloqueios));

		return List.copyOf(livres);
	}

	/**
	 * Converte os agendamentos em blocos ocupados {@code [inicio, fim)},
	 * ordenados por inicio.
	 *
	 * <p>Invariante B: o teto e aplicado <b>aqui</b>, na leitura. Sem isso o
	 * bug das 10:00 volta.
	 */
	private static List<int[]> blocosOcupados(List<BookedSlot> agendados) {
		List<int[]> blocos = new ArrayList<>(agendados.size());
		for (BookedSlot agendado : agendados) {
			int inicio = minutos(agendado.startsAt());
			int duracao = Math.min(agendado.durationMinutes(), MAX_SLOT_MINUTES);
			blocos.add(new int[] { inicio, inicio + duracao });
		}
		blocos.sort(Comparator.comparingInt(bloco -> bloco[0]));
		return blocos;
	}

	/**
	 * Varre os blocos ocupados e devolve os vaos livres dentro da jornada.
	 *
	 * <p>O cursor so anda para frente ({@code max}), o que faz blocos
	 * sobrepostos ou aninhados — dois agendamentos no mesmo horario, ou um
	 * dentro do outro — colapsarem em uma unica regiao ocupada em vez de
	 * gerarem vaos fantasma.
	 */
	private static List<int[]> vaosLivres(List<int[]> ocupados, int abertura, int fechamento) {
		List<int[]> vaos = new ArrayList<>();
		int cursor = abertura;
		for (int[] bloco : ocupados) {
			if (cursor < bloco[0]) {
				vaos.add(new int[] { cursor, bloco[0] });
			}
			cursor = Math.max(cursor, bloco[1]);
		}
		if (cursor < fechamento) {
			vaos.add(new int[] { cursor, fechamento });
		}
		return vaos;
	}

	/**
	 * Invariante C — colisao e sobreposicao de intervalos:
	 * {@code inicioSlot < fimBloco && fimSlot > inicioBloco}.
	 *
	 * <p>Encostar nao colide: um slot que termina exatamente quando o bloqueio
	 * comeca, ou que comeca exatamente quando ele acaba, esta livre.
	 */
	private static boolean colideComBloqueio(int inicioSlot, int duracaoSlot, List<TimeBlock> bloqueios) {
		int fimSlot = inicioSlot + duracaoSlot;
		for (TimeBlock bloqueio : bloqueios) {
			if (inicioSlot < minutos(bloqueio.endsAt()) && fimSlot > minutos(bloqueio.startsAt())) {
				return true;
			}
		}
		return false;
	}

	private static int minutos(LocalTime hora) {
		return hora.getHour() * 60 + hora.getMinute();
	}

	private static LocalTime deMinutos(int minutosDesdeMeiaNoite) {
		return LocalTime.of(minutosDesdeMeiaNoite / 60, minutosDesdeMeiaNoite % 60);
	}

}
