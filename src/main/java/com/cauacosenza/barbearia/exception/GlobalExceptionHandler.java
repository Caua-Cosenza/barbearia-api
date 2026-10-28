package com.cauacosenza.barbearia.exception;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import com.cauacosenza.barbearia.domain.exception.BusinessException;
import com.cauacosenza.barbearia.dto.response.ApiResponse;

/**
 * Traduz toda excecao para o envelope {@link ApiResponse}.
 *
 * <p>Existe para que <b>nenhum controller tenha {@code try/catch}</b>. Os
 * services lancam excecao de dominio, este advice decide status e texto. Sem
 * ele, uma falha nao tratada sairia no formato de erro padrao do Spring
 * ({@code {"timestamp":..., "status":..., "path":...}}), que o frontend nao
 * sabe ler: o {@code client.ts} espera {@code success}, {@code data},
 * {@code error}, {@code message} e {@code timestamp} em toda resposta,
 * inclusive nas de erro.
 *
 * <h2>Status que divergem do plano desta fase</h2>
 *
 * <p>Erro de validacao responde <b>422</b> e nao 400. Nao e preferencia: o
 * {@code errorHandler.ts} do Node devolve 422 para {@code ZodError}, e o
 * criterio da fase e equivalencia. O 400 fica para erro de negocio que nasce
 * de entrada bem formada — dia sem jornada, horario fora do expediente.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/** O {@code error} que o Node usa para falha de schema. */
	private static final String ERRO_VALIDACAO = "Validation Error";

	/**
	 * Falha de negocio prevista. Um unico handler para toda a hierarquia: a
	 * propria excecao carrega status, codigo e mensagem.
	 *
	 * <p>Log em {@code debug}, nao em {@code error}: "o horario acabou de ser
	 * tomado" acontece o tempo todo e nao e defeito. Registrar isso como erro
	 * encheria o log de ruido e faria o alerta perder o sentido.
	 */
	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ApiResponse<Void>> tratarNegocio(BusinessException ex) {
		log.debug("Regra de negocio recusou a operacao: {}", ex.getMessage(), ex);
		return ResponseEntity.status(ex.getStatus())
				.body(ApiResponse.erro(ex.getCodigo(), ex.getMessage()));
	}

	/**
	 * Corpo que nao passou na Bean Validation — o equivalente do
	 * {@code ZodError}.
	 *
	 * <p>A mensagem reproduz o formato do Node:
	 * {@code campo: motivo, campo: motivo}, <b>na ordem em que os campos sao
	 * declarados no DTO</b> — que e a ordem do schema Zod correspondente, e
	 * portanto o mesmo texto que o Node produz. O Hibernate Validator entrega
	 * as violacoes em ordem imprevisivel, entao alguma ordenacao e obrigatoria
	 * para o texto nao variar entre execucoes; ordenar por declaracao custa o
	 * mesmo que ordenar por alfabeto e, de quebra, bate com o Node.
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> tratarCorpoInvalido(MethodArgumentNotValidException ex) {
		Comparator<FieldError> ordemDoDto = ordemDeDeclaracao(ex.getBindingResult().getTarget());

		String detalhe = ex.getBindingResult().getFieldErrors().stream()
				.sorted(ordemDoDto)
				.map(erro -> erro.getField() + ": " + erro.getDefaultMessage())
				.collect(Collectors.joining(", "));

		return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
				.body(ApiResponse.erro(ERRO_VALIDACAO, detalhe));
	}

	/**
	 * Ordena os erros pela posicao do campo no record.
	 *
	 * <p>Todos os DTOs de entrada deste projeto sao records, e
	 * {@link Class#getRecordComponents()} devolve os componentes na ordem em
	 * que foram escritos. Para qualquer outro alvo — ou para um campo aninhado,
	 * cujo caminho nao e um nome de componente — sobra a ordem alfabetica, que
	 * ao menos e estavel.
	 */
	private static Comparator<FieldError> ordemDeDeclaracao(Object alvo) {
		List<String> declarados = (alvo == null || !alvo.getClass().isRecord())
				? List.of()
				: Arrays.stream(alvo.getClass().getRecordComponents())
						.map(RecordComponent::getName)
						.toList();

		return Comparator
				.comparingInt((FieldError erro) -> {
					int posicao = declarados.indexOf(erro.getField());
					return (posicao >= 0) ? posicao : Integer.MAX_VALUE;
				})
				.thenComparing(FieldError::getField);
	}

	/**
	 * Parametro de rota invalido — hoje, o {@code token} que nao e UUID.
	 *
	 * <p>Chega por um caminho diferente do anterior porque a validacao esta em
	 * {@code @PathVariable} e nao num corpo, mas a resposta e a mesma: 422 com
	 * {@code Validation Error}.
	 */
	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ApiResponse<Void>> tratarParametroInvalido(ConstraintViolationException ex) {
		String detalhe = ex.getConstraintViolations().stream()
				.map(violacao -> nomeDoParametro(violacao) + ": " + violacao.getMessage())
				.sorted()
				.collect(Collectors.joining(", "));

		return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
				.body(ApiResponse.erro(ERRO_VALIDACAO, detalhe));
	}

	/**
	 * O nome do parametro que falhou, extraido do caminho da violacao.
	 *
	 * <p>O {@code propertyPath} de um parametro de metodo vem como
	 * {@code metodo.parametro} — fica so o ultimo trecho, que e o nome que o
	 * cliente enviou e o unico que significa algo para ele. O prefixo existe
	 * para igualar o Node, cujo {@code ZodError} monta
	 * {@code `${e.path.join('.')}: ${e.message}`}.
	 */
	private static String nomeDoParametro(ConstraintViolation<?> violacao) {
		String caminho = violacao.getPropertyPath().toString();
		int ultimoPonto = caminho.lastIndexOf('.');
		return (ultimoPonto >= 0) ? caminho.substring(ultimoPonto + 1) : caminho;
	}

	/**
	 * JSON malformado, ou <b>propriedade desconhecida</b> — o
	 * {@code FAIL_ON_UNKNOWN_PROPERTIES} do {@code JacksonConfig} falha por
	 * aqui, que e o equivalente do {@code .strict()} do Zod.
	 *
	 * <p>A mensagem e generica de proposito. A do Jackson cita a classe Java, a
	 * posicao no stream e as propriedades conhecidas do DTO — um mapa da
	 * estrutura interna entregue a quem mandou corpo invalido.
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponse<Void>> tratarCorpoIlegivel(HttpMessageNotReadableException ex) {
		log.debug("Corpo da requisicao ilegivel", ex);
		return ResponseEntity.badRequest()
				.body(ApiResponse.erro("Bad Request", "Corpo da requisição inválido"));
	}

	/** Rota inexistente, no envelope — nao na pagina de erro do Spring. */
	@ExceptionHandler(NoHandlerFoundException.class)
	public ResponseEntity<ApiResponse<Void>> tratarRotaInexistente(NoHandlerFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(ApiResponse.erro("Not Found", "Rota não encontrada: " + ex.getRequestURL()));
	}

	/**
	 * O catch-all. Tudo que chegou aqui e defeito, nao entrada do cliente.
	 *
	 * <p><b>A stack vai para o log e nada dela vai para a resposta.</b> Uma
	 * mensagem de excecao Java pode conter SQL, nome de coluna, caminho de
	 * arquivo e trecho do dado que a causou — e esse dado, neste sistema, pode
	 * ser PII de cliente. O cliente recebe sempre as mesmas sete palavras.
	 *
	 * <p>Que este handler exista nao quer dizer que ele deva ser alcancado:
	 * um 500 aqui e sempre um caso que faltou mapear. O mais importante deles
	 * ja foi — o deadlock da constraint de exclusao, que antes de a Fase 4
	 * revelar o comportamento cairia exatamente neste metodo, em 18 de cada 20
	 * disputas por horario.
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> tratarInesperado(Exception ex) {
		log.error("Erro inesperado", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ApiResponse.erro("Internal Server Error", "Erro inesperado"));
	}

}
