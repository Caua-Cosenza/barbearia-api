package com.cauacosenza.barbearia.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import com.cauacosenza.barbearia.domain.event.AgendamentoCancelado;
import com.cauacosenza.barbearia.domain.event.AgendamentoCriado;
import com.cauacosenza.barbearia.service.ChatMessagePort.ChatMessage;

/**
 * Manda as mensagens de WhatsApp <b>depois</b> que o agendamento esta gravado.
 *
 * <h2>Por que apos o commit, e nao try/catch dentro da transacao</h2>
 *
 * <p>O Node resolve isso com promessa solta ({@code void whatsappService...},
 * sem {@code await}) depois de a transacao do Prisma ter fechado, mais um
 * {@code try/catch} em volta do {@code fetch}. Funciona, com dois problemas:
 * um {@code throw} fora do {@code try} viraria <i>unhandled rejection</i>, e
 * nada garante a ordem entre o commit e o envio — e so uma consequencia de
 * como o codigo foi escrito, nao uma regra.
 *
 * <p>Aqui a regra e explicita: {@code AFTER_COMMIT}. O ouvinte so e chamado se
 * a transacao <b>comitou</b>. Isso da tres coisas que o {@code try/catch}
 * dentro da transacao nao daria:
 *
 * <ul>
 *   <li>a conexao com o banco <b>ja foi devolvida ao pool</b> antes de
 *       qualquer tentativa de rede. Uma Evolution lenta nao segura mais uma
 *       conexao do Hikari — com pool de 10, dez clientes agendando durante
 *       uma instabilidade da Evolution travariam o site inteiro;
 *   <li>nunca se manda mensagem de um agendamento que o banco recusou. O
 *       perdedor de uma disputa por horario (a constraint de exclusao da V2)
 *       nao recebe "seu agendamento foi confirmado";
 *   <li>o agendamento esta gravado e a resposta HTTP 201 ja saiu. O cliente
 *       nao espera o WhatsApp para ver a tela de confirmacao.
 * </ul>
 *
 * <p>Divergencia deliberada em relacao ao Node, registrada no README. O
 * comportamento observavel e o mesmo: a mensagem chega.
 *
 * <h2>A falha nao sobe</h2>
 *
 * <p>Nem precisaria: o {@link ChatMessagePort} promete nao lancar. O
 * {@code try/catch} aqui e a segunda trava, para o caso de uma implementacao
 * futura quebrar a promessa. Se uma excecao escapasse de um ouvinte
 * {@code AFTER_COMMIT} ela nao desfaria o commit — a transacao ja acabou —
 * mas subiria pela pilha e viraria 500 numa requisicao que <b>deu certo</b>.
 * O cliente veria erro depois de o horario ter sido reservado.
 */
@Component
public class NotificacaoDeAgendamentoListener {

	private static final Logger log = LoggerFactory.getLogger(NotificacaoDeAgendamentoListener.class);

	private final ChatMessagePort whatsapp;

	/**
	 * Em bean separado de proposito: {@code @Transactional} funciona por
	 * proxy, e uma chamada do bean a si mesmo o ignoraria. Ver o javadoc de
	 * {@link RegistroDeConfirmacaoEnviada}.
	 */
	private final RegistroDeConfirmacaoEnviada registroDeConfirmacao;

	/** {@code BARBER_PHONE}: para onde vao os avisos ao barbeiro. */
	private final String telefoneDoBarbeiro;

	/** {@code FRONTEND_URL}: base do link de cancelamento. */
	private final String urlDoFrontend;

	public NotificacaoDeAgendamentoListener(ChatMessagePort whatsapp,
			RegistroDeConfirmacaoEnviada registroDeConfirmacao,
			@Value("${BARBER_PHONE:}") String telefoneDoBarbeiro,
			@Value("${FRONTEND_URL:}") String urlDoFrontend) {

		this.whatsapp = whatsapp;
		this.registroDeConfirmacao = registroDeConfirmacao;
		this.telefoneDoBarbeiro = telefoneDoBarbeiro;
		this.urlDoFrontend = urlDoFrontend;
	}

	/**
	 * Duas mensagens: a confirmacao ao cliente e o aviso ao barbeiro.
	 *
	 * <p>A ordem importa pouco, mas a do cliente vem primeiro de proposito —
	 * e a que ele esta esperando.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void aoCriarAgendamento(AgendamentoCriado evento) {
		boolean clienteAvisado = enviarComProtecao(
				evento.guestPhone(),
				MensagensDeWhatsApp.confirmacaoAoCliente(
						evento.guestName(),
						evento.scheduledAt(),
						evento.servicos(),
						evento.totalEmCentavos(),
						MensagensDeWhatsApp.linkDeCancelamento(this.urlDoFrontend, evento.cancelToken())),
				evento.appointmentId());

		enviarComProtecao(
				this.telefoneDoBarbeiro,
				MensagensDeWhatsApp.novoAgendamentoAoBarbeiro(
						evento.guestName(),
						evento.guestPhone(),
						evento.scheduledAt(),
						evento.servicos(),
						evento.totalEmCentavos()),
				evento.appointmentId());

		// Marca so quando a mensagem do CLIENTE chegou: e dela que a coluna
		// fala. O aviso ao barbeiro falhar nao deixa o cliente sem
		// confirmacao, e vice-versa.
		if (clienteAvisado) {
			this.registroDeConfirmacao.marcar(evento.appointmentId());
		}
	}

	/** Um aviso ao barbeiro de que o horario voltou a ficar livre. */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void aoCancelarAgendamento(AgendamentoCancelado evento) {
		enviarComProtecao(
				this.telefoneDoBarbeiro,
				MensagensDeWhatsApp.cancelamentoAoBarbeiro(
						evento.guestName(),
						evento.scheduledAt(),
						evento.servicos()),
				evento.appointmentId());
	}

	/**
	 * Envia, tratando telefone ausente e qualquer excecao inesperada.
	 *
	 * <p>Telefone vazio e o caso de {@code BARBER_PHONE} nao configurado. O
	 * Node manda a requisicao de qualquer forma, com {@code number: "55"},
	 * que a Evolution recusa — <b>aqui o envio e simplesmente pulado</b>.
	 * Divergencia minima e deliberada: evita uma chamada de rede que nao tem
	 * como dar certo, e um log de erro recorrente que nao significa nada.
	 */
	private boolean enviarComProtecao(String telefone, String texto, String appointmentId) {
		if (!StringUtils.hasText(telefone)) {
			log.debug("Telefone de destino ausente — WhatsApp ignorado (agendamento {})",
					appointmentId);
			return false;
		}
		try {
			return this.whatsapp.enviar(new ChatMessage(telefone, texto));
		}
		catch (RuntimeException ex) {
			// O port promete nao lancar. Esta rede de seguranca existe porque
			// uma excecao escapando daqui viraria 500 numa requisicao que deu
			// certo -- o agendamento ja esta gravado.
			log.error("Envio de WhatsApp falhou de forma inesperada (agendamento {})",
					appointmentId, ex);
			return false;
		}
	}

}
