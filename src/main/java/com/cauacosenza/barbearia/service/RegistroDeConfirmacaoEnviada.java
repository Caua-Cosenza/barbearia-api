package com.cauacosenza.barbearia.service;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cauacosenza.barbearia.repository.AppointmentRepository;

/**
 * Grava que o cliente foi avisado por WhatsApp.
 *
 * <p><b>Por que isto e um bean separado, e nao um metodo do
 * {@code NotificacaoDeAgendamentoListener}.</b> O {@code @Transactional} do
 * Spring funciona por <i>proxy</i>: uma chamada que o bean faz a si mesmo nao
 * passa pelo proxy, e a anotacao e simplesmente ignorada. A primeira versao
 * tinha o metodo no proprio ouvinte e a marcacao <b>nao persistia</b> — sem
 * excecao, sem log, sem nada. O UPDATE era emitido e descartado.
 *
 * <p>A causa: o ouvinte roda em {@code AFTER_COMMIT}, dentro da finalizacao da
 * transacao que acabou de comitar. Nessa fase o {@code EntityManager} ainda
 * esta vinculado a thread, mas a transacao ja terminou — escrever ali nao tem
 * efeito. {@code REQUIRES_NEW} resolve, <i>desde que atravesse o proxy</i>,
 * e para isso a chamada precisa vir de outro bean.
 *
 * <h2>Para que servem estas colunas agora</h2>
 *
 * <p>{@code confirmation_sent} e {@code confirmation_sent_at} sobraram do job
 * de lembrete, que foi desativado em producao e <b>nao</b> foi portado. Em vez
 * de ficarem mortas, passam a responder "quem recebeu a confirmacao?" — e
 * portanto quem ficou sem, caso alguem queira reenviar manualmente depois de
 * uma instabilidade da Evolution.
 */
@Component
public class RegistroDeConfirmacaoEnviada {

	private static final Logger log = LoggerFactory.getLogger(RegistroDeConfirmacaoEnviada.class);

	private final AppointmentRepository appointments;

	private final Clock clock;

	public RegistroDeConfirmacaoEnviada(AppointmentRepository appointments, Clock clock) {
		this.appointments = appointments;
		this.clock = clock;
	}

	/**
	 * Marca o agendamento como avisado.
	 *
	 * <p>{@code REQUIRES_NEW} porque a transacao do agendamento <b>ja
	 * comitou</b>: sem uma transacao nova nao ha onde gravar.
	 *
	 * <p>Falha aqui nao sobe. A mensagem ja foi entregue ao cliente, e perder
	 * a marcacao e muito menos grave do que transformar uma requisicao
	 * bem-sucedida em 500 — o pior resultado seria o cliente ver erro depois
	 * de receber a confirmacao no WhatsApp.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void marcar(String appointmentId) {
		try {
			this.appointments.findById(appointmentId).ifPresent(agendamento -> {
				agendamento.setConfirmationSent(true);
				agendamento.setConfirmationSentAt(Instant.now(this.clock));
				this.appointments.save(agendamento);
			});
		}
		catch (RuntimeException ex) {
			log.warn("Nao foi possivel marcar confirmation_sent do agendamento {}: {}",
					appointmentId, ex.getClass().getSimpleName());
		}
	}

}
