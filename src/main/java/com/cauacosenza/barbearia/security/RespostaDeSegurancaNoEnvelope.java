package com.cauacosenza.barbearia.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.cauacosenza.barbearia.dto.response.ApiResponse;

import tools.jackson.databind.ObjectMapper;

/**
 * Faz o 401 e o 403 do Spring Security sairem no envelope
 * {@link ApiResponse}.
 *
 * <p>Sem isto o Spring Security responde com corpo vazio (ou com a pagina de
 * erro padrao), e o {@code client.ts} do frontend quebra: ele faz
 * {@code res.data.success} em <b>toda</b> resposta. O {@code AuthContext}
 * depende disso especificamente — ele chama {@code GET /admin/health} no boot
 * e usa a resposta para decidir entre mostrar o painel e redirecionar para o
 * login.
 *
 * <p><b>Por que nao o {@code GlobalExceptionHandler}.</b> O
 * {@code @RestControllerAdvice} so alcanca excecoes lancadas de dentro do
 * {@code DispatcherServlet}. A cadeia de filtros do Spring Security roda
 * <i>antes</i> dele, entao uma requisicao sem cookie nunca chega a um
 * controller e o advice nunca e consultado. Estes dois pontos de extensao sao
 * o unico lugar onde essas respostas podem ser formatadas.
 *
 * <p>As mensagens sao as do {@code middleware/auth.ts} do Node, literalmente:
 * {@code "Invalid or expired token"} no 401 e {@code "Admin access required"}
 * no 403. A distincao importa e e util: 401 significa "nao sei quem voce e"
 * (o painel manda para o login), 403 significa "sei quem voce e e nao basta"
 * (mandar para o login seria um laco infinito, porque logar de novo daria o
 * mesmo papel).
 */
@Component
public class RespostaDeSegurancaNoEnvelope implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final ObjectMapper json;

	public RespostaDeSegurancaNoEnvelope(ObjectMapper json) {
		this.json = json;
	}

	/** 401 — sem cookie, ou com token invalido/expirado. */
	@Override
	public void commence(HttpServletRequest requisicao, HttpServletResponse resposta,
			AuthenticationException excecao) throws IOException {

		escrever(resposta, HttpStatus.UNAUTHORIZED, "Unauthorized", "Invalid or expired token");
	}

	/** 403 — autenticado, mas sem o papel exigido. */
	@Override
	public void handle(HttpServletRequest requisicao, HttpServletResponse resposta,
			AccessDeniedException excecao) throws IOException {

		escrever(resposta, HttpStatus.FORBIDDEN, "Forbidden", "Admin access required");
	}

	private void escrever(HttpServletResponse resposta, HttpStatus status, String erro, String mensagem)
			throws IOException {

		// A excecao recebida nunca entra na resposta: a do Spring Security
		// descreve qual regra barrou o pedido, que e informacao util para quem
		// esta sondando a API.
		resposta.setStatus(status.value());
		resposta.setContentType(MediaType.APPLICATION_JSON_VALUE);
		resposta.setCharacterEncoding("UTF-8");
		resposta.getWriter().write(this.json.writeValueAsString(ApiResponse.erro(erro, mensagem)));
	}

}
