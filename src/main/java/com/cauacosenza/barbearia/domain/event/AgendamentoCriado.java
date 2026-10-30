package com.cauacosenza.barbearia.domain.event;

import java.time.Instant;
import java.util.List;

/**
 * Um agendamento acabou de ser gravado.
 *
 * <p><b>Um retrato completo, nao uma referencia.</b> O evento carrega todos os
 * dados de que a notificacao precisa, em vez de so o id. Dois motivos:
 *
 * <ul>
 *   <li>o ouvinte roda <b>depois do commit</b>, fora da transacao e da sessao
 *       do Hibernate. Passar a entity faria o acesso a {@code bookedServices}
 *       levantar {@code LazyInitializationException} — e a alternativa,
 *       recarregar tudo do banco, seria uma consulta extra para dados que
 *       quem publicou o evento ja tinha em maos;
 *   <li>o retrato e imutavel. A mensagem que o cliente recebe descreve o
 *       agendamento <i>como ele foi criado</i>, nao como esta agora.
 * </ul>
 *
 * <p>Note que {@code guestName} e {@code guestPhone} aqui estao em <b>texto
 * claro</b> — e PII. O evento nunca e serializado nem logado; ver as regras de
 * log em {@code EvolutionApiClient}.
 *
 * @param appointmentId usado para marcar {@code confirmation_sent} depois
 * @param totalEmCentavos soma do que o cliente vai pagar
 * @param servicos os nomes, na ordem em que entram na mensagem
 */
public record AgendamentoCriado(
		String appointmentId,
		String guestName,
		String guestPhone,
		Instant scheduledAt,
		List<String> servicos,
		int totalEmCentavos,
		String cancelToken) {

	public AgendamentoCriado {
		servicos = List.copyOf(servicos);
	}

}
