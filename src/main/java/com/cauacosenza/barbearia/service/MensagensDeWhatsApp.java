package com.cauacosenza.barbearia.service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Compoe o texto das mensagens de WhatsApp, caractere a caractere igual ao
 * {@code whatsappService.ts} do Node.
 *
 * <p><b>Codigo puro:</b> sem Spring, sem HTTP, sem banco. Entra dado, sai
 * string. Isso e o que permite testar a redacao exata — emojis, asteriscos de
 * negrito, linhas em branco — sem subir nada.
 *
 * <p><b>Nada aqui foi "melhorado".</b> O cliente final le estas mensagens, e a
 * redacao e do dono da barbearia. Um asterisco fora de lugar quebra o negrito
 * do WhatsApp; uma linha em branco a menos muda o espacamento na tela do
 * celular. Os textos foram extraidos executando o proprio
 * {@code whatsappService} do Node com o {@code fetch} interceptado, e os
 * testes comparam com esse resultado.
 *
 * <h2>Os formatos, e por que cada um e assim</h2>
 *
 * <ul>
 *   <li><b>Data</b> — {@code terça-feira, 3 de novembro de 2026}. Reproduz o
 *       {@code Intl.DateTimeFormat('pt-BR', {weekday:'long', day:'numeric',
 *       month:'long', year:'numeric'})}. Note {@code d} e nao {@code dd}: o
 *       dia sai sem zero a esquerda, como o {@code numeric} do Intl.
 *   <li><b>Hora</b> — {@code 10:30}, 24 horas, sempre dois digitos.
 *   <li><b>Valor</b> — {@code 60,00}. Centavos divididos por 100 com duas
 *       casas e virgula decimal. A divisao e inteira em
 *       {@link #formatarValor(int)} para nao passar por ponto flutuante:
 *       {@code 4999 / 100.0} ja da 49.99 por sorte, mas outros valores
 *       arredondam torto, e isto e dinheiro.
 *   <li><b>Servicos</b> — unidos por {@code " + "}.
 *   <li><b>Telefone exibido ao barbeiro</b> — ver {@link #formatarTelefone(String)}.
 * </ul>
 *
 * <p>Tudo em hora de Brasilia: o {@code Intl} do Node recebe
 * {@code timeZone: 'America/Sao_Paulo'} e aqui a conversao e explicita pelo
 * mesmo fuso.
 */
public final class MensagensDeWhatsApp {

	/** {@code terça-feira, 3 de novembro de 2026} — {@code d}, sem zero. */
	private static final DateTimeFormatter DATA_LONGA =
			DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("pt-BR"));

	private static final DateTimeFormatter HORA =
			DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("pt-BR"));

	private MensagensDeWhatsApp() {
	}

	/**
	 * A confirmacao que o <b>cliente</b> recebe ao agendar, com o link de
	 * cancelamento.
	 *
	 * @param linkDeCancelamento a URL completa, ja montada; ver
	 *     {@link #linkDeCancelamento(String, String)}
	 */
	public static String confirmacaoAoCliente(String nomeDoCliente, Instant quando,
			List<String> servicos, int totalEmCentavos, String linkDeCancelamento) {

		return String.join("\n",
				"Olá, *" + nomeDoCliente + "*! 🎉",
				"",
				"Seu agendamento foi confirmado para *" + data(quando) + "* às *" + hora(quando) + "*!",
				"",
				"✂️ *Serviços:* " + servicos(servicos),
				"💰 *Total:* R$ " + formatarValor(totalEmCentavos),
				"",
				"Caso aconteça algum imprevisto e você não possa comparecer, você pode "
						+ "cancelar o seu horário clicando no link seguro abaixo:",
				linkDeCancelamento,
				"",
				"⚠️ *Atenção:* O cancelamento através deste link só é permitido com até "
						+ "*1 hora de antecedência* do seu horário marcado.",
				"",
				"Até logo! 💈");
	}

	/** O aviso que o <b>barbeiro</b> recebe quando alguem agenda. */
	public static String novoAgendamentoAoBarbeiro(String nomeDoCliente, String telefoneDoCliente,
			Instant quando, List<String> servicos, int totalEmCentavos) {

		return String.join("\n",
				"🔔 *Novo agendamento!*",
				"",
				"👤 *Cliente:* " + nomeDoCliente,
				"📱 *WhatsApp:* " + formatarTelefone(telefoneDoCliente),
				"📅 *Data:* " + data(quando),
				"🕐 *Horário:* " + hora(quando),
				"✂️ *Serviços:* " + servicos(servicos),
				"💰 *Total:* R$ " + formatarValor(totalEmCentavos));
	}

	/**
	 * O aviso que o <b>barbeiro</b> recebe quando o cliente cancela pelo link.
	 *
	 * <p>Nao traz valor nem telefone: o atendimento nao vai acontecer e nao ha
	 * ninguem para ligar. Traz a linha {@code _O horário ficou disponível._},
	 * em italico, que e a informacao acionavel.
	 */
	public static String cancelamentoAoBarbeiro(String nomeDoCliente, Instant quando,
			List<String> servicos) {

		return String.join("\n",
				"❌ *Agendamento cancelado*",
				"",
				"👤 *Cliente:* " + nomeDoCliente,
				"📅 *Data:* " + data(quando),
				"🕐 *Horário:* " + hora(quando),
				"✂️ *Serviços:* " + servicos(servicos),
				"",
				"_O horário ficou disponível._");
	}

	/**
	 * O link que vai na mensagem do cliente.
	 *
	 * <p>{@code {FRONTEND_URL}/cancelar/{token}}, a rota
	 * {@code /cancelar/:token} do React. Com {@code FRONTEND_URL} ausente o
	 * Node produz {@code /cancelar/token} — uma URL relativa, inutil num
	 * WhatsApp — e o mesmo acontece aqui: reproduzir e melhor do que inventar
	 * um default errado, e em producao a variavel esta definida.
	 */
	public static String linkDeCancelamento(String urlDoFrontend, String cancelToken) {
		return (urlDoFrontend != null ? urlDoFrontend : "") + "/cancelar/" + cancelToken;
	}

	/**
	 * O telefone como o barbeiro ve na mensagem: {@code (22) 99999-0000}.
	 *
	 * <p>Tira o DDI 55 <b>somente quando o numero tem mais de 11 digitos</b> —
	 * a condicao do Node ({@code startsWith('55') && length > 11}). O detalhe
	 * importa: um celular de Sao Paulo salvo sem DDI vira
	 * {@code 11987654321}, que comeca com "11" e nao com "55", mas um de
	 * Minas salvo sem DDI pode ser {@code 55987654321} — comeca com "55" e
	 * tem 11 digitos, e tirar o prefixo o transformaria em
	 * {@code (98) 7654-321}. Daí o teto de 11.
	 *
	 * <p>Formato de 11 digitos (celular, 9 na frente) e de 10 (fixo) sao
	 * diferentes, e os dois existem na base.
	 */
	public static String formatarTelefone(String telefone) {
		String digitos = somenteDigitos(telefone);
		String local = (digitos.startsWith("55") && digitos.length() > 11)
				? digitos.substring(2)
				: digitos;

		if (local.length() == 11) {
			return "(" + local.substring(0, 2) + ") " + local.substring(2, 7) + "-" + local.substring(7);
		}
		// O Node nao valida o tamanho aqui; com um numero curto o resultado
		// sai estranho em vez de levantar erro, e um aviso feio e melhor do
		// que nenhum aviso.
		return "(" + recortar(local, 0, 2) + ") " + recortar(local, 2, 6) + "-" + recortar(local, 6);
	}

	/** {@code 6000} para {@code "60,00"}, sem passar por ponto flutuante. */
	public static String formatarValor(int centavos) {
		return centavos / 100 + "," + String.format("%02d", Math.abs(centavos % 100));
	}

	public static String somenteDigitos(String valor) {
		return (valor == null) ? "" : valor.replaceAll("\\D", "");
	}

	private static String data(Instant quando) {
		return DATA_LONGA.format(quando.atZone(SlotQueryMapper.FUSO_BRASILIA));
	}

	private static String hora(Instant quando) {
		return HORA.format(quando.atZone(SlotQueryMapper.FUSO_BRASILIA));
	}

	private static String servicos(List<String> nomes) {
		return String.join(" + ", nomes);
	}

	/** {@code String.slice} do JavaScript: nao estoura em indice fora. */
	private static String recortar(String valor, int inicio, int fim) {
		if (inicio >= valor.length()) {
			return "";
		}
		return valor.substring(inicio, Math.min(fim, valor.length()));
	}

	private static String recortar(String valor, int inicio) {
		return (inicio >= valor.length()) ? "" : valor.substring(inicio);
	}

}
