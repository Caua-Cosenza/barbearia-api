package com.cauacosenza.barbearia.config;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.cauacosenza.barbearia.dto.response.ApiResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Recusa pedidos de agendamento que tragam valor monetario no corpo.
 *
 * <p>Equivalente do {@code rejectAmountInBody} do Node, que barra
 * {@code amount}, {@code price} e {@code value} com <b>400</b> antes de o
 * schema rodar.
 *
 * <p><b>Nao e redundante com o {@code FAIL_ON_UNKNOWN_PROPERTIES}.</b> O
 * Jackson ja recusaria esses campos, mas com 400 "corpo invalido" vindo de um
 * caminho generico. Aqui a recusa e explicita e precede a desserializacao, o
 * que da duas coisas: o mesmo status e o mesmo texto que o Node devolve, e um
 * ponto unico onde registrar a tentativa. Um cliente mandando {@code amount}
 * nao e um erro de digitacao — e alguem testando se o servidor aceita o preco
 * que ele escolheu. O {@code warn} existe para isso.
 *
 * <p>O preco <b>nunca</b> vem do cliente em caminho nenhum: o
 * {@code PublicAppointmentService} soma os valores lidos do catalogo pelos
 * {@code serviceIds}. Este filtro e a porta da frente trancada; a fechadura de
 * verdade e o service nunca ler valor da entrada.
 *
 * <p>O corpo de uma requisicao HTTP so pode ser lido uma vez. Para que o
 * controller ainda o receba, o filtro le os bytes e repassa a requisicao
 * embrulhada em {@link CorpoReproduzivel}, que serve esses bytes de novo. O
 * {@code ContentCachingRequestWrapper} do Spring <i>nao</i> serve aqui: ele
 * guarda o que foi lido para inspecao posterior, mas nao reabre o stream para
 * quem vier depois — o controller receberia corpo vazio.
 */
@Component
public class RejeitaPrecoNoCorpoFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(RejeitaPrecoNoCorpoFilter.class);

	/** Os mesmos tres nomes que o Node barra. */
	private static final Set<String> CAMPOS_PROIBIDOS = Set.of("amount", "price", "value");

	private static final String ROTA_AGENDAMENTOS = "/api/v1/appointments";

	private final ObjectMapper json;

	public RejeitaPrecoNoCorpoFilter(ObjectMapper json) {
		this.json = json;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest requisicao) {
		return !("POST".equalsIgnoreCase(requisicao.getMethod())
				&& requisicao.getRequestURI().startsWith(ROTA_AGENDAMENTOS));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta,
			FilterChain cadeia) throws ServletException, IOException {

		byte[] corpo = requisicao.getInputStream().readAllBytes();

		if (temCampoDePreco(corpo)) {
			log.warn("Tentativa de enviar valor no corpo do agendamento: rota={}",
					requisicao.getRequestURI());
			responder400(resposta);
			return;
		}

		cadeia.doFilter(new CorpoReproduzivel(requisicao, corpo), resposta);
	}

	/**
	 * {@code true} se o objeto JSON de primeiro nivel tiver algum dos campos
	 * proibidos.
	 *
	 * <p>Corpo ilegivel passa adiante de proposito: quem recusa JSON malformado
	 * e o conversor de mensagem, com a resposta propria dele. Um filtro que
	 * tambem opinasse sobre sintaxe daria duas respostas diferentes para o
	 * mesmo erro, dependendo da rota.
	 */
	private boolean temCampoDePreco(byte[] corpo) {
		if (corpo.length == 0) {
			return false;
		}
		try {
			JsonNode raiz = this.json.readTree(new String(corpo, StandardCharsets.UTF_8));
			if (!raiz.isObject()) {
				return false;
			}
			for (String campo : CAMPOS_PROIBIDOS) {
				if (raiz.has(campo)) {
					return true;
				}
			}
			return false;
		}
		catch (RuntimeException ex) {
			return false;
		}
	}

	private void responder400(HttpServletResponse resposta) throws IOException {
		resposta.setStatus(HttpStatus.BAD_REQUEST.value());
		resposta.setContentType(MediaType.APPLICATION_JSON_VALUE);
		resposta.setCharacterEncoding("UTF-8");
		resposta.getWriter().write(this.json.writeValueAsString(
				ApiResponse.erro("Bad Request", "Invalid request payload")));
	}

	/**
	 * Uma requisicao cujo corpo ja foi lido e pode ser lido de novo.
	 *
	 * <p>Serve os bytes que o filtro consumiu, para que o conversor de mensagem
	 * do Spring encontre o corpo intacto. {@code getReader()} e sobrescrito
	 * junto com {@code getInputStream()} porque os dois precisam vir da mesma
	 * copia — deixar um deles no original devolveria um stream vazio para quem
	 * usasse o outro.
	 */
	private static final class CorpoReproduzivel extends HttpServletRequestWrapper {

		private final byte[] corpo;

		CorpoReproduzivel(HttpServletRequest original, byte[] corpo) {
			super(original);
			this.corpo = corpo;
		}

		@Override
		public ServletInputStream getInputStream() {
			ByteArrayInputStream bytes = new ByteArrayInputStream(this.corpo);
			return new ServletInputStream() {

				@Override
				public int read() {
					return bytes.read();
				}

				@Override
				public boolean isFinished() {
					return bytes.available() == 0;
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setReadListener(ReadListener listener) {
					// IO assincrono nao e usado nesta aplicacao.
					throw new UnsupportedOperationException("leitura assincrona nao suportada");
				}

			};
		}

		@Override
		public BufferedReader getReader() {
			return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
		}

	}

}
