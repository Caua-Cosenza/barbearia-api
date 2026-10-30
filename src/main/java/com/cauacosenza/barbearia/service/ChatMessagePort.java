package com.cauacosenza.barbearia.service;

import java.util.Objects;

/**
 * A porta de saida para mensagens de WhatsApp.
 *
 * <p><b>Nao ha uma palavra sobre Evolution, HTTP ou JSON nesta interface</b>, e
 * e esse o ponto: quem <i>usa</i> define o contrato, quem <i>implementa</i> se
 * adapta. A inversao de dependencia paga duas contas concretas aqui:
 *
 * <ul>
 *   <li>os testes do fluxo de agendamento rodam <b>sem Evolution no ar</b> —
 *       basta um mock desta interface, e o teste que importa ("falha no envio
 *       nao impede o agendamento") fica trivial de escrever;
 *   <li>trocar a Evolution pela API oficial da Meta um dia nao encosta no
 *       servico de agendamento. Troca-se o bean do pacote
 *       {@code integration}, e nada mais.
 * </ul>
 *
 * <p><b>Uma unica operacao, deliberadamente.</b> A porta transporta texto
 * pronto; <i>compor</i> o texto e {@link MensagensDeWhatsApp}, que e codigo
 * puro. Essa separacao existe porque o texto da mensagem e <b>regra de
 * negocio</b> — o cliente final le aquilo, cada emoji e cada quebra de linha
 * foram escolhidos — e nao detalhe de transporte. Se a composicao morasse no
 * adaptador HTTP, mudar a redacao exigiria mexer no cliente de rede, e testar
 * a redacao exigiria subir um servidor.
 *
 * <p><b>Implementacoes nunca lancam.</b> Ver o contrato em
 * {@link #enviar(ChatMessage)}.
 */
public interface ChatMessagePort {

	/**
	 * Entrega a mensagem, ou desiste em silencio.
	 *
	 * <p><b>Este metodo nao lanca excecao</b>, e isso e contrato, nao
	 * descuido: WhatsApp fora do ar nao pode impedir um agendamento. O cliente
	 * marca o horario, o barbeiro ve no painel, e so a mensagem nao chega.
	 * Quem chama nao precisa de {@code try/catch}.
	 *
	 * @return {@code true} se a mensagem foi aceita pelo provedor. O
	 *     {@code false} nao e erro — e a informacao que permite marcar
	 *     {@code confirmation_sent} apenas quando o envio deu certo, e assim
	 *     saber depois o que precisa de reenvio manual.
	 */
	boolean enviar(ChatMessage mensagem);

	/**
	 * Uma mensagem a enviar.
	 *
	 * @param telefone so digitos, com ou sem o DDI 55 — normalizar e trabalho
	 *     do adaptador, porque o formato exigido e detalhe do provedor
	 * @param texto o corpo pronto, com emojis e quebras de linha
	 */
	record ChatMessage(String telefone, String texto) {

		public ChatMessage {
			Objects.requireNonNull(telefone, "telefone nao pode ser nulo");
			Objects.requireNonNull(texto, "texto nao pode ser nulo");
		}

	}

}
