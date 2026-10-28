package com.cauacosenza.barbearia.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * O relogio da aplicacao, como bean.
 *
 * <p>Tres regras deste sistema dependem do instante atual: a antecedencia
 * minima de 15 minutos nos horarios de hoje, a recusa de agendamento no
 * passado e o prazo de uma hora para cancelar. Com {@code Instant.now()}
 * espalhado pelos services, testar qualquer uma delas exigiria dormir ou
 * agendar para datas distantes — e o teste de borda ("faltando exatamente uma
 * hora") seria impossivel de escrever sem ficar intermitente.
 *
 * <p>Injetado, o teste troca o bean por
 * {@link Clock#fixed(java.time.Instant, java.time.ZoneId)} e a borda vira uma
 * asercao comum.
 *
 * <p>{@link Clock#systemUTC()} e nao {@code systemDefaultZone()}: a hora de
 * Brasilia e obtida sempre convertendo explicitamente pelo
 * {@code SlotQueryMapper}, e um relogio com fuso proprio abriria a porta para
 * alguem ler a hora local do servidor por engano — que em producao e UTC e no
 * laptop do desenvolvedor nao e.
 */
@Configuration
public class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
