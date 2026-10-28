package com.cauacosenza.barbearia.config;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Ajusta o Jackson para falar exatamente a mesma lingua que o backend Node.
 *
 * <p><b>Jackson 3.</b> O Spring Boot 4 trocou o Jackson 2 (
 * {@code com.fasterxml.jackson.databind}) pelo Jackson 3
 * ({@code tools.jackson.databind}). As anotacoes continuam em
 * {@code com.fasterxml.jackson.annotation}, o que torna facil escrever um
 * {@code import} do pacote errado e receber "cannot be resolved" num projeto
 * em que o Jackson obviamente existe. Os tipos mudaram de nome junto:
 * {@code JsonSerializer} virou {@link ValueSerializer},
 * {@code SerializerProvider} virou {@link SerializationContext}, e o
 * {@code Jackson2ObjectMapperBuilderCustomizer} virou
 * {@link JsonMapperBuilderCustomizer}.
 *
 * <h2>Datas no formato de {@code toISOString()}</h2>
 *
 * <p>O JavaScript so tem um jeito de serializar data em JSON, e e
 * {@code new Date().toISOString()}: sempre UTC, sempre com tres casas de
 * milissegundo, sempre terminando em {@code Z}. O Jackson tem varios, e
 * nenhum deles e esse — o ISO nativo para {@link Instant} <b>omite</b> a parte
 * fracionaria quando ela e zero, produzindo {@code 2026-10-27T22:13:00Z} em
 * vez de {@code 2026-10-27T22:13:00.000Z}.
 *
 * <p>Isso importaria pouco se fosse so o {@code timestamp} do envelope, mas o
 * mesmo formato vale para {@code scheduledAt}, que o frontend passa direto
 * para {@code new Date(...)} e para o {@code Intl.DateTimeFormat}. Os dois
 * formatos sao aceitos pelo JavaScript, entao a tela funcionaria de qualquer
 * jeito — o que quebraria e a comparacao campo a campo entre os dois
 * backends, que e o criterio desta fase. Um serializador proprio custa dez
 * linhas e remove a duvida.
 *
 * <h2>Propriedade desconhecida e erro</h2>
 *
 * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES} e o equivalente do {@code .strict()}
 * que o Zod aplica aos schemas de criacao de agendamento: um campo a mais no
 * corpo nao passa despercebido. Sem isso, um cliente que mandasse
 * {@code amountCents} junto com o pedido veria o campo ser ignorado em
 * silencio — e silencio e exatamente o que nao se quer quando alguem tenta
 * injetar preco.
 *
 * <p><b>Divergencia consciente:</b> no Node o {@code .strict()} esta apenas
 * nos schemas de agendamento; o {@code AvailableTimesSchema} nao o tem e
 * descartaria campo extra sem reclamar. Aqui a regra e global, entao
 * {@code POST /available-times} e mais rigoroso que o Node. O frontend nunca
 * manda campo a mais em rota nenhuma, entao a diferenca nao o alcanca.
 */
@Configuration
public class JacksonConfig {

	/** {@code new Date().toISOString()}: UTC, tres casas, {@code Z} literal. */
	private static final DateTimeFormatter ISO_JAVASCRIPT =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

	@Bean
	JsonMapperBuilderCustomizer barbeariaJacksonCustomizer() {
		SimpleModule isoJavaScript = new SimpleModule("iso-javascript");
		isoJavaScript.addSerializer(Instant.class, new InstantToIsoStringSerializer());

		return builder -> builder
				.addModule(isoJavaScript)
				.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
	}

	/**
	 * Serializa {@link Instant} como string ISO-8601 no formato do
	 * {@code toISOString()} do JavaScript.
	 */
	private static final class InstantToIsoStringSerializer extends ValueSerializer<Instant> {

		@Override
		public void serialize(Instant valor, JsonGenerator gerador, SerializationContext contexto) {
			gerador.writeString(ISO_JAVASCRIPT.format(valor));
		}

	}

}
