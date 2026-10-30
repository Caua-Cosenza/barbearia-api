package com.cauacosenza.barbearia.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cauacosenza.barbearia.service.ChatMessagePort;

/**
 * O {@link ChatMessagePort} que nao faz nada, para quando a Evolution nao
 * esta configurada.
 *
 * <p>Ativado quando faltar <b>qualquer uma</b> das tres variaveis —
 * {@code EVOLUTION_API_URL}, {@code EVOLUTION_API_KEY} ou
 * {@code EVOLUTION_INSTANCE_NAME}. Reproduz o comportamento do Node, que
 * verifica as tres e devolve com um {@code warn} se alguma faltar.
 *
 * <p><b>Por que isto existe:</b> o projeto precisa subir em desenvolvimento
 * sem WhatsApp nenhum. A alternativa seria o {@code EnvironmentValidator}
 * exigir as variaveis da Evolution no boot, e dai ninguem conseguiria rodar o
 * sistema localmente sem credencial de um servico de terceiro — o oposto do
 * que se quer num projeto que outra pessoa vai clonar.
 *
 * <p>O {@code false} e honesto: nada foi enviado, e portanto
 * {@code confirmation_sent} <b>nao</b> e marcado. Devolver {@code true}
 * deixaria no banco o registro de uma mensagem que nunca existiu, e um
 * eventual reenvio manual pularia justamente os agendamentos que ficaram sem
 * aviso.
 *
 * <p>O log e {@code debug} e nao {@code warn}: em desenvolvimento isso e o
 * normal e um aviso por agendamento seria so ruido. Que a Evolution nao esta
 * configurada e dito uma vez, no boot, por {@code EvolutionConfig}.
 */
public class NoOpChatMessageAdapter implements ChatMessagePort {

	private static final Logger log = LoggerFactory.getLogger(NoOpChatMessageAdapter.class);

	@Override
	public boolean enviar(ChatMessage mensagem) {
		// Nem o telefone mascarado entra aqui: nao houve envio, nao ha o que
		// diagnosticar.
		log.debug("Evolution API nao configurada — WhatsApp ignorado");
		return false;
	}

}
