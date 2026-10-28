package com.cauacosenza.barbearia.dto.response;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * O envelope de <b>toda</b> resposta da API — sucesso e erro, sem excecao.
 *
 * <pre>
 * { "success": bool, "data": T|null, "error": string|null,
 *   "message": string, "timestamp": "2026-10-27T22:13:00.000Z" }
 * </pre>
 *
 * <p>Reproduz {@code createApiResponse} e {@code createErrorResponse} do
 * backend Node. A ordem dos campos vem de {@code @JsonPropertyOrder}: JSON nao
 * tem ordem semantica e nenhum cliente depende dela, mas um diff campo a campo
 * entre os dois backends fica ilegivel se as chaves sairem embaralhadas, e
 * esta fase se mede por esse diff.
 *
 * <p><b>O {@code timestamp} e o detalhe que nao se ve.</b> O Node usa
 * {@code new Date().toISOString()}, que produz sempre UTC com
 * <i>exatamente</i> tres casas de milissegundo e um {@code Z} literal:
 * {@code 2026-10-27T22:13:00.000Z}. O padrao do Jackson para {@link Instant}
 * e outro — epoch em segundos com fracao, ou ISO com precisao variavel, que
 * omite os milissegundos quando sao zero. Por isso
 * {@code JacksonConfig} instala um serializador proprio: sem ele, um em cada
 * mil timestamps sairia {@code ...T22:13:00Z} e o diff acusaria diferenca
 * onde nao ha.
 *
 * @param <T> o payload; {@code null} em toda resposta de erro
 */
@JsonPropertyOrder({ "success", "data", "error", "message", "timestamp" })
public record ApiResponse<T>(
		boolean success,
		T data,
		String error,
		String message,
		Instant timestamp) {

	/** O {@code message} default do {@code createApiResponse} do Node. */
	private static final String MENSAGEM_PADRAO = "Success";

	/** Sucesso com payload e a mensagem padrao. */
	public static <T> ApiResponse<T> ok(T data) {
		return ok(data, MENSAGEM_PADRAO);
	}

	/** Sucesso com payload e mensagem propria. */
	public static <T> ApiResponse<T> ok(T data, String message) {
		return new ApiResponse<>(true, data, null, message, Instant.now());
	}

	/**
	 * Erro. {@code data} e sempre {@code null} — um envelope de erro que
	 * carrega payload convida o cliente a ler dado de uma resposta que falhou.
	 *
	 * @param error o codigo curto ({@code "Not Found"}, {@code "Conflict"})
	 * @param message o texto em portugues que a tela pode exibir
	 */
	public static <T> ApiResponse<T> erro(String error, String message) {
		return new ApiResponse<>(false, null, error, message, Instant.now());
	}

}
